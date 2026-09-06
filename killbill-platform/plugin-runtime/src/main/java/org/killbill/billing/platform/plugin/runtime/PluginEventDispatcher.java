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

package org.killbill.billing.platform.plugin.runtime;

import java.io.IOException;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.killbill.billing.notification.plugin.api.ExtBusEvent;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.notification.plugin.api.NotificationPluginApiRetryException;
import org.killbill.billing.platform.api.KillbillService;
import org.killbill.billing.platform.api.LifecycleHandlerType;
import org.killbill.billing.platform.api.LifecycleHandlerType.LifecycleLevel;
import org.killbill.billing.platform.plugin.api.PluginServiceRegistry;
import org.killbill.bus.api.BusEvent;
import org.killbill.bus.api.PersistentBus;
import org.killbill.bus.api.PersistentBus.EventBusException;
import org.killbill.clock.Clock;
import org.killbill.commons.eventbus.AllowConcurrentEvents;
import org.killbill.commons.eventbus.Subscribe;
import org.killbill.notificationq.api.NotificationQueueService;
import org.killbill.notificationq.api.NotificationQueueService.NoSuchNotificationQueue;
import org.killbill.queue.QueueObjectMapper;
import org.killbill.queue.retry.RetryableService;
import org.killbill.queue.retry.RetryableSubscriber;
import org.killbill.queue.retry.RetryableSubscriber.SubscriberAction;
import org.killbill.queue.retry.RetryableSubscriber.SubscriberQueueHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Delivers Kill Bill's external bus events to plugins that implement {@link NotificationPluginApi}.
 * <p>
 * This is the last extension point that did not go through a registry. Under OSGi the path was
 * bus &rarr; {@code KillbillEventObservable} (a {@code java.util.Observable} subclass that reached
 * into the superclass's private {@code obs} field by reflection to iterate observers in a defined
 * order) &rarr; a dispatcher shipped inside each plugin, which adapted the observer callback into
 * {@code NotificationPluginApi}. Two indirections and a reflection hack existed only because OSGi
 * had no typed registry for this one API.
 * <p>
 * Here it is a registry like every other extension point: plugins publish a
 * {@code NotificationPluginApi}, the bridge routes it into {@link PluginServiceRegistry}, and this
 * class reads that registry on each event. Plugins no longer ship a dispatcher.
 *
 * <h2>Retries</h2>
 * Events are handled through a {@link RetryableSubscriber}, so a plugin that throws
 * {@link NotificationPluginApiRetryException} gets the event redelivered on its own schedule. That
 * requires the event to survive a round-trip through the notification queue, which is what
 * {@link PluginBusEvent} and its deserializer are for: the concrete {@code ExtBusEvent} type is
 * recorded alongside the payload so it can be reconstructed.
 *
 * <h2>One plugin's failure does not become another's</h2>
 * Each plugin is called inside its own try/catch. An ordinary exception is logged and the remaining
 * plugins still receive the event -- the same "a broken plugin is skipped" contract the invoice and
 * payment dispatchers follow. A retry request is different: it is a deliberate signal, so it
 * propagates and the event is redelivered. The redelivery goes to <em>all</em> subscribers, since
 * the queue stores one entry per event and not one per plugin; a plugin that cannot tolerate seeing
 * an event twice must deduplicate on {@code ExtBusEvent}'s user token.
 */
public class PluginEventDispatcher extends RetryableService implements KillbillService {

    private static final Logger log = LoggerFactory.getLogger(PluginEventDispatcher.class);

    private static final String QUEUE_NAME = "extBusEvent-listener";

    private final PersistentBus externalBus;
    private final PluginServiceRegistry<NotificationPluginApi> notificationPlugins;
    private final RetryableSubscriber retryableSubscriber;
    private final SubscriberQueueHandler subscriberQueueHandler = new SubscriberQueueHandler();

    @Inject
    public PluginEventDispatcher(@Named("externalBus") final PersistentBus externalBus,
                                 final PluginServiceRegistry<NotificationPluginApi> notificationPlugins,
                                 final NotificationQueueService notificationQueueService,
                                 final Clock clock) {
        super(notificationQueueService);
        this.externalBus = externalBus;
        this.notificationPlugins = notificationPlugins;
        subscriberQueueHandler.subscribe(PluginBusEvent.class,
                                         (SubscriberAction<PluginBusEvent>) event -> dispatch(event.getExtBusEvent()));
        this.retryableSubscriber = new RetryableSubscriber(clock, this, subscriberQueueHandler);
    }

