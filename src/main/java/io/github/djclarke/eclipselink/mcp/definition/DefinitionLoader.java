/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp.definition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads a server definition directory and validates its structure.
 *
 * <p>String values may use {@code ${env:NAME}}, {@code ${file:PATH}}, references of plugin
 * {@link SecretResolver}s, a {@code :-default} on any of these, and {@code ${definition.dir}}.
 * Substitution happens on parsed values, so an environment value can never change the YAML structure.
 */
public final class DefinitionLoader {

    public static final String DESCRIPTOR_FILE = "eclipselink-mcp.yaml";

    /** {@code ${definition.dir}}, or {@code ${scheme:reference}} with an optional {@code :-default}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(definition\\.dir|([a-z][a-z0-9-]*):([^}]*))}");
    private static final Pattern SECRET_REFERENCE = Pattern.compile("\\$\\{[a-z][a-z0-9-]*:[^}]+}");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,40}");

    private final YAMLMapper mapper = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final SecretResolvers resolvers;

    public DefinitionLoader() {
        this(System::getenv);
    }

    /** For tests: supply environment variables from a function instead of the process environment. */
    public DefinitionLoader(Function<String, String> environment) {
        this(environment, DefinitionLoader.class.getClassLoader());
    }

    /**
     * @param plugins classloader searched for {@link SecretResolver} plugins (META-INF/services)
     */
    public DefinitionLoader(Function<String, String> environment, ClassLoader plugins) {
        this.resolvers = new SecretResolvers(environment, plugins);
    }

    public ServerDefinition load(Path directory) {
        Path dir = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new DefinitionException("Server definition directory not found: " + dir);
        }
        Path descriptorFile = dir.resolve(DESCRIPTOR_FILE);
        if (!Files.isRegularFile(descriptorFile)) {
            throw new DefinitionException("Missing " + DESCRIPTOR_FILE + " in " + dir);
        }

        JsonNode tree = readTree(descriptorFile);
        checkNoLiteralPassword(tree);
        substitute(tree, dir);
        Descriptor descriptor = convert(tree, Descriptor.class, descriptorFile);
        validate(descriptor, dir);

