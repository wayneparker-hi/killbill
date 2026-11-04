/*
 * Copyright 2020-2026 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.killbill.billing.jaxrs.resources;

import java.util.List;
import org.killbill.billing.runtime.api.ClusterPluginStatus;
import org.killbill.billing.runtime.api.NodePluginStatus;
import org.killbill.billing.runtime.api.PluginsInfoApi;
import org.killbill.billing.util.jackson.ObjectMapper;
import org.testng.annotations.Test;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class TestPluginClusterResource {
    @Test(groups = "fast")
    public void testClusterStatusResponseAndJson() throws Exception {
        final var api = mock(PluginsInfoApi.class);
        final var status = new ClusterPluginStatus("demo", "2.0.0", 7, true, 1000,
                List.of(new NodePluginStatus("node", "1.0.0", "STOPPING", 6L, 900L, false, false)), false);
        when(api.getClusterPluginsInfo()).thenReturn(List.of(status));
        final var resource = new PluginInfoResource(null, null, null, null, null, null, null, api, null, null);
        try (final var response = resource.getClusterPluginsInfo()) {
            assertEquals(response.getStatus(), 200);
            final var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response.getEntity()));
            assertEquals(json.get(0).get("revision").asLong(), 7L);
            assertEquals(json.get(0).get("nodes").get(0).get("state").asText(), "STOPPING");
            assertFalse(json.get(0).get("converged").asBoolean());
        }
        verify(api).getClusterPluginsInfo();
    }
}
