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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionLoader;
import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.tools.ToolException;
import io.github.djclarke.eclipselink.mcp.tools.ToolService;

/** The sales-customer sample: init output with writes enabled for Customer only, as the samples README shows. */
class SalesCustomerSampleTest {

    private static final Path DEFINITION = Path.of("samples/sales-customer");

    Function<String, String> env;
    ModelRuntime model;
    ToolService tools;

    @BeforeEach
    void start() {
        env = TestDatabase.freshCopy("salesCustomer");
        model = ModelRuntime.start(new DefinitionLoader(env).load(DEFINITION));
        tools = new ToolService(model);
    }

    @AfterEach
    void stop() {
        model.close();
    }

    @Test
    void onlyCustomerCreateAndUpdateAreExposed() {
        assertEquals(List.of("create_customer", "update_customer"),
                tools.exposedWrites().stream().map(w -> w.definition().name()).toList());
    }

    @Test
    void updateChecksTheVersionAndAStaleVersionConflicts() {
        Map<String, Object> result = tools.call("update_customer",
                Map.of("id", 3, "version", 1, "email", "ap@initech.example"));
        Map<?, ?> item = (Map<?, ?>) result.get("item");
        assertEquals("ap@initech.example", item.get("email"));
        assertEquals(2, ((Number) item.get("version")).intValue());

        ToolException e = assertThrows(ToolException.class, () -> tools.call("update_customer",
                Map.of("id", 3, "version", 0, "email", "old@initech.example")));
        assertEquals(ToolException.Code.CONFLICT, e.code(), e.getMessage());
    }

    @Test
    void updateChangesOnlyTheFieldsPassed() {
        Map<?, ?> before = (Map<?, ?>) tools.call("find_entity", Map.of("entity", "Customer", "id", 3)).get("item");
        Map<?, ?> item = (Map<?, ?>) tools.call("update_customer",
                Map.of("id", 3, "version", 1, "name", "Initech LLC", "creditLimit", "75000.00")).get("item");
        assertEquals("Initech LLC", item.get("name"));
        assertEquals(0, new java.math.BigDecimal("75000.00").compareTo(new java.math.BigDecimal((String) item.get("creditLimit"))));
        assertEquals(before.get("email"), item.get("email"), "left out, so unchanged");
        assertEquals(before.get("region"), item.get("region"), "left out, so unchanged");
        assertEquals(2, ((Number) item.get("version")).intValue());

        ToolException e = assertThrows(ToolException.class, () -> tools.call("update_customer",
                Map.of("id", 3, "version", 2, "region", "SOUTH")));
        assertEquals(ToolException.Code.INVALID_PARAMETER, e.code(), e.getMessage());
    }

    @Test
    void createGeneratesTheIdAndStartsAtVersionOne() {
        Map<?, ?> item = (Map<?, ?>) tools.call("create_customer",
                Map.of("name", "Stark Industries", "region", "EAST")).get("item");
        assertEquals(100L, item.get("id"));
        assertEquals(1, ((Number) item.get("version")).intValue());
    }
}
