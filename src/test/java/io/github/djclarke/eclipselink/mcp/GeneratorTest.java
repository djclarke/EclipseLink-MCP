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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionException;
import io.github.djclarke.eclipselink.mcp.definition.DefinitionLoader;
import io.github.djclarke.eclipselink.mcp.generate.InitCommand;
import io.github.djclarke.eclipselink.mcp.generate.SchemaModel;
import io.github.djclarke.eclipselink.mcp.generate.SchemaReader;
import io.github.djclarke.eclipselink.mcp.generate.TableSelection;
import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.tools.ToolService;

/** {@code init}: table selection, scoped metadata reads, and a round trip over the sample database. */
class GeneratorTest {

    static final String MEM = "jdbc:h2:mem:generator;DB_CLOSE_DELAY=-1";
    static Connection mem;

    @TempDir
    Path dir;

    @BeforeAll
    static void createSelectionDatabase() throws SQLException {
        mem = DriverManager.getConnection(MEM, "sa", "");
        try (Statement s = mem.createStatement()) {
            s.execute("CREATE TABLE A_PARENT (ID INT PRIMARY KEY, NAME VARCHAR(20))");
            s.execute("COMMENT ON TABLE A_PARENT IS 'A parent row'");
            s.execute("CREATE TABLE A_CHILD (ID INT PRIMARY KEY, PARENT_ID INT REFERENCES A_PARENT(ID), V INT)");
            s.execute("CREATE TABLE A_AUDIT (ID INT PRIMARY KEY, AT TIMESTAMP)");
            s.execute("CREATE TABLE B_OTHER (ID INT PRIMARY KEY)");
            s.execute("CREATE TABLE LINK (A_ID INT REFERENCES A_PARENT(ID), B_ID INT REFERENCES B_OTHER(ID), "
                    + "NOTE VARCHAR(20), PRIMARY KEY (A_ID, B_ID))");
            s.execute("CREATE VIEW V_SUMMARY AS SELECT P.ID, COUNT(C.ID) AS CHILDREN FROM A_PARENT P "
                    + "LEFT JOIN A_CHILD C ON C.PARENT_ID = P.ID GROUP BY P.ID");
            s.execute("CREATE SCHEMA OTHER");
            s.execute("CREATE TABLE OTHER.A_ELSEWHERE (ID INT PRIMARY KEY)");
            s.execute("INSERT INTO A_PARENT VALUES (1, 'p1'), (2, 'p2')");
            s.execute("INSERT INTO A_CHILD VALUES (10, 1, 5), (11, 1, 6), (12, 2, 7)");
            s.execute("INSERT INTO B_OTHER VALUES (100)");
            s.execute("INSERT INTO LINK VALUES (1, 100, 'one'), (2, 100, 'two')");
        }
    }

    @AfterAll
    static void close() throws SQLException {
        mem.close();
    }

    // ---- selection --------------------------------------------------------------------------------------------

