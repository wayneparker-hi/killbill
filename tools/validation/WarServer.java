/*
 * Copyright 2020-2026 The Billing Project, LLC
 * Licensed under the Apache License, Version 2.0.
 */

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.webapp.WebAppContext;

/** Local acceptance container: deploy the built WAR and its production web.xml. */
public final class WarServer {
    public static void main(final String[] args) throws Exception {
        final Server server = new Server();
        server.setStopAtShutdown(true);
        server.setStopTimeout(10_000);
        final ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        final WebAppContext application = new WebAppContext();
        application.setContextPath("/");
        application.setWar(args[0]);
        application.setTempDirectory(Path.of(args[2]).toFile());
        application.setThrowUnavailableOnStartupException(true);
        server.setHandler(application);
        try {
            server.start();
            if (!application.isAvailable()) { throw new IllegalStateException("WAR failed to start"); }
            Files.writeString(Path.of(args[1]), Integer.toString(connector.getLocalPort()));
            server.join();
        } finally { server.stop(); }
    }
}
