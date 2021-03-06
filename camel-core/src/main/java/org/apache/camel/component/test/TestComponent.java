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
package org.apache.camel.component.test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Endpoint;
import org.apache.camel.impl.UriEndpointComponent;
import org.apache.camel.util.CamelContextHelper;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.UnsafeUriCharactersEncoder;

/**
 * Component for testing by polling test messages from another endpoint on startup as the expected message bodies to
 * receive during testing.
 *
 * @version 
 */
public class TestComponent extends UriEndpointComponent {

    public TestComponent() {
        super(TestEndpoint.class);
    }

    public Endpoint createEndpoint(String uri) throws Exception {
        // lets not use the normal parameter handling so that all parameters are sent to the nested endpoint

        ObjectHelper.notNull(getCamelContext(), "camelContext");
        URI u = new URI(UnsafeUriCharactersEncoder.encode(uri));
        String path = u.getSchemeSpecificPart();
        if (path.startsWith("//")) {
            path = path.substring(2);
        }

        return createEndpoint(uri, path, new HashMap<String, Object>());
    }

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        Long timeout = getAndRemoveParameter(parameters, "timeout", Long.class);
        Endpoint endpoint = CamelContextHelper.getMandatoryEndpoint(getCamelContext(), remaining);

        TestEndpoint answer = new TestEndpoint(uri, this, endpoint);
        if (timeout != null) {
            answer.setTimeout(timeout);
        }
        return answer;
    }

}