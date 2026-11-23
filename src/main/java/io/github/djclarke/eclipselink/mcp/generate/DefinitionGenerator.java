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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionException;
import io.github.djclarke.eclipselink.mcp.definition.DefinitionLoader;

/**
 * Writes a starter server definition from a {@link SchemaModel} (docs/server-definition.md 7.4): the descriptor,
 * {@code model/eclipselink-orm.xml} with one VIRTUAL entity per table and relationships from foreign keys, the
 * {@code selection.yaml} it came from, and a README. JSON uses the default bindings, so no OXM file is written.
 * The output is a starting point for the design-time session, which renames and trims.
 */
public final class DefinitionGenerator {

    /**
     * @param name server name ({@code server.name}); also the package of the dynamic entity classes
     * @param url JDBC URL, written as the default of an environment reference
     * @param user database user, written as the default of an environment reference
     * @param passwordEnv environment variable holding the password, or null for none
     */
    public record Options(String name, String url, String user, String passwordEnv) {
    }

    /** What was written, for the command's summary. */
    public record Result(Path directory, Map<String, String> entities, List<String> relationships,
                         List<String> warnings) {
    }

    private final SchemaModel schema;
    private final TableSelection selection;
    private final Options options;
    private final List<String> warnings = new ArrayList<>();
    private final List<String> versionNotes = new ArrayList<>();

    public DefinitionGenerator(SchemaModel schema, TableSelection selection, Options options) {
        this.schema = schema;
        this.selection = selection;
        this.options = options;
        warnings.addAll(schema.warnings());
    }

    public Result write(Path dir, boolean overwrite) {
        try {
            if (Files.exists(dir) && !overwrite) {
                try (Stream<Path> s = Files.list(dir)) {
                    if (s.findAny().isPresent()) {
                        throw new DefinitionException(dir + " is not empty; choose another --out or pass --force");
                    }
                }
            }
            Files.createDirectories(dir.resolve("model"));
            Mapping mapping = map();
            Files.writeString(dir.resolve("model/eclipselink-orm.xml"), mapping.ormXml());
            Files.writeString(dir.resolve(DefinitionLoader.DESCRIPTOR_FILE), descriptor());
            Files.writeString(dir.resolve(TableSelection.FILE), "# Tables this definition was generated from. "
                    + "Re-run init with --selection " + TableSelection.FILE + " to regenerate.\n" + selection.toYaml());
            Files.writeString(dir.resolve("README.md"), readme(mapping));
            return new Result(dir, mapping.entityTables(), mapping.relationships(), List.copyOf(warnings));
        } catch (IOException e) {
            throw new DefinitionException("Cannot write " + dir + ": " + e.getMessage(), e);
        }
    }

    // ---- mapping ---------------------------------------------------------------------------------------------

    private record Attr(String name, String xml) {
    }

    private record Mapping(String ormXml, Map<String, String> entityTables, List<String> relationships) {
    }

    private record EntityPlan(SchemaModel.Table table, String entity, Set<String> names, List<Attr> attrs) {
    }

