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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.djclarke.eclipselink.mcp.json.EntityJson;
import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.tools.Json;
import io.github.djclarke.eclipselink.mcp.tools.ToolException;
import io.github.djclarke.eclipselink.mcp.tools.ToolService;

/** The fixed JSON contract for decimals, floating point and dates/times (docs/server-definition.md 4.2.1). */
class TypeContractTest {

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

    private static Map<?, ?> find(String entity, int id) {
        return (Map<?, ?>) tools.call("find_entity", Map.of("entity", entity, "id", id, "depth", 0)).get("item");
    }

    private static List<Object> ids(String entity, Map<String, Object> filter) {
        List<?> items = (List<?>) tools.call("query_entities", Map.of("entity", entity, "filter", filter)).get("items");
        return items.stream().map(i -> (Object) ((Map<?, ?>) i).get("id")).toList();
    }

    @Test
    void decimalsAreStringsWithEveryDigit() {
        assertEquals("1234567890123456.7891", find("Customer", 1).get("creditLimit"), "beyond double precision");
        assertEquals("0.0001", find("Customer", 5).get("creditLimit"), "plain notation, no exponent");
        assertEquals("50000.0000", find("Customer", 2).get("creditLimit"), "scale kept as stored");
        assertNull(find("Customer", 3).get("creditLimit"));
        assertEquals("120.00", find("Product", 2).get("price"));
        // and on the wire
        String json = Json.write(tools.call("find_entity", Map.of("entity", "Customer", "id", 1, "depth", 0)));
        assertTrue(json.contains("\"creditLimit\":\"1234567890123456.7891\""), json);
    }

    @Test
    void floatingPointIsANumber() {
        assertEquals(0.25, find("Product", 1).get("weightKg"));
        assertNull(find("Product", 3).get("weightKg"));
        assertEquals("NaN", EntityJson.value(Double.NaN));
        assertEquals("-Infinity", EntityJson.value(Double.NEGATIVE_INFINITY));
    }

    @Test
    void datesAndTimesAreIso8601WithSecondsAndOffsets() {
        assertEquals("2026-10-01T00:00:00", find("Order", 1006).get("createdAt"), "seconds always shown");
        assertEquals("2026-08-14T16:42:07.25", find("Order", 1002).get("createdAt"), "fraction only when stored");
        assertEquals("2026-07-05T14:00:00-04:00", find("Order", 1001).get("shippedAt"), "offset kept, not shifted");
        assertEquals("2026-10-10T09:00:00+05:30", find("Order", 1007).get("shippedAt"));
        assertEquals("2026-07-22T10:30:00Z", find("Order", 1003).get("shippedAt"), "a zero offset is Z");
        assertNull(find("Order", 1002).get("shippedAt"));
        assertEquals("2026-07-03", find("Order", 1001).get("orderDate"));
        assertEquals("08:44:00", EntityJson.value(LocalTime.of(8, 44)));
        assertEquals("2026-11-23T08:44:00", EntityJson.value(LocalDateTime.of(2026, 11, 23, 8, 44)));
        assertEquals("2026-11-23T08:44:00-05:00",
                EntityJson.value(OffsetDateTime.of(2026, 11, 23, 8, 44, 0, 0, ZoneOffset.ofHours(-5))));
        assertEquals("1000", EntityJson.value(new BigDecimal("1E+3")));
    }

    @Test
    void filterValuesAreIsoOrDecimalStringsConvertedByTheServer() {
        assertEquals(List.of(1L), ids("Customer", Map.of("path", "creditLimit", "op", "gt", "value", "1000000")));
        assertEquals(List.of(2L), ids("Customer", Map.of("path", "creditLimit", "op", "eq", "value", 50000)));
        assertEquals(List.of(5L), ids("Customer", Map.of("path", "creditLimit", "op", "eq", "value", "0.0001")));
        assertEquals(List.of(1005L, 1006L, 1007L),
                ids("Order", Map.of("path", "createdAt", "op", "ge", "value", "2026-09-15T23:59:59")));
        // Offsets are compared as instants: 1001 shipped 18:00Z on 5 July, 1003 at 10:30Z on 22 July.
        assertEquals(List.of(1001L, 1003L),
                ids("Order", Map.of("path", "shippedAt", "op", "le", "value", "2026-07-22T06:30:00-04:00")));
        ToolException bad = assertThrows(ToolException.class,
                () -> ids("Order", Map.of("path", "createdAt", "op", "ge", "value", "2026-09-15 23:59")));
        assertEquals(ToolException.Code.INVALID_FILTER, bad.code());
        assertTrue(bad.getMessage().contains("use e.g. \"2026-11-23T08:44:00\""), bad.getMessage());
        ToolException notDecimal = assertThrows(ToolException.class,
                () -> ids("Customer", Map.of("path", "creditLimit", "op", "gt", "value", "1,000")));
        assertTrue(notDecimal.getMessage().contains("use e.g. \"1234.50\""), notDecimal.getMessage());
    }

    @Test
    void describeShowsFormatScaleExampleAndZone() {
        Map<String, Object> order = tools.describe(model.entity("Order"));
        Map<?, ?> created = attribute(order, "createdAt");
        assertEquals("local-date-time", created.get("type"));
        assertEquals("\"2026-11-23T08:44:00\"", created.get("example"));
        assertEquals("none: the value as stored in the database", created.get("timeZone"));
        Map<?, ?> shipped = attribute(order, "shippedAt");
        assertEquals("date-time", shipped.get("type"));
        assertNull(shipped.get("timeZone"));
        Map<?, ?> limit = attribute(tools.describe(model.entity("Customer")), "creditLimit");
        assertEquals("decimal", limit.get("type"));
        assertEquals(4, limit.get("scale"));
        Map<?, ?> weight = attribute(tools.describe(model.entity("Product")), "weightKg");
        assertEquals("number", weight.get("type"));
    }

    @Test
    void reportSumsOfDecimalsStayExact() {
        List<?> rows = (List<?>) tools.call("report_sales_by_region",
                Map.of("fromDate", "2026-01-01", "toDate", "2027-01-01")).get("rows");
        assertTrue(rows.stream().allMatch(r -> ((Map<?, ?>) r).get("totalValue") instanceof String), rows.toString());
        Map<?, ?> col = ((List<?>) tools.call("report_sales_by_region",
                Map.of("fromDate", "2026-01-01", "toDate", "2027-01-01")).get("columns")).stream()
                .map(Map.class::cast).filter(c -> c.get("name").equals("totalValue")).findFirst().orElseThrow();
        assertEquals("string", col.get("type"));
    }

    private static Map<?, ?> attribute(Map<String, Object> describe, String name) {
        return ((List<?>) describe.get("attributes")).stream().map(Map.class::cast)
                .filter(a -> a.get("name").equals(name)).findFirst().orElseThrow();
    }
}