    /**
     * Fans one event out to every plugin currently publishing {@link NotificationPluginApi}.
     * <p>
     * The registry is read per event rather than cached, so a plugin that starts between two events
     * receives the second one, and a plugin that stops stops receiving them immediately.
     *
     * @param event the event to deliver
     */
    private void dispatch(final ExtBusEvent event) {
        for (final String pluginName : notificationPlugins.getAllServices()) {
            final NotificationPluginApi plugin = notificationPlugins.getServiceForName(pluginName);
            if (plugin == null) {
                // Stopped between listing and lookup; nothing to do.
                continue;
            }
            try {
                plugin.onEvent(event);
            } catch (final NotificationPluginApiRetryException e) {
                // Deliberate signal from the plugin: let it reach the retryable subscriber.
                throw e;
            } catch (final RuntimeException e) {
                log.warn("Plugin {} threw while handling {}; skipping it for this event", pluginName, event, e);
            }
        }
    }

    public void register() throws EventBusException {
        externalBus.register(this);
    }

    public void unregister() throws EventBusException {
        externalBus.unregister(this);
    }

    @Override
    public String getName() {
        return KILLBILL_SERVICES.RETRIABLE_BUS_HANDLER_SERVICE.getServiceName();
    }

    @Override
    public int getRegistrationOrdering() {
        return KILLBILL_SERVICES.RETRIABLE_BUS_HANDLER_SERVICE.getRegistrationOrdering();
    }

    @LifecycleHandlerType(LifecycleLevel.INIT_SERVICE)
    public void initialize() {
        super.initialize(QUEUE_NAME, subscriberQueueHandler);
    }

    @LifecycleHandlerType(LifecycleLevel.START_SERVICE)
    public void start() {
        super.start();
    }

    @LifecycleHandlerType(LifecycleLevel.STOP_SERVICE)
    public void stop() throws NoSuchNotificationQueue {
        super.stop();
    }

    @AllowConcurrentEvents
    @Subscribe
    public void handleKillbillEvent(final ExtBusEvent extBusEvent) {
        retryableSubscriber.handleEvent(new PluginBusEvent(extBusEvent, extBusEvent.getClass()));
    }

    /**
     * An {@link ExtBusEvent} wrapped so the retry queue can store and rebuild it.
     * <p>
     * {@code ExtBusEvent} is an interface, so the concrete class is recorded alongside the payload;
     * without it the queue could serialize an event it could not deserialize.
     */
    @JsonDeserialize(using = PluginBusEventDeserializer.class)
    protected static class PluginBusEvent implements BusEvent {

        private final ExtBusEvent extBusEvent;
        private final Class<?> extBusEventClass;

        @JsonCreator
        public PluginBusEvent(@JsonProperty("extBusEvent") final ExtBusEvent extBusEvent,
                              @JsonProperty("extBusEventClass") final Class<?> extBusEventClass) {
            this.extBusEvent = extBusEvent;
            this.extBusEventClass = extBusEventClass;
        }

        public ExtBusEvent getExtBusEvent() {
            return extBusEvent;
        }

        public Class<?> getExtBusEventClass() {
            return extBusEventClass;
        }

        @Override
        public Long getSearchKey1() {
            final UUID accountId = extBusEvent.getAccountId();
            return accountId == null ? null : accountId.getMostSignificantBits() & Long.MAX_VALUE;
        }

        @Override
        public Long getSearchKey2() {
            final UUID tenantId = extBusEvent.getTenantId();
            return tenantId == null ? null : tenantId.getMostSignificantBits() & Long.MAX_VALUE;
        }

        @Override
        public UUID getUserToken() {
            return extBusEvent.getUserToken();
        }

        @Override
        public String toString() {
            return "PluginBusEvent{extBusEvent=" + extBusEvent + ", extBusEventClass=" + extBusEventClass + '}';
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final PluginBusEvent that = (PluginBusEvent) o;
            return java.util.Objects.equals(extBusEvent, that.extBusEvent)
                   && java.util.Objects.equals(extBusEventClass, that.extBusEventClass);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(extBusEvent, extBusEventClass);
        }
    }

    protected static class PluginBusEventDeserializer extends JsonDeserializer<PluginBusEvent> {

        private static final ObjectMapper objectMapper = QueueObjectMapper.get();

        @Override
        @SuppressWarnings("unchecked")
        public PluginBusEvent deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
            final JsonNode node = p.getCodec().readTree(p);
            final Class<ExtBusEvent> extBusEventClass;
            try {
                extBusEventClass = (Class<ExtBusEvent>) Class.forName(node.get("extBusEventClass").textValue());
            } catch (final ClassNotFoundException e) {
                throw new IOException(e);
            }
            return new PluginBusEvent(objectMapper.treeToValue(node.get("extBusEvent"), extBusEventClass),
                                      extBusEventClass);
        }
    }
}