    private Mapping map() {
        String pkg = javaPackage(options.name());
        Map<String, EntityPlan> byTable = new LinkedHashMap<>();
        Set<String> entityNames = new HashSet<>();
        for (SchemaModel.Table t : schema.tables()) {
            if (t.primaryKey().isEmpty()) {
                continue; // warned by the reader
            }
            String entity = unique(upperCamel(t.name()), entityNames, t.schema() == null ? "2" : upperCamel(t.schema()));
            byTable.put(t.qualifiedName(), new EntityPlan(t, entity, new HashSet<>(), new ArrayList<>()));
        }

        // Relationships first, so foreign key columns that become relationships are not also basic attributes.
        Map<String, Set<String>> fkColumnsUsed = new LinkedHashMap<>();
        List<String> relationships = new ArrayList<>();
        List<String[]> inverse = new ArrayList<>(); // target table, source entity, mapped-by, xml
        Map<String, List<Attr>> relationAttrs = new LinkedHashMap<>();
        for (EntityPlan p : byTable.values()) {
            for (SchemaModel.ForeignKey fk : p.table().foreignKeys()) {
                EntityPlan target = byTable.get(fk.targetSchema() == null ? fk.targetTable()
                        : fk.targetSchema() + "." + fk.targetTable());
                if (target == null || !new HashSet<>(fk.targetColumns()).equals(new HashSet<>(target.table().primaryKey()))) {
                    warnings.add(p.table().qualifiedName() + " foreign key " + fk.columns() + " -> " + fk.targetTable()
                            + " is not to a selected table's primary key; its columns stay basic attributes");
                    continue;
                }
                boolean overlapsKey = fk.columns().stream().anyMatch(p.table().primaryKey()::contains);
                String attr = relationName(fk, target.entity());
                attr = unique(attr, p.names(), "Ref");
                StringBuilder xml = new StringBuilder();
                xml.append("            <many-to-one name=\"").append(attr).append("\" target-entity=\"")
                        .append(pkg).append('.').append(target.entity()).append("\" fetch=\"LAZY\">\n");
                for (int i = 0; i < fk.columns().size(); i++) {
                    xml.append("                <join-column name=\"").append(esc(fk.columns().get(i)))
                            .append("\" referenced-column-name=\"").append(esc(fk.targetColumns().get(i))).append('"')
                            .append(overlapsKey ? " insertable=\"false\" updatable=\"false\"" : "").append("/>\n");
                }
                xml.append("            </many-to-one>\n");
                relationAttrs.computeIfAbsent(p.table().qualifiedName(), k -> new ArrayList<>()).add(new Attr(attr, xml.toString()));
                if (!overlapsKey) {
                    fkColumnsUsed.computeIfAbsent(p.table().qualifiedName(), k -> new HashSet<>()).addAll(fk.columns());
                }
                relationships.add(p.entity() + "." + attr + " -> " + target.entity());
                inverse.add(new String[] {target.table().qualifiedName(), p.entity(), attr});
            }
        }

        StringBuilder orm = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- Generated by eclipselink-mcp init from the tables in selection.yaml. Review and edit:
                     rename entities and attributes, remove what agents do not need, add mcp.description. -->
                <entity-mappings xmlns="http://www.eclipse.org/eclipselink/xsds/persistence/orm"
                                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                 xsi:schemaLocation="http://www.eclipse.org/eclipselink/xsds/persistence/orm http://www.eclipse.org/eclipselink/xsds/eclipselink_orm_5_0.xsd"
                                 version="5.0">
                """);
        orm.append("    <package>").append(pkg).append("</package>\n");
        Map<String, String> entityTables = new LinkedHashMap<>();
        boolean qualify = !selection.schemas().isEmpty();
        for (EntityPlan p : byTable.values()) {
            SchemaModel.Table t = p.table();
            entityTables.put(p.entity(), t.qualifiedName());
            orm.append("\n    <entity class=\"").append(p.entity()).append("\" access=\"VIRTUAL\">\n");
            orm.append("        <table name=\"").append(esc(t.name())).append('"');
            if (qualify && t.schema() != null) {
                orm.append(" schema=\"").append(esc(t.schema())).append('"');
            }
            orm.append("/>\n");
            if (t.remarks() != null) {
                orm.append("        <property name=\"mcp.description\" value=\"").append(esc(t.remarks())).append("\"/>\n");
            }
            orm.append("        <attributes>\n");
            Set<String> skip = fkColumnsUsed.getOrDefault(t.qualifiedName(), Set.of());
            List<Attr> ids = new ArrayList<>();
            List<Attr> basics = new ArrayList<>();
            List<Attr> versions = new ArrayList<>();
            String versionColumn = versionColumn(t, skip);
            // Relationship names were reserved first; basic attributes take what is left.
            Set<String> taken = new HashSet<>(p.names());
            for (SchemaModel.Column c : t.columns()) {
                if (skip.contains(c.name())) {
                    continue;
                }
                String type = javaType(c);
                if (type == null) {
                    warnings.add(t.qualifiedName() + "." + c.name() + " has unsupported type " + c.typeName() + "; skipped");
                    if (t.primaryKey().contains(c.name())) {
                        throw new DefinitionException(t.qualifiedName() + " key column " + c.name()
                                + " has unsupported type " + c.typeName());
                    }
                    continue;
                }
                boolean key = t.primaryKey().contains(c.name());
                boolean version = c.name().equals(versionColumn);
                String element = key ? "id" : version ? "version" : "basic";
                String name = unique(lowerCamel(c.name()), taken, "Value");
                StringBuilder xml = new StringBuilder("            <").append(element)
                        .append(" name=\"").append(name).append("\" attribute-type=\"").append(type).append("\">");
                xml.append("<column name=\"").append(esc(c.name())).append('"');
                if (type.equals("java.math.BigDecimal") && c.size() > 0) {
                    xml.append(" precision=\"").append(c.size()).append("\" scale=\"").append(c.scale()).append('"');
                }
                xml.append("/>");
                if (key && c.autoIncrement() && t.primaryKey().size() == 1) {
                    xml.append("<generated-value strategy=\"IDENTITY\"/>");
                }
                if (c.remarks() != null) {
                    xml.append("\n                <property name=\"mcp.description\" value=\"").append(esc(c.remarks()))
                            .append("\"/>\n            ");
                }
                xml.append("</").append(element).append(">\n");
                (key ? ids : version ? versions : basics).add(new Attr(name, xml.toString()));
            }
            ids.forEach(a -> orm.append(a.xml()));
            basics.forEach(a -> orm.append(a.xml()));
            versions.forEach(a -> orm.append(a.xml()));
            versions.forEach(a -> versionNotes.add(p.entity() + "." + a.name() + " (" + t.qualifiedName() + "."
                    + versionColumn + ")"));
            relationAttrs.getOrDefault(t.qualifiedName(), List.of()).forEach(a -> orm.append(a.xml()));
            for (String[] inv : inverse) {
                if (!inv[0].equals(t.qualifiedName())) {
                    continue;
                }
                long sameSource = inverse.stream().filter(o -> o[0].equals(inv[0]) && o[1].equals(inv[1])).count();
                String base = lowerCamel(plural(inv[1]));
                String name = unique(sameSource > 1 ? base + "By" + capitalize(inv[2]) : base, taken, "List");
                orm.append("            <one-to-many name=\"").append(name).append("\" target-entity=\"").append(pkg)
                        .append('.').append(inv[1]).append("\" mapped-by=\"").append(inv[2])
                        .append("\" attribute-type=\"java.util.List\"/>\n");
                relationships.add(p.entity() + "." + name + " -> " + inv[1] + " (many)");
            }
            orm.append("        </attributes>\n    </entity>\n");
        }
        orm.append("</entity-mappings>\n");
        if (byTable.isEmpty()) {
            throw new DefinitionException("None of the selected tables has a primary key; give keys in the selection");
        }
        return new Mapping(orm.toString(), entityTables, relationships);
    }

    /** Column names recognized as an optimistic lock version (spec 7.4), compared case-insensitively. */
    static final List<String> VERSION_NAMES = List.of("VERSION", "ROW_VERSION", "OPT_LOCK", "OPTLOCK",
            "LOCK_VERSION", "OBJ_VERSION");

    /**
     * The table's optimistic lock version column: the one named in the selection's {@code versions}, or a
     * recognized name on an integer column outside the key and foreign keys. Null for none.
     */
    private String versionColumn(SchemaModel.Table t, Set<String> relationshipColumns) {
        String named = selection.versionFor(t.schema(), t.name());
        if ("none".equalsIgnoreCase(named)) {
            return null;
        }
        Set<String> fkColumns = new HashSet<>(relationshipColumns);
        t.foreignKeys().forEach(fk -> fkColumns.addAll(fk.columns()));
        if (named != null) {
            SchemaModel.Column c = t.columns().stream().filter(x -> x.name().equalsIgnoreCase(named)).findFirst()
                    .orElseThrow(() -> new DefinitionException("versions: " + t.qualifiedName() + " has no column "
                            + named));
            if (!isInteger(c) || t.primaryKey().contains(c.name()) || fkColumns.contains(c.name())) {
                throw new DefinitionException("versions: " + t.qualifiedName() + "." + c.name()
                        + " must be an integer column outside the key and foreign keys");
            }
            return c.name();
        }
        for (SchemaModel.Column c : t.columns()) {
            if (VERSION_NAMES.contains(c.name().toUpperCase(Locale.ROOT)) && isInteger(c)
                    && !t.primaryKey().contains(c.name()) && !fkColumns.contains(c.name())) {
                return c.name();
            }
        }
        return null;
    }

    private static boolean isInteger(SchemaModel.Column c) {
        String type = javaType(c);
        return "Integer".equals(type) || "Long".equals(type);
    }

    private static String relationName(SchemaModel.ForeignKey fk, String targetEntity) {
        if (fk.columns().size() == 1) {
            String c = fk.columns().get(0);
            String lower = c.toLowerCase(Locale.ROOT);
            for (String suffix : List.of("_id", "_key", "_fk", "id")) {
                if (lower.endsWith(suffix) && lower.length() > suffix.length()) {
                    return lowerCamel(c.substring(0, c.length() - suffix.length()));
                }
            }
        }
        return lowerCamel(targetEntity);
    }

    /** Java type for a column, or null when unsupported. */
    static String javaType(SchemaModel.Column c) {
        return switch (c.jdbcType()) {
            case Types.BIT, Types.BOOLEAN -> "Boolean";
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> "Integer";
            case Types.BIGINT -> "Long";
            case Types.REAL -> "Float";
            case Types.FLOAT, Types.DOUBLE -> "Double";
            case Types.NUMERIC, Types.DECIMAL -> c.scale() > 0 ? "java.math.BigDecimal"
                    : c.size() > 0 && c.size() <= 9 ? "Integer"
                    : c.size() > 0 && c.size() <= 18 ? "Long" : "java.math.BigDecimal";
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                    Types.CLOB, Types.NCLOB -> "String";
            case Types.DATE -> "java.time.LocalDate";
            case Types.TIME -> "java.time.LocalTime";
            case Types.TIMESTAMP -> "java.time.LocalDateTime";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "java.time.OffsetDateTime";
            case Types.TIME_WITH_TIMEZONE -> "java.time.OffsetTime";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "byte[]";
            default -> null;
        };
    }

    // ---- descriptor and README -----------------------------------------------------------------------------

    private String envPrefix() {
        return options.name().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private String descriptor() {
        String prefix = envPrefix();
        StringBuilder y = new StringBuilder("apiVersion: eclipselink-mcp/v1\n");
        y.append("server:\n  name: ").append(options.name()).append("\n  version: 0.1.0\n");
        y.append("connection:\n");
        y.append("  url: ").append(envWithDefault(prefix + "_DB_URL", options.url())).append('\n');
        y.append("  user: ").append(envWithDefault(prefix + "_DB_USER", options.user())).append('\n');
        y.append("  password: \"").append(options.passwordEnv() == null ? "${env:" + prefix + "_DB_PASSWORD:-}"
                : "${env:" + options.passwordEnv() + "}").append("\"\n");
        y.append("model:\n  mappings: model/eclipselink-orm.xml\n");
        return y.toString();
    }

    /** {@code ${env:NAME:-default}}, without the default when it could leak a secret or break the syntax. */
    private String envWithDefault(String env, String value) {
        if (value == null) {
            return "\"${env:" + env + ":-}\"";
        }
        if (value.toLowerCase(Locale.ROOT).contains("password") || value.contains("}") || value.contains("\"")) {
            warnings.add("The JDBC URL or user was not written into the descriptor; set " + env);
            return "\"${env:" + env + "}\"";
        }
        return "\"${env:" + env + ":-" + value + "}\"";
    }

    private String readme(Mapping m) {
        String prefix = envPrefix();
        StringBuilder r = new StringBuilder("# ").append(options.name()).append("\n\n");
        r.append("Generated by `eclipselink-mcp init` from ").append(m.entityTables().size())
                .append(" tables (see `selection.yaml`). Review `model/eclipselink-orm.xml` before use.\n\n");
        r.append("| Entity | Table |\n|---|---|\n");
        m.entityTables().forEach((e, t) -> r.append("| ").append(e).append(" | ").append(t).append(" |\n"));
        if (!m.relationships().isEmpty()) {
            r.append("\nRelationships from foreign keys:\n\n");
            m.relationships().forEach(rel -> r.append("- ").append(rel).append('\n'));
        }
        r.append("\nOptimistic lock versions: ").append(versionNotes.isEmpty() ? "none found"
                : String.join(", ", versionNotes)).append(". The server is read-only; write operations need a "
                + "version on the entity they change, or a lock through a relationship (spec 4.9).\n");
        r.append("\nEnvironment: `").append(prefix).append("_DB_URL` and `").append(prefix)
                .append("_DB_USER` override the defaults in the descriptor. The password comes from `")
                .append(options.passwordEnv() == null ? prefix + "_DB_PASSWORD" : options.passwordEnv())
                .append("`.\n");
        if (!warnings.isEmpty()) {
            r.append("\nWarnings:\n\n");
            warnings.forEach(w -> r.append("- ").append(w).append('\n'));
        }
        return r.toString();
    }

    // ---- names ---------------------------------------------------------------------------------------------

    static String upperCamel(String dbName) {
        return capitalize(lowerCamel(dbName));
    }

    /** {@code ORDER_DATE} and {@code order-date} become {@code orderDate}; {@code orderDate} stays. */
    static String lowerCamel(String dbName) {
        String[] parts = dbName.split("[^A-Za-z0-9]+");
        boolean mixed = !dbName.equals(dbName.toUpperCase(Locale.ROOT)) && !dbName.equals(dbName.toLowerCase(Locale.ROOT));
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            String p = mixed ? part : part.toLowerCase(Locale.ROOT);
            sb.append(sb.isEmpty() ? Character.toLowerCase(p.charAt(0)) + p.substring(1) : capitalize(p));
        }
        String s = sb.isEmpty() ? "x" : sb.toString();
        return Character.isJavaIdentifierStart(s.charAt(0)) ? s : "_" + s;
    }

    static String plural(String entity) {
        String lower = entity.toLowerCase(Locale.ROOT);
        if (lower.endsWith("s") || lower.endsWith("x")) {
            return entity;
        }
        if (lower.endsWith("y") && lower.length() > 1 && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
            return entity.substring(0, entity.length() - 1) + "ies";
        }
        return entity + "s";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** {@code name}, or {@code name + suffix}, {@code name + suffix + 2}, ... whichever is free; reserves it. */
    private static String unique(String name, Set<String> taken, String suffix) {
        String n = name;
        for (int i = 1; !taken.add(n); i++) {
            n = name + suffix + (i == 1 ? "" : String.valueOf(i));
        }
        return n;
    }

    private static String javaPackage(String name) {
        String p = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return p.isEmpty() || !Character.isJavaIdentifierStart(p.charAt(0)) ? "model" : p;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
