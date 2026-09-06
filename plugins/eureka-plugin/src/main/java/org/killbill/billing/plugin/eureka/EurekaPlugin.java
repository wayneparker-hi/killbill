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
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.plugin.eureka;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.killbill.CreatorName;
import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.billing.runtime.api.ServiceDiscoveryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.InstanceInfo.PortType;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.appinfo.UniqueIdentifier;
import com.netflix.config.ConfigurationManager;
import com.netflix.discovery.CommonConstants;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.DiscoveryManager;
import com.netflix.discovery.EurekaClient;

/**
 * Registers this Kill Bill node with a Eureka server and publishes a {@link ServiceDiscoveryRegistry}
 * so the rest of the system can look up peers.
 * <p>
 * Ported from the OSGi eureka bundle. Off unless {@code org.killbill.eureka} is {@code true}.
 * <p>
 * The Eureka client runs heartbeat threads, so it is handed to the resource registry rather than
 * shut down by hand -- the same rule every other long-lived resource follows here, and the reason a
 * disabled-then-enabled plugin does not leave two clients heartbeating for the same instance.
 */
public class EurekaPlugin implements Plugin {

    private static final Logger logger = LoggerFactory.getLogger(EurekaPlugin.class);

    public static final String REGISTRATION_NAME = "killbill-eureka";

    private static final String ENABLED_PROPERTY = "org.killbill.eureka";

    @Override
    public void start(final PluginContext context) {
        if (!Boolean.parseBoolean(property(context, ENABLED_PROPERTY, "false"))) {
            logger.info("Eureka integration is disabled ({} is not true)", ENABLED_PROPERTY);
            return;
        }

        final Properties properties = platformProperties(context);
        ConfigurationManager.loadProperties(properties);

        final String namespace = Optional.ofNullable(properties.getProperty("eureka.namespace"))
                                         .orElse(CommonConstants.DEFAULT_CONFIG_NAMESPACE);
        final KillbillEurekaInstanceConfig instanceConfig = new KillbillEurekaInstanceConfig(namespace);
        final DefaultEurekaClientConfig clientConfig = new DefaultEurekaClientConfig(namespace);

        DiscoveryManager.getInstance().setEurekaInstanceConfig(instanceConfig);
        DiscoveryManager.getInstance().setEurekaClientConfig(clientConfig);

        final InstanceInfo instanceInfo = createInstanceInfo(instanceConfig);
        final ApplicationInfoManager applicationInfoManager = new ApplicationInfoManager(instanceConfig, instanceInfo);

        final EurekaClient client = new DiscoveryClient(applicationInfoManager, clientConfig);
        // Managed: the client heartbeats on its own threads, which would otherwise outlive the
        // plugin and keep its ClassLoader alive.
        context.resources().onClose(client::shutdown);

        context.services().register(ServiceDiscoveryRegistry.class,
                                    new EurekaServiceRegistry(applicationInfoManager),
                                    Map.of("killbill.pluginName", REGISTRATION_NAME));
        logger.info("Registered this node with Eureka under namespace {}", namespace);
    }

    @Override
    public void stop() {
        // The client is shut down by the resource registry, the registration withdrawn by the
        // service registry.
    }

    /**
     * Eureka configures itself from a {@link Properties} object, but the platform exposes settings
     * one key at a time. The keys Eureka reads are not knowable in advance (they vary by namespace
     * and data-centre), so this copies across what the plugin's own descriptor declares and lets
     * Eureka's own defaults cover the rest.
     */
    private static Properties platformProperties(final PluginContext context) {
        final Properties properties = new Properties();
        properties.putAll(context.config().all());
        return properties;
    }

    private static String property(final PluginContext context, final String key, final String fallback) {
        return context.config()
                      .find(key)
                      .or(() -> Optional.ofNullable(
                              context.getPlatformService(KillbillConfigSource.class).getString(key)))
                      .orElse(fallback);
    }

    private static InstanceInfo createInstanceInfo(final EurekaInstanceConfig eurekaConfig) {
        // Build the lease information to be passed to the server based on eurekaConfig
        final LeaseInfo.Builder leaseInfoBuilder = LeaseInfo.Builder.newBuilder()
                                                                    .setRenewalIntervalInSecs(eurekaConfig.getLeaseRenewalIntervalInSeconds())
                                                                    .setDurationInSecs(eurekaConfig.getLeaseExpirationDurationInSeconds());

        // Builder the instance information to be registered with eureka server
        final InstanceInfo.Builder builder = InstanceInfo.Builder.newBuilder();

        // Set the appropriate id for the InstanceInfo, falling back to datacenter Id if applicable, else hostname
        String instanceId = eurekaConfig.getInstanceId();
        final DataCenterInfo dataCenterInfo = eurekaConfig.getDataCenterInfo();
        if (instanceId == null || instanceId.isEmpty()) {
            if (dataCenterInfo instanceof UniqueIdentifier) {
                instanceId = ((UniqueIdentifier) dataCenterInfo).getId();
            } else {
                instanceId = CreatorName.get();
            }
        }

        final String hostName = eurekaConfig.getHostName(false);

        builder.setNamespace(eurekaConfig.getNamespace())
               .setInstanceId(instanceId)
               .setAppName(eurekaConfig.getAppname())
               .setAppGroupName(eurekaConfig.getAppGroupName())
               .setDataCenterInfo(eurekaConfig.getDataCenterInfo())
               .setIPAddr(eurekaConfig.getIpAddress())
               .setHostName(hostName)
               .setPort(eurekaConfig.getNonSecurePort())
               .enablePort(PortType.UNSECURE, eurekaConfig.isNonSecurePortEnabled())
               .setSecurePort(eurekaConfig.getSecurePort())
               .enablePort(PortType.SECURE, eurekaConfig.getSecurePortEnabled())
               .setVIPAddress(eurekaConfig.getVirtualHostName())
               .setSecureVIPAddress(eurekaConfig.getSecureVirtualHostName())
               .setHomePageUrl(eurekaConfig.getHomePageUrlPath(), eurekaConfig.getHomePageUrl())
               .setStatusPageUrl(eurekaConfig.getStatusPageUrlPath(), eurekaConfig.getStatusPageUrl())
               .setASGName(eurekaConfig.getASGName())
               .setHealthCheckUrls(eurekaConfig.getHealthCheckUrlPath(), eurekaConfig.getHealthCheckUrl(), eurekaConfig.getSecureHealthCheckUrl());

        // Start off with the STARTING state to avoid traffic
        if (!eurekaConfig.isInstanceEnabledOnit()) {
            final InstanceStatus initialStatus = InstanceStatus.STARTING;
            logger.info("Setting initial instance status as: {}", initialStatus);
            builder.setStatus(initialStatus);
        } else {
            logger.info("Setting initial instance status as: {}. This may be too early for the instance to advertise " + "itself as available. You would instead want to control this via a healthcheck handler.", InstanceStatus.UP);
        }

        // Add any user-specific metadata information
        for (final Map.Entry<String, String> mapEntry : eurekaConfig.getMetadataMap().entrySet()) {
            final String key = mapEntry.getKey();
            final String value = mapEntry.getValue();
            builder.add(key, value);
        }

        final InstanceInfo instanceInfo = builder.build();
        instanceInfo.setLeaseInfo(leaseInfoBuilder.build());

        return instanceInfo;
    }
}
