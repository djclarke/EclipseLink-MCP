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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.tools.ToolException;
import io.github.djclarke.eclipselink.mcp.tools.ToolService;

/** Tool behavior against the sample sales database created by {@link TestDatabase}. */
class ToolServiceTest {

    static ModelRuntime model;
    static ToolService tools;

    @BeforeAll
    static void start() {
        model = ModelRuntime.start(TestDatabase.sampleDefinition());
        tools = new ToolService(model);
    }

    @AfterAll
    static void stop() {
        model.close();
    }

    @Test
    void exposesReadOnlyToolsAndOneToolPerReport() {
        assertEquals(List.of("list_entities", "describe_entity", "find_entity", "query_entities",
                        "report_open_orders_by_customer", "report_sales_by_region"),
                tools.tools().stream().map(ToolService.Tool::name).toList());
    }

    @Test
    void listEntities() {
        List<?> entities = (List<?>) tools.call("list_entities", Map.of()).get("entities");
        assertEquals(4, entities.size());
        Map<?, ?> order = (Map<?, ?>) entities.get(1);
        assertEquals("Order", order.get("name"));
        assertEquals(List.of("id"), order.get("key"));
        assertEquals(List.of("customer", "lines"), order.get("relationships"));
    }

    @Test
    void describeEntityIncludesTypesDescriptionsAndSchema() {
        Map<String, Object> d = tools.call("describe_entity", Map.of("entity", "Order"));
        List<?> attributes = (List<?>) d.get("attributes");
        Map<?, ?> orderDate = attributes.stream().map(Map.class::cast)
                .filter(a -> a.get("name").equals("orderDate")).findFirst().orElseThrow();
        assertEquals("date", orderDate.get("type"));
        Map<?, ?> props = (Map<?, ?>) ((Map<?, ?>) d.get("jsonSchema")).get("properties");
        assertEquals("number", ((Map<?, ?>) props.get("total")).get("type"));
        assertEquals("Order total in USD", ((Map<?, ?>) props.get("total")).get("description"));
    }

    @Test
    void findEntityNestsToOneRelationshipsByDepth() {
        Map<?, ?> item = (Map<?, ?>) tools.call("find_entity", Map.of("entity", "Order", "id", 1005)).get("item");
        assertEquals("2026-09-15", item.get("orderDate"));
        assertEquals(new BigDecimal("12000.00"), item.get("total"));
        assertEquals("Umbrella Traders", ((Map<?, ?>) item.get("customer")).get("name"));
        assertFalse(item.containsKey("lines"), "to-many relationships are omitted unless selected");

        Map<?, ?> shallow = (Map<?, ?>) tools.call("find_entity",
                Map.of("entity", "Order", "id", 1005, "depth", 0)).get("item");
        assertEquals(Map.of("id", 4L), shallow.get("customer"));
    }

    @Test
    void findEntityWithSelectPaths() {
        Map<?, ?> item = (Map<?, ?>) tools.call("find_entity", Map.of("entity", "Order", "id", 1006,
                "select", List.of("total", "customer.name", "lines.quantity", "lines.product.name"))).get("item");
        assertEquals(Map.of("id", 5L, "name", "Hooli"), item.get("customer"));
        List<?> lines = (List<?>) item.get("lines");
        assertEquals(2, lines.size());
        assertTrue(((Map<?, ?>) lines.get(0)).containsKey("product"));
        assertFalse(item.containsKey("orderDate"));
    }

    @Test
    void findEntityNotFound() {
        ToolException e = assertThrows(ToolException.class,
                () -> tools.call("find_entity", Map.of("entity", "Order", "id", 9999)));
        assertEquals(ToolException.Code.NOT_FOUND, e.code());
    }

    @Test
    void queryEntitiesWithFilterAcrossRelationship() {
        Map<String, Object> result = tools.call("query_entities", Map.of(
                "entity", "Order",
                "filter", Map.of("and", List.of(
                        Map.of("path", "status", "op", "eq", "value", "OPEN"),
                        Map.of("path", "customer.region", "op", "in", "value", List.of("EAST", "NORTH")))),
                "select", List.of("total", "customer.name"),
                "orderBy", List.of(Map.of("by", "total", "dir", "desc")),
                "count", true));
        List<?> items = (List<?>) result.get("items");
        assertEquals(2, items.size());
        assertEquals(1005L, ((Map<?, ?>) items.get(0)).get("id"));
        assertEquals(2L, result.get("count"));
        assertNull(result.get("nextCursor"));
    }

