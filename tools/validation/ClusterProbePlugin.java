/* Copyright 2020-2026 The Billing Project, LLC. Licensed under the Apache License, Version 2.0. */
package org.killbill.billing.plugin.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.killbill.billing.lpr.api.DurableEvent;
import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.util.nodes.KillbillNodesApi;

/** Acceptance-only fixture: no core API calls during start, no replacement platform bindings. */
public final class ClusterProbePlugin implements Plugin {
    private PluginContext context;
    private Path evidence;

    @Override
    public void start(final PluginContext context) throws IOException {
        this.context = context;
        evidence = context.pluginRoot().resolve("evidence");
        Files.createDirectories(evidence);
        context.resources().manage(context.durableEvents().subscribe("work", event -> {
            try { receive(event); }
            catch (final Exception error) { throw new IllegalStateException("Probe delivery failed", error); }
        }));
        context.services().register(Servlet.class, new ProbeServlet(), Map.of("killbill.pluginName", context.pluginId()));
    }

    private String node() {
        return context.getPlatformService(KillbillNodesApi.class).getCurrentNodeInfo().getNodeName();
    }

    private void receive(final DurableEvent event) throws Exception {
        final String node = node();
        if (!event.topic().equals("normal") && !event.topic().equals("crash")) {
            throw new IllegalArgumentException("Unexpected phase " + event.topic());
        }
        if (!event.payload().equals("acceptance:" + event.id())) {
            throw new IllegalArgumentException("Corrupt payload " + event.id());
        }
        commitEffect(event, node);
        Files.writeString(evidence.resolve("entered-" + event.id() + "-" + node), event.payload());
        final String gate = event.topic().equals("normal") ? "release-normal" : "release-" + event.id() + "-" + node;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(evidence.resolve(gate))) {
            if (System.nanoTime() >= deadline) { throw new IllegalStateException("Barrier timeout " + gate); }
            Thread.sleep(20);
        }
    }

    private void commitEffect(final DurableEvent event, final String node) throws Exception {
        final DataSource source = context.getPlatformService(DataSource.class);
        try (final var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (final var effect = connection.prepareStatement("INSERT INTO validation_war_effects(id,payload) VALUES (?,?) ON CONFLICT(id) DO NOTHING");
                 final var delivery = connection.prepareStatement("INSERT INTO validation_war_deliveries(id,node) VALUES (?,?)")) {
                effect.setObject(1, event.id());
                effect.setString(2, event.payload());
                effect.executeUpdate();
                delivery.setObject(1, event.id());
                delivery.setString(2, node);
                delivery.executeUpdate();
                connection.commit();
            } catch (final Exception error) { connection.rollback(); throw error; }
        }
    }

    @Override
    public void stop() { context = null; }

    private final class ProbeServlet extends HttpServlet {
        @Override
        protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            response.getWriter().write("{\"node\":\"" + node() + "\",\"pid\":" + ProcessHandle.current().pid() + "}");
        }

        @Override
        protected void doPost(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
            final UUID id = UUID.fromString(request.getParameter("id"));
            final String phase = request.getParameter("phase");
            if (!"normal".equals(phase) && !"crash".equals(phase)) { response.sendError(400); return; }
            context.durableEvents().publish(context.pluginId(), "work", new DurableEvent(id, phase, "acceptance:" + id));
            response.setContentType("application/json");
            response.getWriter().write("{\"id\":\"" + id + "\"}");
        }
    }
}
