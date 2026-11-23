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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.persistence.descriptors.ClassDescriptor;

import io.github.djclarke.eclipselink.mcp.definition.Descriptor;
import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.query.NamedQuery;
import io.github.djclarke.eclipselink.mcp.query.NamedReport;
import io.github.djclarke.eclipselink.mcp.tools.Json;
import io.github.djclarke.eclipselink.mcp.tools.ToolException;
import io.github.djclarke.eclipselink.mcp.tools.ToolService;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

/**
 * Wires a server definition's model and tools into an MCP Java SDK server: server info, generated
 * instructions, tools and resources (docs/server-definition.md section 4).
 */
public final class McpServerFactory {

    public static final String MODEL_URI = "eclipselink://model";

    private McpServerFactory() {
    }

    public static McpSyncServer create(ModelRuntime model, ToolService tools, McpServerTransportProvider transport) {
        Descriptor.Server info = model.definition().descriptor().server();

        List<McpServerFeatures.SyncToolSpecification> toolSpecs = new ArrayList<>();
        McpSchema.ToolAnnotations readOnly = new McpSchema.ToolAnnotations(null, true, false, true, false, null);
        for (ToolService.Tool t : tools.tools()) {
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(t.name())
                    .title(t.title())
                    .description(t.description())
                    .inputSchema(t.inputSchema())
                    .annotations(readOnly)
                    .build();
            toolSpecs.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler((exchange, request) -> call(tools, request))
                    .build());
        }

        List<McpServerFeatures.SyncResourceSpecification> resources = new ArrayList<>();
        resources.add(resource(MODEL_URI, "model", "Data model overview", "text/markdown",
                "Entities, relationships and reports available on this server", () -> overview(model, tools)));
        for (ClassDescriptor d : model.entities().values()) {
            resources.add(resource("eclipselink://entity/" + d.getAlias(), "entity-" + d.getAlias(),
                    d.getAlias(), "application/json", "Description and JSON Schema of " + d.getAlias(),
                    () -> Json.writePretty(tools.describe(d))));
        }
        for (NamedReport r : tools.reports()) {
            resources.add(resource("eclipselink://report/" + r.definition().name(), "report-" + r.definition().name(),
                    r.definition().title(), "application/json", "Parameters and columns of " + r.definition().title(),
                    () -> Json.writePretty(Map.of(
                            "name", r.definition().name(),
                            "tool", r.definition().toolName(),
                            "description", String.valueOf(r.definition().description()),
                            "inputSchema", r.inputSchema(model.definition().descriptor().limits().maxPageSize()),
                            "columns", r.columns().stream().map(c -> Map.of("name", c.name(), "schema", c.schema()))
                                    .toList()))));
        }

        for (NamedQuery q : tools.queries()) {
            String title = q.definition().title() == null ? q.definition().name() : q.definition().title();
            resources.add(resource("eclipselink://query/" + q.definition().name(), "query-" + q.definition().name(),
                    title, "application/json", "Parameters and graph of " + title,
                    () -> Json.writePretty(Map.of(
                            "name", q.definition().name(),
                            "tool", q.definition().toolName(),
                            "description", String.valueOf(q.definition().description()),
                            "root", q.root().getAlias(),
                            "inputSchema", q.inputSchema(model.definition().descriptor().limits().maxPageSize()),
                            "graph", q.definition().graph()))));
        }

