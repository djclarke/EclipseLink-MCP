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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionException;
import io.github.djclarke.eclipselink.mcp.definition.DefinitionLoader;
import io.github.djclarke.eclipselink.mcp.definition.Descriptor;
import io.github.djclarke.eclipselink.mcp.definition.ServerDefinition;
import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.tools.ToolException;
import io.github.djclarke.eclipselink.mcp.tools.ToolService;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Named write operations with optimistic locking (docs/server-definition.md 2.7, 4.9) on the order-desk sample,
 * each test against its own fresh copy of the sample database.
 */
class WriteToolsTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @TempDir
    Path tmp;
    Function<String, String> env;
    ModelRuntime model;
    ToolService tools;

    @BeforeEach
    void start() {
        env = TestDatabase.freshCopy("writes" + DATABASES.incrementAndGet());
        model = ModelRuntime.start(new DefinitionLoader(env).load(TestDatabase.ORDER_DESK_DEFINITION));
        tools = new ToolService(model);
    }

    @AfterEach
    void stop() {
        model.close();
    }

    private Map<String, Object> call(String tool, Object... keyValues) {
        return tools.call(tool, args(keyValues));
    }

    private ToolException fails(ToolException.Code code, String tool, Object... keyValues) {
        ToolException e = assertThrows(ToolException.class, () -> tools.call(tool, args(keyValues)));
        assertEquals(code, e.code(), e.getMessage());
        return e;
    }

    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private Map<?, ?> find(String entity, Object id) {
        return (Map<?, ?>) call("find_entity", "entity", entity, "id", id, "depth", 0).get("item");
    }

    private Object db(String sql) {
        return TestDatabase.query(env, sql);
    }

    // ---- read-only by default -----------------------------------------------------------------------------

    @Test
    void theReadOnlySampleHasNoWriteTools() {
        try (ModelRuntime readOnly = ModelRuntime.start(TestDatabase.sampleDefinition())) {
            assertTrue(new ToolService(readOnly).tools().stream().allMatch(t -> t.kind() == ToolService.Kind.READ));
        }
    }

    @Test
    void writeOperationsStayHiddenUntilTheDescriptorEnablesThem() {
        ServerDefinition d = model.definition();
        Descriptor x = d.descriptor();
        Descriptor off = new Descriptor(x.apiVersion(), x.server(), x.connection(), x.model(),
                new Descriptor.Tools(null, null, null, null, null, null, List.of()), x.limits(), x.transport(),
                x.logging());
        try (ModelRuntime m = ModelRuntime.start(new ServerDefinition(d.directory(), off, d.reports(), d.queries(),
                d.writes()))) {
            ToolService readOnly = new ToolService(m);
            assertTrue(readOnly.tools().stream().allMatch(t -> t.kind() == ToolService.Kind.READ));
            ToolException e = assertThrows(ToolException.class, () -> readOnly.call("ship_order", Map.of()));
            assertTrue(e.getMessage().contains("tools.writes"), e.getMessage());
            assertTrue(McpServerFactory.instructions(m, readOnly).contains("read-only server"));
        }
    }

    /** The order-desk definition with writes enabled for other entities. */
    private ModelRuntime withWritable(Path definition, String... entities) {
        ServerDefinition d = new DefinitionLoader(env).load(definition);
        Descriptor x = d.descriptor();
        Descriptor changed = new Descriptor(x.apiVersion(), x.server(), x.connection(), x.model(),
                new Descriptor.Tools(null, null, null, null, null, null, List.of(entities)), x.limits(), x.transport(),
                x.logging());
        return ModelRuntime.start(new ServerDefinition(d.directory(), changed, d.reports(), d.queries(), d.writes()));
    }

    @Test
    void writesAreEnabledPerEntity() {
        ToolException hidden = assertThrows(ToolException.class, () -> tools.call("update_customer_email",
                args("id", 2, "version", 1, "email", "new@globex.example")));
        assertTrue(hidden.getMessage().contains("Customer is not listed under tools.writes"), hidden.getMessage());

        try (ModelRuntime m = withWritable(TestDatabase.ORDER_DESK_DEFINITION, "Order", "OrderLine", "Customer")) {
            ToolService t = new ToolService(m);
            assertEquals(List.of("add_order_line", "cancel_order", "change_line_quantity", "remove_order_line",
                            "ship_order", "update_customer_email"),
                    t.exposedWrites().stream().map(w -> w.definition().name()).toList());
            Map<?, ?> item = (Map<?, ?>) t.call("update_customer_email",
                    args("id", 2, "version", 1, "email", "new@globex.example")).get("item");
            assertEquals(2, item.get("version"));
            ToolException tooLong = assertThrows(ToolException.class, () -> t.call("update_customer_email",
                    args("id", 1, "version", 1, "email", "x".repeat(101) + "@example.com")));
            assertEquals(ToolException.Code.INVALID_PARAMETER, tooLong.code(), tooLong.getMessage());
        }
    }

    @Test
    void anEntityEnabledForWritesMustBeLocked() throws IOException {
        DefinitionException unknown = assertThrows(DefinitionException.class, () -> {
            try (ModelRuntime m = withWritable(TestDatabase.ORDER_DESK_DEFINITION, "Invoice")) {
                new ToolService(m);
            }
        });
        assertTrue(unknown.getMessage().contains("unknown entity 'Invoice'"), unknown.getMessage());

        Path dir = copyOrderDesk();
        Path orm = dir.resolve("model/eclipselink-orm.xml");
        String xml = Files.readString(orm);
        int product = xml.indexOf("<entity class=\"Product\"");
        int version = xml.indexOf("<version ", product);
        int end = xml.indexOf("</version>", version) + "</version>".length();
        Files.writeString(orm, xml.substring(0, version) + xml.substring(end));
        DefinitionException unlocked = assertThrows(DefinitionException.class, () -> {
            try (ModelRuntime m = withWritable(dir, "Order", "Product")) {
                new ToolService(m);
            }
        });
        assertTrue(unlocked.getMessage().contains("no optimistic lock"), unlocked.getMessage());
    }

    @Test
    void writeToolsAreAnnotatedForClients() {
        Map<String, ToolService.Kind> kinds = new LinkedHashMap<>();
        tools.tools().stream().filter(t -> t.kind() != ToolService.Kind.READ).forEach(t -> kinds.put(t.name(), t.kind()));
        assertEquals(Map.of("add_order_line", ToolService.Kind.CREATE, "cancel_order", ToolService.Kind.UPDATE,
                "change_line_quantity", ToolService.Kind.UPDATE, "remove_order_line", ToolService.Kind.DELETE,
                "ship_order", ToolService.Kind.UPDATE), kinds, "only operations on Order and OrderLine");
        McpSchema.ToolAnnotations update = McpServerFactory.annotations(ToolService.Kind.UPDATE);
        assertFalse(update.readOnlyHint());
        assertTrue(update.destructiveHint());
        assertFalse(McpServerFactory.annotations(ToolService.Kind.CREATE).destructiveHint());
        assertTrue(McpServerFactory.annotations(ToolService.Kind.READ).readOnlyHint());
        assertTrue(McpServerFactory.instructions(model, tools).contains("Write tools: "));
    }

    @Test
    void inputSchemasAskForTheVersionTheLockNeeds() {
        Map<?, ?> ship = schema("ship_order");
        assertEquals(List.of("id", "version", "shippedAt"), ship.get("required"));
        Map<?, ?> add = schema("add_order_line");
        assertEquals(List.of("orderVersion", "order", "product", "quantity", "price"), add.get("required"));
        assertFalse(((Map<?, ?>) add.get("properties")).containsKey("id"), "the line's key is generated");
        assertEquals(List.of("id", "orderVersion"), schema("remove_order_line").get("required"));
    }

    private Map<?, ?> schema(String tool) {
        return tools.tools().stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow().inputSchema();
    }

    // ---- versions travel in the JSON ----------------------------------------------------------------------

    @Test
    void readToolsReturnVersionsWithKeys() {
        assertEquals(1, find("Order", 1002).get("version"));
        Map<?, ?> selected = (Map<?, ?>) call("find_entity", "entity", "Order", "id", 1002, "select",
                List.of("total")).get("item");
        assertEquals(Map.of("id", 1002L, "version", 1, "total", "620.00"), selected);
        List<?> open = (List<?>) call("query_open_orders", "customerId", 1).get("items");
        assertEquals(1, ((Map<?, ?>) open.get(0)).get("version"));

        Map<String, Object> line = tools.call("describe_entity", Map.of("entity", "OrderLine"));
        assertEquals(Map.of("through", "order", "entity", "Order", "version", "version", "input", "orderVersion"),
                line.get("lock"));
        Map<String, Object> order = tools.call("describe_entity", Map.of("entity", "Order"));
        assertEquals(Map.of("version", "version", "input", "version"), order.get("lock"));
    }

    // ---- an entity's own version ----------------------------------------------------------------------------

    @Test
    void updateChecksAndIncrementsTheVersion() {
        Map<String, Object> out = call("ship_order", "id", 1002, "version", 1,
                "shippedAt", "2026-11-23T10:30:00-05:00");
        Map<?, ?> item = (Map<?, ?>) out.get("item");
        assertEquals("SHIPPED", item.get("status"));
        assertEquals("2026-11-23T10:30:00-05:00", item.get("shippedAt"));
        assertEquals(2, item.get("version"));
        assertEquals(2, find("Order", 1002).get("version"));
        assertEquals("SHIPPED", db("SELECT STATUS FROM ORDERS WHERE ID = 1002"));
    }

    @Test
    void aStaleVersionIsAConflictAndChangesNothing() {
        call("ship_order", "id", 1006, "version", 1, "shippedAt", "2026-11-23T10:30:00Z");
        // A second agent read the order while it was open, and tries to cancel it with that version.
        ToolException e = fails(ToolException.Code.CONFLICT, "cancel_order", "id", 1006, "version", 1);
        assertEquals(Map.of("entity", "Order", "id", 1006L, "expectedVersion", 1, "currentVersion", 2), e.details());
        assertTrue(e.getMessage().contains("Read it again"), e.getMessage());
        assertEquals("SHIPPED", db("SELECT STATUS FROM ORDERS WHERE ID = 1006"));
    }

    @Test
    void aChangeByAnotherApplicationIsDetected() {
        TestDatabase.execute(env, "UPDATE ORDERS SET TOTAL = 13000.00, VERSION = VERSION + 1 WHERE ID = 1005");
        fails(ToolException.Code.CONFLICT, "ship_order", "id", 1005, "version", 1, "shippedAt", "2026-11-23T10:30:00Z");
        Map<?, ?> item = (Map<?, ?>) call("ship_order", "id", 1005, "version", 2,
                "shippedAt", "2026-11-23T10:30:00Z").get("item");
        assertEquals("13000.00", item.get("total"));
        assertEquals(3, item.get("version"));
    }

    @Test
    void aConditionThatDoesNotHoldIsAPreconditionFailure() {
        fails(ToolException.Code.PRECONDITION_FAILED, "cancel_order", "id", 1001, "version", 1);
        assertEquals("SHIPPED", db("SELECT STATUS FROM ORDERS WHERE ID = 1001"));
        assertEquals(1, db("SELECT VERSION FROM ORDERS WHERE ID = 1001"));
    }

    // ---- locked through a relationship ------------------------------------------------------------------------

    @Test
    void addingALineChecksAndIncrementsTheOrdersVersion() {
        Map<String, Object> out = call("add_order_line", "order", 1002, "orderVersion", 1, "product", 1,
                "quantity", 3, "price", "25.00");
        Map<?, ?> item = (Map<?, ?>) out.get("item");
        assertEquals(1000L, item.get("id"), "generated by the identity column");
        assertEquals(Map.of("id", 1002L), item.get("order"));
        assertEquals(Map.of("entity", "Order", "id", 1002L, "version", 2), out.get("locked"));
        assertEquals(2, db("SELECT VERSION FROM ORDERS WHERE ID = 1002"));

        // A second agent that read the order before the first one's change cannot add its line.
        ToolException e = fails(ToolException.Code.CONFLICT, "add_order_line", "order", 1002, "orderVersion", 1,
                "product", 2, "quantity", 1, "price", "120.00");
        assertEquals(2, e.details().get("currentVersion"));
        assertEquals(3L, db("SELECT COUNT(*) FROM ORDER_LINE WHERE ORDER_ID = 1002"));
    }

    @Test
    void aSequenceOfLineChangesUsesTheReturnedVersionsWithoutReadingAgain() {
        Map<?, ?> locked = (Map<?, ?>) call("add_order_line", "order", 1006, "orderVersion", 1, "product", 4,
                "quantity", 1, "price", "5.50").get("locked");
        Object lineId = 1000L;
        locked = (Map<?, ?>) call("change_line_quantity", "id", lineId, "orderVersion", locked.get("version"),
                "quantity", 4).get("locked");
        assertEquals(3, locked.get("version"));
        Map<String, Object> removed = call("remove_order_line", "id", 7, "orderVersion", locked.get("version"));
        assertEquals(true, removed.get("deleted"));
        assertEquals(Map.of("entity", "Order", "id", 1006L, "version", 4), removed.get("locked"));
        assertEquals(4, db("SELECT VERSION FROM ORDERS WHERE ID = 1006"));
        assertEquals(4, db("SELECT QUANTITY FROM ORDER_LINE WHERE ID = 1000"));
        assertNull(db("SELECT ID FROM ORDER_LINE WHERE ID = 7"));
    }

    @Test
    void lineChangesOnAShippedOrderAreRefused() {
        fails(ToolException.Code.PRECONDITION_FAILED, "add_order_line", "order", 1001, "orderVersion", 1,
                "product", 1, "quantity", 1, "price", "25.00");
        fails(ToolException.Code.PRECONDITION_FAILED, "remove_order_line", "id", 1, "orderVersion", 1);
        assertEquals(1L, db("SELECT COUNT(*) FROM ORDER_LINE WHERE ORDER_ID = 1001"));
        assertEquals(1, db("SELECT VERSION FROM ORDERS WHERE ID = 1001"));
    }

    // ---- inputs and database errors -----------------------------------------------------------------------

    @Test
    void inputsAreChecked() {
        fails(ToolException.Code.INVALID_PARAMETER, "ship_order", "id", 1002, "shippedAt", "2026-11-23T10:30:00Z");
        fails(ToolException.Code.INVALID_PARAMETER, "ship_order", "id", 1002, "version", 1,
                "shippedAt", "2026-11-23T10:30:00Z", "status", "OPEN");
        fails(ToolException.Code.INVALID_FILTER, "ship_order", "id", 1002, "version", 1, "shippedAt", "23/11/2026");
        fails(ToolException.Code.NOT_FOUND, "ship_order", "id", 9999, "version", 1, "shippedAt", "2026-11-23T10:30:00Z");
        fails(ToolException.Code.NOT_FOUND, "add_order_line", "order", 1002, "orderVersion", 1, "product", 99,
                "quantity", 1, "price", "1.00");
    }

    @Test
    void aDryRunRollsBack() {
        try (ModelRuntime m = ModelRuntime.start(new DefinitionLoader(env).load(TestDatabase.ORDER_DESK_DEFINITION))) {
            Map<String, Object> out = new ToolService(m, true).call("ship_order",
                    args("id", 1002, "version", 1, "shippedAt", "2026-11-23T10:30:00Z"));
            assertEquals(true, out.get("dryRun"));
            assertEquals(2, ((Map<?, ?>) out.get("item")).get("version"));
        }
        assertEquals("OPEN", db("SELECT STATUS FROM ORDERS WHERE ID = 1002"));
        assertEquals(1, db("SELECT VERSION FROM ORDERS WHERE ID = 1002"));
    }

    @Test
    void missingGrantsAndConstraintsAreReportedWithoutSql() throws IOException {
        Path dir = copyOrderDesk();
        Files.writeString(dir.resolve("writes/delete-customer.yaml"), """
                name: delete_customer
                action: delete
                root: Customer
                """);
        Files.writeString(dir.resolve("writes/add-line-loosely.yaml"), """
                name: add_line_loosely
                action: create
                root: OrderLine
                set: { order: { required: true }, product: { required: true }, quantity: {}, price: {} }
                """);
        try (ModelRuntime m = withWritable(dir, "Order", "OrderLine", "Customer")) {
            ToolService t = new ToolService(m);
            ToolException denied = assertThrows(ToolException.class,
                    () -> t.call("delete_customer", args("id", 5, "version", 1)));
            assertEquals(ToolException.Code.PERMISSION_DENIED, denied.code(), denied.getMessage());
            ToolException notNull = assertThrows(ToolException.class, () -> t.call("add_line_loosely",
                    args("order", 1002, "orderVersion", 1, "product", 1)));
            assertEquals(ToolException.Code.CONSTRAINT_VIOLATION, notNull.code(), notNull.getMessage());
            assertFalse(notNull.getMessage().contains("INSERT"), notNull.getMessage());
        }
        assertEquals(5L, db("SELECT COUNT(*) FROM CUSTOMER"));
        assertEquals(1, db("SELECT VERSION FROM ORDERS WHERE ID = 1002"));
    }

    // ---- startup validation -----------------------------------------------------------------------------------

    @Test
    void invalidWriteOperationsFailStartup() throws IOException {
        assertInvalid("name: ship_it\naction: update\nroot: Order\nfixed: { status: SHIPPED }\nlocking: none\n",
                "remove 'locking: none'");
        assertInvalid("name: rekey\naction: update\nroot: Order\nset: { id: {} }\n", "part of the key");
        assertInvalid("name: bump\naction: update\nroot: Order\nset: { version: {} }\n", "the version");
        assertInvalid("name: relines\naction: update\nroot: Order\nset: { lines: {} }\n", "owning to-one");
        assertInvalid("name: move_line\naction: update\nroot: OrderLine\nset: { order: {} }\n", "locked through");
        assertInvalid("name: orphan_line\naction: create\nroot: OrderLine\nset: { quantity: {} }\n", "must set it");
        assertInvalid("name: odd\naction: update\nroot: Order\nset: { total: { allowed: [abc] } }\n", "total");
        assertInvalid("name: query_x\naction: update\nroot: Order\nfixed: { status: OPEN }\n", "clashes");
        assertInvalid("name: wipe\naction: delete\nroot: Order\nfixed: { status: OPEN }\n", "no set or fixed");
        assertInvalid("name: ship_order\naction: update\nroot: Nope\nfixed: { status: OPEN }\n", "duplicate");
    }

    private void assertInvalid(String yaml, String expected) throws IOException {
        Path dir = copyOrderDesk();
        Files.writeString(dir.resolve("writes/zz-invalid.yaml"), yaml);
        DefinitionException e = assertThrows(DefinitionException.class, () -> {
            try (ModelRuntime m = ModelRuntime.start(new DefinitionLoader(env).load(dir))) {
                new ToolService(m);
            }
        });
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    @Test
    void theOrderDeskModelIsTheSalesDbModel() throws IOException {
        assertEquals(Files.readString(Path.of("samples/sales-db/model/eclipselink-orm.xml")),
                Files.readString(TestDatabase.ORDER_DESK_DEFINITION.resolve("model/eclipselink-orm.xml")));
    }

    private Path copyOrderDesk() throws IOException {
        Path to = Files.createTempDirectory(tmp, "order-desk");
        try (Stream<Path> s = Files.walk(TestDatabase.ORDER_DESK_DEFINITION)) {
            for (Path p : s.toList()) {
                Path target = to.resolve(TestDatabase.ORDER_DESK_DEFINITION.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
        return to;
    }
}
