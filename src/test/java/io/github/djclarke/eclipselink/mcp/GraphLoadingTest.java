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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.persistence.jpa.JpaHelper;
import org.eclipse.persistence.sessions.SessionEvent;
import org.eclipse.persistence.sessions.SessionEventAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionException;
import io.github.djclarke.eclipselink.mcp.definition.QueryDefinition;
import io.github.djclarke.eclipselink.mcp.definition.ReportDefinition;
import io.github.djclarke.eclipselink.mcp.definition.ServerDefinition;
import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.tools.ToolService;

/**
 * Named queries and load graphs (docs/server-definition.md 2.5, 4.8): the graph decides both what is loaded
 * and the JSON returned, and a page costs a fixed number of SQL statements whatever its size.
 */
class GraphLoadingTest {

    static ModelRuntime model;
    static ToolService tools;
    static final AtomicInteger statements = new AtomicInteger();

    @BeforeAll
    static void start() {
        model = ModelRuntime.start(TestDatabase.sampleDefinition());
        tools = new ToolService(model);
        JpaHelper.getServerSession(model.entityManagerFactory()).getEventManager().addListener(
                new SessionEventAdapter() {
                    @Override
                    public void postExecuteCall(SessionEvent event) {
                        statements.incrementAndGet();
                    }
                });
    }

    @AfterAll
    static void stop() {
        model.close();
    }

    @BeforeEach
    void reset() {
        statements.set(0);
    }

    @Test
    void namedQueryReturnsTheDeclaredGraph() {
        Map<String, Object> out = tools.call("query_order_details", Map.of("region", "EAST"));
        List<?> items = (List<?>) out.get("items");
        assertEquals(List.of(1004L, 1002L, 1001L), items.stream().map(i -> ((Map<?, ?>) i).get("id")).toList());
        Map<String, Object> product = ordered("id", 2L, "name", "Gadget", "category", "HARDWARE");
        Map<String, Object> line = ordered("id", 5L, "quantity", 2, "price", "120.00",
                "product", product);
        Map<String, Object> expected = ordered("id", 1004L, "orderDate", "2026-09-02", "status", "CANCELLED",
                "total", "240.00", "customer", ordered("id", 3L, "name", "Initech"),
                "lines", List.of(line));
        assertEquals(expected, items.get(0));
        assertEquals(List.copyOf(expected.keySet()), List.copyOf(((Map<?, ?>) items.get(0)).keySet()),
                "graph order is kept");
    }

    @Test
    void aPageCostsOneStatementPerGraphLevelNotPerRow() {
        // Orders, then one IN batch each for customer, lines and lines.product.
        tools.call("query_order_details", Map.of("region", "EAST"));
        assertEquals(4, statements.getAndSet(0));
        tools.call("query_order_details", Map.of("region", "WEST"));
        assertEquals(4, statements.getAndSet(0));
    }

    @Test
    void optionalParameterAndPaging() {
        List<?> open = (List<?>) tools.call("query_order_details", Map.of("region", "WEST", "status", "OPEN"))
                .get("items");
        assertEquals(List.of(1006L), open.stream().map(i -> ((Map<?, ?>) i).get("id")).toList());
        statements.set(0);
        Map<String, Object> page = tools.call("query_order_details", Map.of("region", "WEST", "pageSize", 2));
        assertEquals(2, ((List<?>) page.get("items")).size());
        assertNotNull(page.get("nextCursor"));
        assertEquals(4, statements.get());
    }

    @Test
    void defaultOutputReadsReferencesFromForeignKeysWithoutLazyLoading() {
        List<?> items = (List<?>) tools.call("query_entities", Map.of("entity", "Order")).get("items");
        assertEquals(7, items.size());
        assertEquals(Map.of("id", 1L), ((Map<?, ?>) items.get(0)).get("customer"));
        assertEquals(1, statements.get(), "only the orders query; customers are not loaded");

        statements.set(0);
        List<?> lines = (List<?>) tools.call("query_entities", Map.of("entity", "OrderLine")).get("items");
        assertEquals(Map.of("id", 1001L), ((Map<?, ?>) lines.get(0)).get("order"));
        assertEquals(Map.of("id", 1L), ((Map<?, ?>) lines.get(0)).get("product"));
        assertEquals(1, statements.get());
    }

    @Test
    void querySelectPathsAreBatchRead() {
        List<?> items = (List<?>) tools.call("query_entities", Map.of("entity", "Order",
                "select", List.of("total", "customer.name", "lines.product.name"))).get("items");
        assertEquals(7, items.size());
        assertEquals(4, statements.get(), "orders + customer + lines + lines.product");
    }

    @Test
    void findEntityBatchReadsTheDepthGraph() {
        Map<?, ?> item = (Map<?, ?>) tools.call("find_entity", Map.of("entity", "OrderLine", "id", 6,
                "depth", 2)).get("item");
        Map<?, ?> order = (Map<?, ?>) item.get("order");
        assertEquals("Umbrella Traders", ((Map<?, ?>) order.get("customer")).get("name"));
        assertEquals("Support Plan", ((Map<?, ?>) item.get("product")).get("name"));
        // line, then order + product, then order.customer
        assertTrue(statements.get() <= 4, "statements: " + statements.get());
    }

    @Test
    void invalidQueriesFailStartup() {
        assertStartupFails(query(Map.of("path", "status", "op", "eq", "value", "OPEN"), List.of("custmer.name"),
                new LinkedHashMap<>()), "custmer");
        LinkedHashMap<String, ReportDefinition.Parameter> unused = new LinkedHashMap<>();
        unused.put("region", new ReportDefinition.Parameter("string", true, null));
        assertStartupFails(query(null, List.of("total"), unused), "declared but not used");
        assertStartupFails(query(Map.of("path", "status", "op", "eq", "param", "status"), List.of("total"),
                new LinkedHashMap<>()), "undeclared parameter 'status'");
    }

    private static QueryDefinition query(Map<String, Object> filter, List<String> graph,
                                         LinkedHashMap<String, ReportDefinition.Parameter> params) {
        return new QueryDefinition("bad", null, null, "Order", filter, null, params, graph);
    }

    private static void assertStartupFails(QueryDefinition q, String message) {
        ServerDefinition sample = TestDatabase.sampleDefinition();
        ServerDefinition def = new ServerDefinition(sample.directory(), sample.descriptor(), List.of(), List.of(q));
        try (ModelRuntime m = ModelRuntime.start(def)) {
            DefinitionException e = assertThrows(DefinitionException.class, () -> new ToolService(m));
            assertTrue(e.getMessage().contains(message), e.getMessage());
        }
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