        List<ReportDefinition> reports = loadReports(dir.resolve("reports"));
        List<QueryDefinition> queries = loadQueries(dir.resolve("queries"));
        List<WriteDefinition> writes = loadWrites(dir.resolve("writes"));
        return new ServerDefinition(dir, descriptor, reports, queries, writes);
    }

    private static final Set<String> BUILT_IN_TOOLS = Set.of("list_entities", "describe_entity", "find_entity",
            "query_entities");

    private List<WriteDefinition> loadWrites(Path writesDir) {
        List<WriteDefinition> writes = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Path file : yamlFiles(writesDir)) {
            WriteDefinition write = convert(readTree(file), WriteDefinition.class, file);
            require(write.name() != null && NAME.matcher(write.name()).matches(),
                    file + ": write operation name must match " + NAME.pattern());
            require(!write.name().startsWith("report_") && !write.name().startsWith("query_")
                            && !BUILT_IN_TOOLS.contains(write.name()),
                    file + ": write operation name '" + write.name() + "' clashes with a read tool name");
            require(names.add(write.name()), file + ": duplicate write operation name " + write.name());
            require(write.action() != null && WriteDefinition.ACTIONS.contains(write.action()),
                    file + ": action must be one of " + WriteDefinition.ACTIONS);
            require(write.root() != null, file + ": write operation needs a root entity");
            require(write.locking() == null || "none".equals(write.locking()),
                    file + ": locking can only be 'none' (omit it to use the entity's lock)");
            if ("delete".equals(write.action())) {
                require(write.set().isEmpty() && write.fixed().isEmpty(), file + ": a delete takes no set or fixed");
            } else {
                require(!write.set().isEmpty() || !write.fixed().isEmpty(),
                        file + ": a " + write.action() + " needs attributes under set or fixed");
            }
            require(write.items().isEmpty() || "create".equals(write.action()),
                    file + ": items apply only to a create");
            writes.add(write);
        }
        return writes;
    }

    private List<QueryDefinition> loadQueries(Path queriesDir) {
        List<QueryDefinition> queries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Path file : yamlFiles(queriesDir)) {
            QueryDefinition query = convert(readTree(file), QueryDefinition.class, file);
            require(query.name() != null && NAME.matcher(query.name()).matches(),
                    file + ": query name must match " + NAME.pattern());
            require(!query.name().equals("entities"), file + ": query name 'entities' is reserved (query_entities)");
            require(names.add(query.name()), file + ": duplicate query name " + query.name());
            require(query.root() != null, file + ": query needs a root entity");
            require(!query.graph().isEmpty(), file + ": query needs a graph (the attribute paths to return)");
            queries.add(query);
        }
        return queries;
    }

    private static List<Path> yamlFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).sorted().toList();
        } catch (IOException e) {
            throw new DefinitionException("Cannot list " + dir, e);
        }
    }

    private List<ReportDefinition> loadReports(Path reportsDir) {
        List<ReportDefinition> reports = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Path file : yamlFiles(reportsDir)) {
            ReportDefinition report = convert(readTree(file), ReportDefinition.class, file);
            require(report.name() != null && NAME.matcher(report.name()).matches(),
                    file + ": report name must match " + NAME.pattern());
            require(names.add(report.name()), file + ": duplicate report name " + report.name());
            require(report.root() != null, file + ": report needs a root entity");
            require(!report.select().isEmpty(), file + ": report needs at least one select column");
            for (ReportDefinition.Column c : report.select()) {
                require(c.as() != null, file + ": every select column needs 'as'");
            }
            reports.add(report);
        }
        return reports;
    }

    private void validate(Descriptor d, Path dir) {
        require(Descriptor.API_VERSION.equals(d.apiVersion()),
                "apiVersion must be " + Descriptor.API_VERSION + ", found " + d.apiVersion());
        require(d.server() != null && d.server().name() != null, "server.name is required");
        require(d.connection() != null && d.connection().url() != null, "connection.url is required");
        require(d.model() != null && d.model().mappings() != null, "model.mappings is required");
        Path mappings = dir.resolve(d.model().mappings()).normalize();
        require(Files.isRegularFile(mappings), "model.mappings file not found: " + mappings);
        require(mappings.startsWith(dir), "model.mappings must be inside the definition directory");
        require(d.model().schema() == null,
                "model.schema is not supported yet in this POC; qualify tables in the mappings instead");
        require(d.model().bindings() == null,
                "model.bindings (MOXy overrides) is not supported yet in this POC; remove it");
        require("stdio".equals(d.transport().type()),
                "transport.type '" + d.transport().type() + "' is not supported yet; only stdio");
        Descriptor.Limits l = d.limits();
        require(l.defaultPageSize() > 0 && l.defaultPageSize() <= l.maxPageSize(),
                "limits.defaultPageSize must be between 1 and limits.maxPageSize");
        require(l.maxDepth() >= 0 && l.maxDepth() <= 5, "limits.maxDepth must be between 0 and 5");
    }

    /** A password must be a secret reference (or empty), never a literal in the file. */
    private static void checkNoLiteralPassword(JsonNode tree) {
        JsonNode password = tree.path("connection").path("password");
        if (password.isString()) {
            String raw = password.asString();
            if (!raw.isEmpty() && !SECRET_REFERENCE.matcher(raw.trim()).matches()) {
                throw new DefinitionException("connection.password must be a secret reference such as "
                        + "${env:DB_PASSWORD} or ${file:/run/secrets/db-password}, not a literal");
            }
        }
    }

    private void substitute(JsonNode node, Path dir) {
        if (node instanceof ObjectNode obj) {
            for (Map.Entry<String, JsonNode> e : obj.properties()) {
                if (e.getValue().isString()) {
                    obj.put(e.getKey(), resolve(e.getValue().asString(), dir));
                } else {
                    substitute(e.getValue(), dir);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                if (arr.get(i).isString()) {
                    arr.set(i, arr.stringNode(resolve(arr.get(i).asString(), dir)));
                } else {
                    substitute(arr.get(i), dir);
                }
            }
        }
    }

    public String resolve(String value, Path dir) {
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement;
            if (m.group(1).equals("definition.dir")) {
                replacement = dir.toString().replace('\\', '/');
            } else {
                String scheme = m.group(2);
                String reference = m.group(3);
                String dflt = null;
                int d = reference.indexOf(":-");
                if (d >= 0) {
                    dflt = reference.substring(d + 2);
                    reference = reference.substring(0, d);
                }
                replacement = resolvers.get(scheme).resolve(reference, dir);
                if (replacement == null) {
                    if (dflt == null) {
                        throw new DefinitionException(missing(scheme, reference, dir));
                    }
                    replacement = dflt;
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String missing(String scheme, String reference, Path dir) {
        return switch (scheme) {
            case "env" -> "Environment variable " + reference + " is not set";
            case "file" -> "Secret file not found: " + dir.resolve(reference).normalize();
            default -> "Secret ${" + scheme + ":" + reference + "} not found";
        };
    }

    private JsonNode readTree(Path file) {
        try {
            JsonNode tree = mapper.readTree(file.toFile());
            if (tree == null || !tree.isObject()) {
                throw new DefinitionException(file + ": expected a YAML mapping");
            }
            return tree;
        } catch (JacksonException e) {
            throw new DefinitionException(file + ": " + e.getOriginalMessage(), e);
        }
    }

    private <T> T convert(JsonNode tree, Class<T> type, Path file) {
        try {
            return mapper.treeToValue(tree, type);
        } catch (JacksonException e) {
            throw new DefinitionException(file + ": " + e.getOriginalMessage(), e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new DefinitionException(message);
        }
    }
}
