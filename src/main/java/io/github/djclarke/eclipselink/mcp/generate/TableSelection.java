/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp.generate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionException;

/**
 * Which tables a generated definition covers (docs/server-definition.md 7.2). Nothing is scanned wholesale:
 * the selection must name tables or patterns, and metadata is read only for what it selects.
 *
 * <pre>
 * schemas: [SALES]                       # default: the connection's current schema
 * tables: [ORDERS]                       # exact names; seeds for FK expansion
 * include: ["ORDER*", "re:^CUST(OMER)?$"] # glob, or regex with "re:"
 * exclude: ["*_AUDIT", "TMP_*"]          # never selected unless listed in tables
 * types: [TABLE, VIEW]                   # JDBC table types, default TABLE
 * followForeignKeys: { depth: 1, direction: both }   # outgoing | both
 * keys: { ORDER_SUMMARY_V: [ORDER_ID] }  # primary key for tables or views that declare none
 * versions: { LEGACY: REV_NO, AUDIT: none }   # optimistic lock version column per table, or none
 * maxTables: 200
 * </pre>
 */
public record TableSelection(
        String catalog,
        List<String> schemas,
        List<String> tables,
        List<String> include,
        List<String> exclude,
        List<String> types,
        FollowForeignKeys followForeignKeys,
        Map<String, List<String>> keys,
        Integer maxTables,
        Map<String, String> versions) {

    public static final String FILE = "selection.yaml";

    public TableSelection {
        schemas = schemas == null ? List.of() : List.copyOf(schemas);
        tables = tables == null ? List.of() : List.copyOf(tables);
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
        types = types == null || types.isEmpty() ? List.of("TABLE") : List.copyOf(types);
        followForeignKeys = followForeignKeys == null ? new FollowForeignKeys(0, null) : followForeignKeys;
        keys = keys == null ? Map.of() : Map.copyOf(keys);
        maxTables = maxTables == null ? 200 : maxTables;
        versions = versions == null ? Map.of() : Map.copyOf(versions);
        for (String p : include) {
            pattern(p);
        }
        for (String p : exclude) {
            pattern(p);
        }
        if (maxTables < 1) {
            throw new DefinitionException("maxTables must be at least 1");
        }
    }

    public TableSelection(String catalog, List<String> schemas, List<String> tables, List<String> include,
                          List<String> exclude, List<String> types, FollowForeignKeys followForeignKeys,
                          Map<String, List<String>> keys, Integer maxTables) {
        this(catalog, schemas, tables, include, exclude, types, followForeignKeys, keys, maxTables, null);
    }

    /**
     * The version column named for a table in {@code versions}: by plain or qualified name, case-insensitive.
     * Returns {@code "none"} when detection is switched off for it, or null when not named.
     */
    public String versionFor(String schema, String table) {
        for (Map.Entry<String, String> e : versions.entrySet()) {
            String k = e.getKey();
            if (k.equalsIgnoreCase(table) || (schema != null && k.equalsIgnoreCase(schema + "." + table))) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Expand the selection along foreign keys from the seed tables.
     *
     * @param depth hops to follow; 0 means no expansion
     * @param direction {@code outgoing} (tables the seeds reference) or {@code both} (also tables referencing them)
     */
    public record FollowForeignKeys(Integer depth, String direction) {
        public FollowForeignKeys {
            depth = depth == null ? 0 : depth;
            direction = direction == null ? "outgoing" : direction;
            if (depth < 0 || depth > 10) {
                throw new DefinitionException("followForeignKeys.depth must be from 0 to 10");
            }
            if (!direction.equals("outgoing") && !direction.equals("both")) {
                throw new DefinitionException("followForeignKeys.direction must be outgoing or both");
            }
        }

        public boolean incoming() {
            return direction.equals("both");
        }
    }

    public static TableSelection load(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new DefinitionException("Selection file not found: " + file);
        }
        YAMLMapper mapper = YAMLMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        try {
            TableSelection s = mapper.readValue(file.toFile(), TableSelection.class);
            return s == null ? new TableSelection(null, null, null, null, null, null, null, null, null) : s;
        } catch (JacksonException e) {
            throw new DefinitionException(file + ": " + e.getOriginalMessage(), e);
        }
    }

    /** Writes the selection as YAML, so a generated definition records how it was made (re-entrant, FR-32). */
    public String toYaml() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (catalog != null) {
            m.put("catalog", catalog);
        }
        putIfNotEmpty(m, "schemas", schemas);
        putIfNotEmpty(m, "tables", tables);
        putIfNotEmpty(m, "include", include);
        putIfNotEmpty(m, "exclude", exclude);
        m.put("types", types);
        if (followForeignKeys.depth() > 0) {
            m.put("followForeignKeys", Map.of("depth", followForeignKeys.depth(),
                    "direction", followForeignKeys.direction()));
        }
        if (!keys.isEmpty()) {
            m.put("keys", keys);
        }
        if (!versions.isEmpty()) {
            m.put("versions", versions);
        }
        m.put("maxTables", maxTables);
        return YAMLMapper.builder().build().writeValueAsString(m);
    }

    /** Overlays command line values: lists are added to, scalars replaced when given. */
    public TableSelection with(String catalog, List<String> schemas, List<String> tables, List<String> include,
                               List<String> exclude, List<String> types, Integer depth, String direction,
                               Integer maxTables) {
        return new TableSelection(
                catalog != null ? catalog : this.catalog,
                concat(this.schemas, schemas), concat(this.tables, tables), concat(this.include, include),
                concat(this.exclude, exclude),
                types == null || types.isEmpty() ? this.types : types,
                new FollowForeignKeys(depth != null ? depth : followForeignKeys.depth(),
                        direction != null ? direction : followForeignKeys.direction()),
                keys, maxTables != null ? maxTables : this.maxTables, versions);
    }

    /** Is anything selected? An empty selection would mean every table, which is never assumed. */
    public boolean isEmpty() {
        return tables.isEmpty() && include.isEmpty();
    }

    /** A case-insensitive matcher for a glob ({@code ORDER*}, {@code ?}) or a regex ({@code re:...}). */
    public static Pattern pattern(String p) {
        if (p == null || p.isBlank()) {
            throw new DefinitionException("Empty table name pattern");
        }
        if (p.startsWith("re:")) {
            try {
                return Pattern.compile(p.substring(3), Pattern.CASE_INSENSITIVE);
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new DefinitionException("Invalid regex table pattern '" + p + "': " + e.getDescription());
            }
        }
        StringBuilder re = new StringBuilder();
        for (char c : p.toCharArray()) {
            switch (c) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    /** SQL LIKE pattern for a glob, so the database filters names; null for a regex (names are listed). */
    static String likePattern(String p, String escape) {
        if (p.startsWith("re:")) {
            return null;
        }
        StringBuilder like = new StringBuilder();
        for (char c : p.toCharArray()) {
            switch (c) {
                case '*' -> like.append('%');
                case '?' -> like.append('_');
                case '%', '_' -> like.append(escape == null ? "_" : escape + c);
                default -> like.append(c);
            }
        }
        return like.toString();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        if (b == null || b.isEmpty()) {
            return a;
        }
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static void putIfNotEmpty(Map<String, Object> m, String key, List<String> v) {
        if (!v.isEmpty()) {
            m.put(key, v);
        }
    }
}
