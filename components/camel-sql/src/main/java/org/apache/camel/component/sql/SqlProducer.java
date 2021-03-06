/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultProducer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.PreparedStatementCreator;

public class SqlProducer extends DefaultProducer {
    private String query;
    private JdbcTemplate jdbcTemplate;
    private boolean batch;
    private boolean alwaysPopulateStatement;
    private SqlPrepareStatementStrategy sqlPrepareStatementStrategy;
    private int parametersCount;

    public SqlProducer(SqlEndpoint endpoint, String query, JdbcTemplate jdbcTemplate, SqlPrepareStatementStrategy sqlPrepareStatementStrategy,
                       boolean batch, boolean alwaysPopulateStatement) {
        super(endpoint);
        this.jdbcTemplate = jdbcTemplate;
        this.sqlPrepareStatementStrategy = sqlPrepareStatementStrategy;
        this.query = query;
        this.batch = batch;
        this.alwaysPopulateStatement = alwaysPopulateStatement;
    }

    @Override
    public SqlEndpoint getEndpoint() {
        return (SqlEndpoint) super.getEndpoint();
    }

    public void process(final Exchange exchange) throws Exception {
        String queryHeader = exchange.getIn().getHeader(SqlConstants.SQL_QUERY, String.class);

        final String sql = queryHeader != null ? queryHeader : query;
        final String preparedQuery = sqlPrepareStatementStrategy.prepareQuery(sql, getEndpoint().isAllowNamedParameters());

        // CAMEL-7313 - check whether to return generated keys
        final Boolean shouldRetrieveGeneratedKeys =
            exchange.getIn().getHeader(SqlConstants.SQL_RETRIEVE_GENERATED_KEYS, false, Boolean.class);

        PreparedStatementCreator statementCreator = new PreparedStatementCreator() {
            @Override
            public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
                if (!shouldRetrieveGeneratedKeys) {
                    return con.prepareStatement(preparedQuery);
                } else {
                    Object expectedGeneratedColumns = exchange.getIn().getHeader(SqlConstants.SQL_GENERATED_COLUMNS);
                    if (expectedGeneratedColumns == null) {
                        return con.prepareStatement(preparedQuery, Statement.RETURN_GENERATED_KEYS);
                    } else if (expectedGeneratedColumns instanceof String[]) {
                        return con.prepareStatement(preparedQuery, (String[]) expectedGeneratedColumns);
                    } else if (expectedGeneratedColumns instanceof int[]) {
                        return con.prepareStatement(preparedQuery, (int[]) expectedGeneratedColumns);
                    } else {
                        throw new IllegalArgumentException(
                                "Header specifying expected returning columns isn't an instance of String[] or int[] but "
                                        + expectedGeneratedColumns.getClass());
                    }
                }
            }
        };

        jdbcTemplate.execute(statementCreator, new PreparedStatementCallback<Map<?, ?>>() {
            public Map<?, ?> doInPreparedStatement(PreparedStatement ps) throws SQLException {
                int expected = parametersCount > 0 ? parametersCount : ps.getParameterMetaData().getParameterCount();

                // only populate if really needed
                if (alwaysPopulateStatement || expected > 0) {
                    // transfer incoming message body data to prepared statement parameters, if necessary
                    if (batch) {
                        Iterator<?> iterator = exchange.getIn().getBody(Iterator.class);
                        while (iterator != null && iterator.hasNext()) {
                            Object value = iterator.next();
                            Iterator<?> i = sqlPrepareStatementStrategy.createPopulateIterator(sql, preparedQuery, expected, exchange, value);
                            sqlPrepareStatementStrategy.populateStatement(ps, i, expected);
                            ps.addBatch();
                        }
                    } else {
                        Iterator<?> i = sqlPrepareStatementStrategy.createPopulateIterator(sql, preparedQuery, expected, exchange, exchange.getIn().getBody());
                        sqlPrepareStatementStrategy.populateStatement(ps, i, expected);
                    }
                }

                boolean isResultSet = false;

                // execute the prepared statement and populate the outgoing message
                if (batch) {
                    int[] updateCounts = ps.executeBatch();
                    int total = 0;
                    for (int count : updateCounts) {
                        total += count;
                    }
                    exchange.getIn().setHeader(SqlConstants.SQL_UPDATE_COUNT, total);
                } else {
                    isResultSet = ps.execute();
                    if (isResultSet) {
                        // preserve headers first, so we can override the SQL_ROW_COUNT header
                        exchange.getOut().getHeaders().putAll(exchange.getIn().getHeaders());

                        ResultSet rs = ps.getResultSet();
                        SqlOutputType outputType = getEndpoint().getOutputType();
                        log.trace("Got result list from query: {}, outputType={}", rs, outputType);
                        if (outputType == SqlOutputType.SelectList) {
                            List<?> data = getEndpoint().queryForList(rs, true);
                            // for noop=true we still want to enrich with the row count header
                            if (getEndpoint().isNoop()) {
                                exchange.getOut().setBody(exchange.getIn().getBody());
                            } else if (getEndpoint().getOutputHeader() != null) {
                                exchange.getOut().setBody(exchange.getIn().getBody());
                                exchange.getOut().setHeader(getEndpoint().getOutputHeader(), data);
                            } else {
                                exchange.getOut().setBody(data);
                            }
                            exchange.getOut().setHeader(SqlConstants.SQL_ROW_COUNT, data.size());
                        } else if (outputType == SqlOutputType.SelectOne) {
                            Object data = getEndpoint().queryForObject(rs);
                            if (data != null) {
                                // for noop=true we still want to enrich with the row count header
                                if (getEndpoint().isNoop()) {
                                    exchange.getOut().setBody(exchange.getIn().getBody());
                                } else if (getEndpoint().getOutputHeader() != null) {
                                    exchange.getOut().setBody(exchange.getIn().getBody());
                                    exchange.getOut().setHeader(getEndpoint().getOutputHeader(), data);
                                } else {
                                    exchange.getOut().setBody(data);
                                }
                                exchange.getOut().setHeader(SqlConstants.SQL_ROW_COUNT, 1);
                            }
                        } else {
                            throw new IllegalArgumentException("Invalid outputType=" + outputType);
                        }
                    } else {
                        exchange.getIn().setHeader(SqlConstants.SQL_UPDATE_COUNT, ps.getUpdateCount());
                    }
                }

                if (shouldRetrieveGeneratedKeys) {
                    if (isResultSet) {
                        // we won't return generated keys for SELECT statements
                        exchange.getOut().setHeader(SqlConstants.SQL_GENERATED_KEYS_DATA, Collections.EMPTY_LIST);
                        exchange.getOut().setHeader(SqlConstants.SQL_GENERATED_KEYS_ROW_COUNT, 0);
                    } else {
                        List<?> generatedKeys = getEndpoint().queryForList(ps.getGeneratedKeys(), false);
                        exchange.getOut().setHeader(SqlConstants.SQL_GENERATED_KEYS_DATA, generatedKeys);
                        exchange.getOut().setHeader(SqlConstants.SQL_GENERATED_KEYS_ROW_COUNT, generatedKeys.size());
                    }
                }

                // data is set on exchange so return null
                return null;
            }
        });
    }

    public void setParametersCount(int parametersCount) {
        this.parametersCount = parametersCount;
    }
}
