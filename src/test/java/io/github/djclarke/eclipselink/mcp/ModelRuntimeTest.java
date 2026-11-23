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

import java.util.List;

import jakarta.persistence.EntityManager;

import org.eclipse.persistence.dynamic.DynamicEntity;
import org.junit.jupiter.api.Test;

import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;

class ModelRuntimeTest {

    @Test
    void buildsDynamicEntitiesFromSampleDefinition() {
        try (ModelRuntime model = ModelRuntime.start(TestDatabase.sampleDefinition())) {
            assertEquals(List.of("Customer", "Order", "OrderLine", "Product"), List.copyOf(model.entities().keySet()));
            assertEquals("A customer account", ModelRuntime.description(model.entity("Customer")));

            EntityManager em = model.createEntityManager();
            try {
                DynamicEntity order = (DynamicEntity) em.find(model.entity("Order").getJavaClass(), 1005L);
                DynamicEntity customer = order.get("customer");
                assertEquals("Umbrella Traders", customer.get("name"));
                List<?> lines = order.get("lines");
                assertTrue(!lines.isEmpty());
            } finally {
                em.close();
            }
        }
    }
}
