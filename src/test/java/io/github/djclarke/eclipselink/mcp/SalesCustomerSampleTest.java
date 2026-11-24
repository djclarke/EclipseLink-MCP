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
    void onlyTheCustomerEmailUpdateIsExposed() {
        assertEquals(List.of("update_customer_email"),
                tools.exposedWrites().stream().map(w -> w.definition().name()).toList());
    }

    @Test
    void updateChecksTheVersionAndAStaleVersionConflicts() {
        Map<String, Object> result = tools.call("update_customer_email",
                Map.of("id", 3, "version", 1, "email", "ap@initech.example"));
        Map<?, ?> item = (Map<?, ?>) result.get("item");
        assertEquals("ap@initech.example", item.get("email"));
        assertEquals(2, ((Number) item.get("version")).intValue());

        ToolException e = assertThrows(ToolException.class, () -> tools.call("update_customer_email",
                Map.of("id", 3, "version", 0, "email", "old@initech.example")));
        assertEquals(ToolException.Code.CONFLICT, e.code(), e.getMessage());
    }
}
