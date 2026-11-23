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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionException;
import io.github.djclarke.eclipselink.mcp.definition.DefinitionLoader;
import io.github.djclarke.eclipselink.mcp.definition.ServerDefinition;
import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.tools.ToolService;

class DefinitionLoaderTest {

    @TempDir
    Path dir;

    @Test
    void loadsSampleWithEnvironmentSubstitution() {
        ServerDefinition def = TestDatabase.sampleDefinition();
        assertEquals("sales-db", def.descriptor().server().name());
        assertEquals(TestDatabase.URL, def.descriptor().connection().url());
        assertEquals("MCP_READER", def.descriptor().connection().user());
        assertEquals(2, def.reports().size());
    }

    @Test
    void missingEnvironmentVariableFails() {
        DefinitionException e = assertThrows(DefinitionException.class,
                () -> new DefinitionLoader(n -> null).load(TestDatabase.SAMPLE_DEFINITION));
        assertTrue(e.getMessage().contains("SALES_DB_URL"), e.getMessage());
    }

    @Test
    void literalPasswordIsRejected() throws IOException {
        write("connection:\n  url: jdbc:h2:mem:x\n  password: secret\n");
        DefinitionException e = assertThrows(DefinitionException.class, () -> new DefinitionLoader().load(dir));
        assertTrue(e.getMessage().contains("environment reference"), e.getMessage());
    }

    @Test
    void unknownKeysAreRejected() throws IOException {
        write("connection:\n  url: jdbc:h2:mem:x\n  passwrd: ${env:X:-}\n");
        DefinitionException e = assertThrows(DefinitionException.class, () -> new DefinitionLoader().load(dir));
        assertTrue(e.getMessage().contains("passwrd"), e.getMessage());
    }

    @Test
    void invalidReportFailsStartup() throws IOException {
        // Copy the sample and add a report that refers to an attribute that does not exist.
        Path model = Files.createDirectories(dir.resolve("model"));
        Files.copy(TestDatabase.SAMPLE_DEFINITION.resolve("model/eclipselink-orm.xml"),
                model.resolve("eclipselink-orm.xml"));
        Files.copy(TestDatabase.SAMPLE_DEFINITION.resolve("eclipselink-mcp.yaml"),
                dir.resolve("eclipselink-mcp.yaml"));
        Path reports = Files.createDirectories(dir.resolve("reports"));
        Files.writeString(reports.resolve("bad.yaml"), """
                name: bad
                root: Order
                select:
                  - { path: customer.creditLimit, as: limit }
                """);
        ServerDefinition def = new DefinitionLoader(TestDatabase.environment()).load(dir);
        try (ModelRuntime runtime = ModelRuntime.start(def)) {
            DefinitionException e = assertThrows(DefinitionException.class, () -> new ToolService(runtime));
            assertTrue(e.getMessage().contains("Report 'bad'") && e.getMessage().contains("creditLimit"),
                    e.getMessage());
        }
    }

    @Test
    void placeholderSyntax() {
        DefinitionLoader loader = new DefinitionLoader(Map.of("A", "1")::get);
        assertEquals("x1y", loader.resolve("x${env:A}y", dir));
        assertEquals("dflt", loader.resolve("${env:B:-dflt}", dir));
    }

    private void write(String body) throws IOException {
        Files.writeString(dir.resolve(DefinitionLoader.DESCRIPTOR_FILE),
                "apiVersion: eclipselink-mcp/v1\nserver:\n  name: t\nmodel:\n  mappings: m.xml\n" + body);
    }
}