        return McpServer.sync(transport)
                .serverInfo(new McpSchema.Implementation(info.name(), info.title(), versionOf(info)))
                .instructions(instructions(model, tools))
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .resources(false, false)
                        .logging()
                        .build())
                .tools(toolSpecs)
                .resources(resources)
                .build();
    }

    static McpSchema.CallToolResult call(ToolService tools, McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> result = tools.call(request.name(), request.arguments());
            return McpSchema.CallToolResult.builder()
                    .addTextContent(Json.write(result))
                    .structuredContent(result)
                    .build();
        } catch (ToolException e) {
            Map<String, Object> error = Map.of("error", Map.of("code", e.code().name(), "message", e.getMessage()));
            return McpSchema.CallToolResult.builder()
                    .addTextContent(e.code() + ": " + e.getMessage())
                    .structuredContent(error)
                    .isError(true)
                    .build();
        }
    }

    /** Server instructions: the definition's own text, then a generated summary (spec 4.1). */
    static String instructions(ModelRuntime model, ToolService tools) {
        Descriptor.Server info = model.definition().descriptor().server();
        StringBuilder sb = new StringBuilder();
        if (info.instructions() != null) {
            sb.append(info.instructions().strip()).append("\n\n");
        }
        sb.append("This is a read-only server over a relational database, exposed as entities.\n");
        sb.append("Entities: ");
        List<String> names = new ArrayList<>();
        for (ClassDescriptor d : model.entities().values()) {
            String desc = ModelRuntime.description(d);
            names.add(d.getAlias() + (desc == null ? "" : " (" + desc + ")"));
        }
        sb.append(String.join(", ", names)).append(".\n");
        List<String> reports = tools.reports().stream().map(r -> r.definition().toolName()).toList();
        if (!reports.isEmpty()) {
            sb.append("Report tools: ").append(String.join(", ", reports))
                    .append(". Prefer these for totals and summaries.\n");
        }
        List<String> queries = tools.queries().stream().map(q -> q.definition().toolName()).toList();
        if (!queries.isEmpty()) {
            sb.append("Named query tools: ").append(String.join(", ", queries))
                    .append(". Prefer these when one fits: they return related data in one call.\n");
        }
        sb.append("Use list_entities and describe_entity to learn the model, query_entities with a structured "
                + "filter to find rows, and page with nextCursor. Results only include what the server's database "
                + "account can read.");
        return sb.toString();
    }

    private static String overview(ModelRuntime model, ToolService tools) {
        StringBuilder sb = new StringBuilder("# ").append(model.definition().descriptor().server().title() == null
                ? model.definition().descriptor().server().name() : model.definition().descriptor().server().title())
                .append("\n\n## Entities\n\n");
        for (ClassDescriptor d : model.entities().values()) {
            Map<String, Object> desc = tools.describe(d);
            sb.append("### ").append(d.getAlias()).append("\n\n");
            if (desc.containsKey("description")) {
                sb.append(desc.get("description")).append("\n\n");
            }
            for (Object a : (List<?>) desc.get("attributes")) {
                Map<?, ?> attr = (Map<?, ?>) a;
                sb.append("- `").append(attr.get("name")).append("` ").append(attr.get("type"))
                        .append(Boolean.TRUE.equals(attr.get("key")) ? " (key)" : "")
                        .append(attr.containsKey("description") ? ": " + attr.get("description") : "").append('\n');
            }
            for (Object r : (List<?>) desc.get("relationships")) {
                Map<?, ?> rel = (Map<?, ?>) r;
                sb.append("- `").append(rel.get("name")).append("` -> ").append(rel.get("target"))
                        .append(" (").append(rel.get("cardinality")).append(")\n");
            }
            sb.append('\n');
        }
        if (!tools.reports().isEmpty()) {
            sb.append("## Reports\n\n");
            for (NamedReport r : tools.reports()) {
                sb.append("- `").append(r.definition().toolName()).append("`: ")
                        .append(String.valueOf(r.definition().description())).append('\n');
            }
        }
        if (!tools.queries().isEmpty()) {
            sb.append("\n## Named queries\n\n");
            for (NamedQuery q : tools.queries()) {
                sb.append("- `").append(q.definition().toolName()).append("`: ")
                        .append(String.valueOf(q.definition().description())).append(" Graph: ")
                        .append(String.join(", ", q.definition().graph())).append('\n');
            }
        }
        return sb.toString();
    }

    private static McpServerFeatures.SyncResourceSpecification resource(String uri, String name, String title,
            String mimeType, String description, java.util.function.Supplier<String> content) {
        McpSchema.Resource resource = McpSchema.Resource.builder()
                .uri(uri).name(name).title(title).description(description).mimeType(mimeType).build();
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) ->
                new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents(uri, mimeType, content.get()))));
    }

    private static String versionOf(Descriptor.Server info) {
        return info.version() == null ? "0.0.0" : info.version();
    }
}
