/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionException;
import io.github.djclarke.eclipselink.mcp.definition.DefinitionLoader;
import io.github.djclarke.eclipselink.mcp.definition.ServerDefinition;
import io.github.djclarke.eclipselink.mcp.generate.InitCommand;
import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.tools.ToolService;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar eclipselink-mcp-server.jar --definition &lt;dir&gt; [--drivers &lt;dir&gt;] [--validate]
 * java -jar eclipselink-mcp-server.jar init --url &lt;jdbc-url&gt; --out &lt;dir&gt; [selection options]
 * </pre>
 *
 * stdout carries MCP messages, so everything else (logs, errors) goes to stderr.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("init")) {
            int code = new InitCommand(System::getenv, System.out).run(java.util.Arrays.copyOfRange(args, 1, args.length));
            if (code != 0) {
                System.exit(code);
            }
            return;
        }
        Path definitionDir = null;
        Path driversDir = null;
        boolean validate = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--definition" -> definitionDir = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--drivers" -> {
                    if (i + 1 >= args.length) {
                        usage(System.err);
                        System.exit(2);
                    }
                    driversDir = Path.of(args[++i]);
                }
                case "--validate" -> validate = true;
                case "--help", "-h" -> {
                    usage(System.out);
                    return;
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    usage(System.err);
                    System.exit(2);
                }
            }
        }
        if (definitionDir == null) {
            usage(System.err);
            System.exit(2);
        }

        ServerDefinition definition;
        ModelRuntime model;
        ToolService tools;
        try {
            definition = new DefinitionLoader().load(definitionDir);
            configureLogging(definition.descriptor().logging().level());
            model = ModelRuntime.start(definition, driversDir);
            tools = new ToolService(model);
        } catch (DefinitionException e) {
            System.err.println("Invalid server definition: " + e.getMessage());
            System.exit(1);
            return;
        }

        if (validate) {
            PrintStream out = System.out;
            out.println("Server definition OK: " + definition.directory());
            String version = definition.descriptor().server().version();
            out.println("  server:   " + definition.descriptor().server().name() + (version == null ? "" : " " + version));
            if (model.drivers().directory() != null) {
                out.println("  drivers:  " + String.join(", ", model.drivers().driverClasses())
                        + " (from " + model.drivers().directory() + ")");
            }
            out.println("  entities: " + String.join(", ", model.entities().keySet()));
            out.println("  reports:  " + String.join(", ",
                    tools.reports().stream().map(r -> r.definition().name()).toList()));
            out.println("  queries:  " + String.join(", ",
                    tools.queries().stream().map(q -> q.definition().name() + " (batch: "
                            + String.join(", ", q.loadGraph().relationships()) + ")").toList()));
            out.println("  tools:    " + String.join(", ", tools.tools().stream().map(ToolService.Tool::name).toList()));
            model.close();
            return;
        }

        McpSyncServer server = McpServerFactory.create(model, tools,
                new StdioServerTransportProvider(McpJsonDefaults.getMapper()));
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.closeGracefully();
            model.close();
            stopped.countDown();
        }, "eclipselink-mcp-shutdown"));
        System.err.println("EclipseLink MCP server '" + definition.descriptor().server().name()
                + "' started on stdio with " + tools.tools().size() + " tools");
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** slf4j-simple writes to stderr; set its level from the descriptor before any logger is created. */
    private static void configureLogging(String level) {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level);
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar eclipselink-mcp-server.jar --definition <dir> [--drivers <dir>] [--validate]");
        out.println("  --definition <dir>  server definition directory (contains eclipselink-mcp.yaml)");
        out.println("  --drivers <dir>     JDBC driver JARs to load (default: connection.drivers, or <definition>/drivers)");
        out.println("  --validate          load and check the definition against the database, then exit");
        out.println("Generate a starter definition from selected tables: ... init --help");
    }
}