    private static Set<String> select(TableSelection selection) throws SQLException {
        SchemaModel model = new SchemaReader(mem, selection).read();
        return model.tables().stream().map(SchemaModel.Table::name).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private static TableSelection sel(List<String> tables, List<String> include, List<String> exclude) {
        return new TableSelection(null, null, tables, include, exclude, null, null, null, null);
    }

    @Test
    void nothingIsScannedWithoutAnExplicitSelection() {
        DefinitionException e = assertThrows(DefinitionException.class, () -> select(sel(null, null, null)));
        assertTrue(e.getMessage().contains("never scans a whole database"), e.getMessage());
    }

    @Test
    void globAndRegexPatternsWithExcludes() throws SQLException {
        assertEquals(Set.of("A_CHILD", "A_PARENT"), select(sel(null, List.of("a_*"), List.of("*_AUDIT"))));
        assertEquals(Set.of("B_OTHER"), select(sel(null, List.of("re:^b_.*"), null)));
        assertEquals(Set.of("A_AUDIT", "A_CHILD", "A_PARENT", "B_OTHER", "LINK"), select(sel(null, List.of("*"), null)),
                "current schema only, tables only (no views)");
    }

    @Test
    void explicitTablesAndForeignKeyExpansion() throws SQLException {
        assertEquals(Set.of("A_CHILD"), select(sel(List.of("a_child"), null, null)));
        TableSelection out1 = sel(List.of("A_CHILD"), null, null).with(null, null, null, null, null, null, 1, null, null);
        assertEquals(Set.of("A_CHILD", "A_PARENT"), select(out1));
        TableSelection both = sel(List.of("A_PARENT"), null, List.of("A_CHILD"))
                .with(null, null, null, null, null, null, 2, "both", null);
        assertEquals(Set.of("A_PARENT", "B_OTHER", "LINK"), select(both), "LINK refers to A_PARENT, then B_OTHER");
        DefinitionException missing = assertThrows(DefinitionException.class, () -> select(sel(List.of("NOPE"), null, null)));
        assertTrue(missing.getMessage().contains("NOPE not found"), missing.getMessage());
    }

    @Test
    void schemaScopingTypesKeysAndLimits() throws SQLException {
        TableSelection other = new TableSelection(null, List.of("other"), null, List.of("*"), null, null, null, null, null);
        assertEquals(Set.of("A_ELSEWHERE"), select(other));

        TableSelection view = new TableSelection(null, null, List.of("V_SUMMARY"), null, null, List.of("VIEW"),
                null, null, null);
        SchemaModel noKey = new SchemaReader(mem, view).read();
        assertTrue(noKey.tables().get(0).primaryKey().isEmpty());
        assertTrue(noKey.warnings().get(0).contains("no primary key"), noKey.warnings().toString());
        TableSelection keyed = new TableSelection(null, null, List.of("V_SUMMARY"), null, null, List.of("VIEW"),
                null, Map.of("V_SUMMARY", List.of("ID")), null);
        assertEquals(List.of("ID"), new SchemaReader(mem, keyed).read().tables().get(0).primaryKey());

        TableSelection tooMany = new TableSelection(null, null, null, List.of("*"), null, null, null, null, 2);
        DefinitionException e = assertThrows(DefinitionException.class, () -> select(tooMany));
        assertTrue(e.getMessage().contains("over maxTables 2"), e.getMessage());
    }

    @Test
    void generatesCompositeKeysViewsAndRemarks() throws Exception {
        Path sel = dir.resolve("sel.yaml");
        Files.writeString(sel, """
                include: ["A_*", "LINK", "B_*", "V_*"]
                exclude: ["*_AUDIT"]
                types: [TABLE, VIEW]
                keys: { V_SUMMARY: [ID] }
                """);
        Path out = dir.resolve("gen");
        assertEquals(0, init("--url", MEM, "--user", "sa", "--out", out.toString(), "--selection", sel.toString()));
        String orm = Files.readString(out.resolve("model/eclipselink-orm.xml"));
        assertTrue(orm.contains("<property name=\"mcp.description\" value=\"A parent row\"/>"), orm);
        assertEquals(TableSelection.load(sel), TableSelection.load(out.resolve(TableSelection.FILE)),
                "the definition records its selection");

        try (ModelRuntime model = ModelRuntime.start(new DefinitionLoader(n -> null).load(out))) {
            assertEquals(Set.of("AChild", "AParent", "BOther", "Link", "VSummary"), model.entities().keySet());
            ToolService tools = new ToolService(model);
            Map<?, ?> link = (Map<?, ?>) tools.call("find_entity", Map.of("entity", "Link",
                    "id", Map.of("aId", 2, "bId", 100), "depth", 1)).get("item");
            assertEquals("two", link.get("note"));
            assertEquals("p2", ((Map<?, ?>) link.get("a")).get("name"));
            List<?> parents = (List<?>) tools.call("query_entities", Map.of("entity", "AParent",
                    "select", List.of("name", "aChilds.v"))).get("items");
            assertEquals(2, ((List<?>) ((Map<?, ?>) parents.get(0)).get("aChilds")).size());
            List<?> summary = (List<?>) tools.call("query_entities", Map.of("entity", "VSummary",
                    "filter", Map.of("path", "id", "op", "eq", "value", 1))).get("items");
            assertEquals(2L, ((Number) ((Map<?, ?>) summary.get(0)).get("children")).longValue());
        }
    }

    @Test
    void versionColumnsNamedInTheSelection() throws Exception {
        Path sel = dir.resolve("versions.yaml");
        Files.writeString(sel, """
                tables: [A_PARENT, A_CHILD]
                versions: { A_CHILD: V }
                """);
        Path out = dir.resolve("versions");
        assertEquals(0, init("--url", MEM, "--user", "sa", "--out", out.toString(), "--selection", sel.toString()));
        String orm = Files.readString(out.resolve("model/eclipselink-orm.xml"));
        assertTrue(orm.contains("<version name=\"v\" attribute-type=\"Integer\"><column name=\"V\"/></version>"), orm);
        assertTrue(Files.readString(out.resolve("README.md")).contains("Optimistic lock versions: AChild.v"));

        Files.writeString(sel, "tables: [A_PARENT, A_CHILD]\nversions: { A_CHILD: NAME_MISSING }\n");
        assertEquals(1, init("--url", MEM, "--user", "sa", "--out", dir.resolve("bad").toString(),
                "--selection", sel.toString()));
        Files.writeString(sel, "tables: [A_PARENT, A_CHILD]\nversions: { A_CHILD: PARENT_ID }\n");
        assertEquals(1, init("--url", MEM, "--user", "sa", "--out", dir.resolve("bad2").toString(),
                "--selection", sel.toString()), "a foreign key column cannot be the version");
    }

    // ---- round trip over the sample database ----------------------------------------------------------------

    @Test
    void roundTripAllTablesOfTheSampleDatabase() throws Exception {
        TestDatabase.environment(); // creates the database
        Path out = dir.resolve("gen-sales");
        int code = init(Map.of("GEN_PW", TestDatabase.READER_PASSWORD), "--url", TestDatabase.URL,
                "--user", TestDatabase.READER_USER, "--password-env", "GEN_PW", "--include", "*",
                "--out", out.toString());
        assertEquals(0, code);
        String descriptor = Files.readString(out.resolve(DefinitionLoader.DESCRIPTOR_FILE));
        assertTrue(descriptor.contains("password: \"${env:GEN_PW}\""), descriptor);
        assertFalse(descriptor.contains(TestDatabase.READER_PASSWORD + "\""), "no password in the descriptor");

        ModelRuntime generated = ModelRuntime.start(new DefinitionLoader(Map.of("GEN_PW", TestDatabase.READER_PASSWORD)::get).load(out));
        ModelRuntime handWritten = ModelRuntime.start(TestDatabase.sampleDefinition());
        try (generated; handWritten) {
            assertEquals(Set.of("Customer", "OrderLine", "Orders", "Product"), generated.entities().keySet());
            ToolService gen = new ToolService(generated);
            ToolService hand = new ToolService(handWritten);

            Map<String, Object> filter = Map.of("path", "customer.region", "op", "eq", "value", "EAST");
            assertEquals(hand.call("query_entities", Map.of("entity", "Order", "filter", filter)).get("items"),
                    gen.call("query_entities", Map.of("entity", "Orders", "filter", filter)).get("items"));
            assertEquals(hand.call("find_entity", Map.of("entity", "Order", "id", 1005)).get("item"),
                    gen.call("find_entity", Map.of("entity", "Orders", "id", 1005)).get("item"));
            assertEquals(
                    hand.call("query_entities", Map.of("entity", "Customer", "select", List.of("name", "orders.total")))
                            .get("items"),
                    gen.call("query_entities", Map.of("entity", "Customer", "select", List.of("name", "orders.total")))
                            .get("items"));
            Map<?, ?> line = (Map<?, ?>) gen.call("find_entity", Map.of("entity", "OrderLine", "id", 4,
                    "select", List.of("quantity", "order.status", "product.name"))).get("item");
            assertEquals("Support Plan", ((Map<?, ?>) line.get("product")).get("name"));
            Map<?, ?> orders = ((List<?>) gen.call("list_entities", Map.of()).get("entities")).stream()
                    .map(Map.class::cast).filter(e -> e.get("name").equals("Orders")).findFirst().orElseThrow();
            assertEquals(List.of("customer", "orderLines"), orders.get("relationships"));
        }
    }

    private static int init(String... args) {
        return init(Map.of(), args);
    }

    private static int init(Map<String, String> env, String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = InitCommand.run(args, env, new PrintStream(buf, true, StandardCharsets.UTF_8));
        System.err.print(buf.toString(StandardCharsets.UTF_8));
        return code;
    }
}