    @Test
    void queryEntitiesThroughToManyIsDistinct() {
        Map<String, Object> result = tools.call("query_entities", Map.of(
                "entity", "Customer",
                "filter", Map.of("path", "orders.status", "op", "eq", "value", "SHIPPED"),
                "select", List.of("name"),
                "count", true));
        assertEquals(2, ((List<?>) result.get("items")).size()); // Acme and Globex, Globex has two shipped orders
        assertEquals(2L, result.get("count"));
    }

    @Test
    void queryEntitiesPages() {
        Map<String, Object> page1 = tools.call("query_entities", Map.of("entity", "Order", "pageSize", 3));
        assertEquals(3, ((List<?>) page1.get("items")).size());
        String cursor = (String) page1.get("nextCursor");
        assertNotNull(cursor);
        Map<String, Object> page3 = tools.call("query_entities",
                Map.of("entity", "Order", "pageSize", 3, "cursor", tools.call("query_entities",
                        Map.of("entity", "Order", "pageSize", 3, "cursor", cursor)).get("nextCursor")));
        assertEquals(1, ((List<?>) page3.get("items")).size());
        assertNull(page3.get("nextCursor"));
    }

    @Test
    void queryErrorsAreAgentFacing() {
        assertEquals(ToolException.Code.UNKNOWN_ENTITY, assertThrows(ToolException.class,
                () -> tools.call("query_entities", Map.of("entity", "Salary"))).code());
        assertEquals(ToolException.Code.UNKNOWN_ATTRIBUTE, assertThrows(ToolException.class,
                () -> tools.call("query_entities", Map.of("entity", "Order",
                        "filter", Map.of("path", "customer.creditLimit", "op", "gt", "value", 1)))).code());
        assertEquals(ToolException.Code.INVALID_FILTER, assertThrows(ToolException.class,
                () -> tools.call("query_entities", Map.of("entity", "Order",
                        "filter", Map.of("path", "total", "op", "drop", "value", 1)))).code());
        assertEquals(ToolException.Code.INVALID_FILTER, assertThrows(ToolException.class,
                () -> tools.call("query_entities", Map.of("entity", "Order",
                        "filter", Map.of("path", "orderDate", "op", "gt", "value", "yesterday")))).code());
        assertEquals(ToolException.Code.INVALID_FILTER, assertThrows(ToolException.class,
                () -> tools.call("query_entities", Map.of("entity", "Order",
                        "filter", Map.of("path", "total", "op", "gt", "param", "x")))).code());
    }

    @Test
    void filterValuesAreBoundNotConcatenated() {
        Map<String, Object> result = tools.call("query_entities", Map.of("entity", "Customer",
                "filter", Map.of("path", "name", "op", "eq", "value", "x' OR '1'='1")));
        assertEquals(List.of(), result.get("items"));
    }

    @Test
    void salesByRegionReport() {
        Map<String, Object> result = tools.call("report_sales_by_region",
                Map.of("fromDate", "2026-07-01", "toDate", "2026-10-01"));
        List<?> rows = (List<?>) result.get("rows");
        // Q3: NORTH 12000 (1005), WEST 5500 (1003), EAST 1870 (1001+1002); 1004 is cancelled.
        assertEquals(3, rows.size());
        assertEquals(Map.of("region", "NORTH", "orderCount", 1L, "totalValue", new BigDecimal("12000.00")),
                rows.get(0));
        assertEquals(Map.of("region", "EAST", "orderCount", 2L, "totalValue", new BigDecimal("1870.00")),
                rows.get(2));
    }

    @Test
    void reportOptionalParameterAndValidation() {
        List<?> all = (List<?>) tools.call("report_open_orders_by_customer", Map.of()).get("rows");
        assertEquals(3, all.size());
        List<?> west = (List<?>) tools.call("report_open_orders_by_customer", Map.of("region", "WEST")).get("rows");
        assertEquals(1, west.size());
        assertEquals("Hooli", ((Map<?, ?>) west.get(0)).get("customer"));

        assertEquals(ToolException.Code.INVALID_PARAMETER, assertThrows(ToolException.class,
                () -> tools.call("report_sales_by_region", Map.of("fromDate", "2026-07-01"))).code());
        assertEquals(ToolException.Code.INVALID_PARAMETER, assertThrows(ToolException.class,
                () -> tools.call("report_sales_by_region",
                        Map.of("fromDate", "2026-07-01", "toDate", "2026-10-01", "groupBy", "status"))).code());
    }
}
