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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionLoader;
import io.github.djclarke.eclipselink.mcp.edit.EditCommand;
import io.github.djclarke.eclipselink.mcp.generate.InitCommand;
import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.tools.ToolService;

/**
 * Validated model edits and tool previews (docs/server-definition.md 7.5) on a definition generated from the
 * sample database: the simplification steps the design-time skill takes after {@code init}.
 */
class EditCommandTest {

    static final Map<String, String> ENV = Map.of("GEN_PW", TestDatabase.READER_PASSWORD);

    @TempDir
    Path tmp;
    Path dir;
    Path orm;
    String out;
    String err;

    @BeforeEach
    void generate() {
        TestDatabase.environment();
        dir = tmp.resolve("sales");
        assertEquals(0, InitCommand.run(new String[] {"--url", TestDatabase.URL, "--user", TestDatabase.READER_USER,
                "--password-env", "GEN_PW", "--include", "*", "--out", dir.toString()}, ENV,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        orm = dir.resolve("model/eclipselink-orm.xml");
    }

    private int edit(String... args) {
        String[] all = new String[args.length + 2];
        all[0] = "--definition";
        all[1] = dir.toString();
        System.arraycopy(args, 0, all, 2, args.length);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = new EditCommand(ENV::get, new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8)).run(all);
        out = o.toString(StandardCharsets.UTF_8);
        err = e.toString(StandardCharsets.UTF_8);
        return code;
    }

    private int call(String tool, String json) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int code = new CallCommand(ENV::get, new PrintStream(o, true, StandardCharsets.UTF_8), System.err)
                .run(new String[] {"--definition", dir.toString(), tool, json});
        out = o.toString(StandardCharsets.UTF_8);
        return code;
    }

    private Map<String, Object> describe(String entity) {
        try (ModelRuntime m = ModelRuntime.start(new DefinitionLoader(ENV::get).load(dir))) {
            return new ToolService(m).describe(m.entity(entity));
        }
    }

    private static List<?> relationships(Map<String, Object> d) {
        return ((List<?>) d.get("relationships")).stream().map(r -> ((Map<?, ?>) r).get("name")).toList();
    }

    @Test
    void renameEntityUpdatesTargetsAndQueryRoots() throws Exception {
        Files.createDirectories(dir.resolve("queries"));
        Files.writeString(dir.resolve("queries/recent.yaml"), """
                name: recent
                root: Orders   # the generated name
                graph: [total, customer.name]
                """);
        assertEquals(0, edit("rename-entity", "Orders", "Order"), err);
        assertTrue(out.contains("Renamed entity Orders to Order (2 relationship targets updated)"), out);
        assertTrue(out.contains("Updated root in queries/recent.yaml"), out);
        assertTrue(Files.readString(dir.resolve("queries/recent.yaml")).contains("root: Order   # the generated name"));
        assertEquals(0, call("query_recent", "{\"pageSize\": 1}"));
        assertTrue(out.contains("\"entity\" : \"Order\""), out);
    }

    @Test
    void renameOwningRelationshipUpdatesMappedBy() {
        assertEquals(0, edit("rename-attribute", "Orders", "orderLines", "lines"), err);
        assertEquals(0, edit("rename-attribute", "OrderLine", "order", "parentOrder"), err);
        assertTrue(out.contains("(mapped-by updated on Orders.lines)"), out);
        assertEquals(List.of("customer", "lines"), relationships(describe("Orders")));
        assertEquals(0, call("query_entities", "{\"entity\":\"Orders\",\"select\":[\"lines.quantity\"],\"pageSize\":1}"));
    }

    @Test
    void dropAReverseOneToManyThatIsNotNeeded() {
        // Doug's example: keep OrderLine.product, drop Product.orderLines.
        assertEquals(1, edit("remove-attribute", "OrderLine", "product"));
        assertTrue(err.contains("Product.orderLines is the inverse of OrderLine.product"), err);
        assertEquals(0, edit("remove-attribute", "Product", "orderLines"), err);
        assertEquals(List.of(), relationships(describe("Product")));
        assertEquals(List.of("order", "product"), relationships(describe("OrderLine")));
    }

    @Test
    void trimColumnsButNotKeys() {
        assertEquals(0, edit("remove-attribute", "Customer", "email", "region"), err);
        assertEquals(Set.of("id", "name"), Set.copyOf(((List<?>) describe("Customer").get("attributes")).stream()
                .map(a -> ((Map<?, ?>) a).get("name")).toList()));
        assertEquals(1, edit("remove-attribute", "Customer", "id"));
        assertTrue(err.contains("part of the key"), err);
    }

    @Test
    void unlinkAndRemoveEntityKeepForeignKeys() throws Exception {
        assertEquals(0, edit("unlink", "OrderLine", "product"), err);
        assertTrue(out.contains("Added OrderLine.productId for column PRODUCT_ID"), out);
        assertTrue(out.contains("Removed Product.orderLines (inverse)"), out);
        assertEquals(0, call("find_entity", "{\"entity\":\"OrderLine\",\"id\":4}"));
        assertTrue(out.contains("\"productId\" : 3"), out);

        assertEquals(0, edit("remove-entity", "Customer"), err);
        assertTrue(out.contains("Added Orders.customerId for column CUSTOMER_ID"), out);
        try (ModelRuntime m = ModelRuntime.start(new DefinitionLoader(ENV::get).load(dir))) {
            assertEquals(Set.of("OrderLine", "Orders", "Product"), m.entities().keySet());
        }
    }

    @Test
    void describeEntitiesAndAttributes() {
        assertEquals(0, edit("describe", "Customer", "A customer account"), err);
        assertEquals(0, edit("describe", "Customer.region", "Sales region: EAST, WEST or NORTH"), err);
        assertEquals(0, edit("describe", "Orders.customer", "Who placed the order"), err);
        Map<String, Object> d = describe("Customer");
        assertEquals("A customer account", d.get("description"));
        Map<?, ?> region = ((List<?>) d.get("attributes")).stream().map(Map.class::cast)
                .filter(a -> a.get("name").equals("region")).findFirst().orElseThrow();
        assertEquals("Sales region: EAST, WEST or NORTH", region.get("description"));
        assertEquals(0, edit("describe", "Customer", "A customer"), err);
        assertEquals("A customer", describe("Customer").get("description"), "replaced, not duplicated");
    }

    @Test
    void aChangeThatBreaksTheDefinitionIsRolledBack() throws Exception {
        Files.createDirectories(dir.resolve("reports"));
        Files.writeString(dir.resolve("reports/by-region.yaml"), """
                name: by_region
                root: Orders
                select:
                  - { path: customer.region, as: region }
                  - { aggregate: count, path: id, as: orders }
                groupBy: [customer.region]
                """);
        String before = Files.readString(orm);
        assertEquals(1, edit("rename-attribute", "Customer", "region", "area"));
        assertTrue(err.contains("Edit not applied") && err.contains("by_region") && err.contains("region"), err);
        assertEquals(before, Files.readString(orm), "the ORM file is restored");
        assertEquals(1, edit("rename-entity", "Orders", "1Order"));
        assertEquals(before, Files.readString(orm));
    }
}
