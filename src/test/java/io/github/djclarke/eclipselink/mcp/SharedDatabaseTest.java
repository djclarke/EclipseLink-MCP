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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.djclarke.eclipselink.mcp.model.H2Urls;

/**
 * MCP clients such as Claude Desktop start several copies of a server. With an H2 file database each copy is
 * a separate process, so the file must be shared (AUTO_SERVER) or every copy after the first fails.
 */
class SharedDatabaseTest {

    @TempDir
    Path dir;

    /** Runs Main in a separate JVM, as an MCP client would. */
    private static Result runMain(Map<String, String> env, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Main.class.getName()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(env);
        Process p = pb.start();
        p.getOutputStream().close();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("timed out");
        }
        return new Result(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record Result(int code, String out, String err) {
    }

    @Test
    void anotherProcessSharesTheSampleDatabaseAsTheReadOnlyAccount() throws Exception {
        TestDatabase.environment();
        // This JVM holds the database open, like the first server copy.
        try (Connection first = DriverManager.getConnection(TestDatabase.URL, TestDatabase.READER_USER,
                TestDatabase.READER_PASSWORD)) {
            Result r = runMain(Map.of("SALES_DB_URL", TestDatabase.URL, "SALES_DB_USER", TestDatabase.READER_USER,
                    "SALES_DB_PASSWORD", TestDatabase.READER_PASSWORD),
                    "--definition", TestDatabase.SAMPLE_DEFINITION.toString(), "--validate");
            assertEquals(0, r.code(), r.err());
            assertTrue(r.out().contains("Server definition OK"), r.out());
            assertTrue(first.isValid(1));
        }
    }

    @Test
    void aLockedFileGivesAShortHintInsteadOfAStackTrace() throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("locked").toString().replace('\\', '/');
        Files.createDirectories(dir.resolve("def/model"));
        Files.writeString(dir.resolve("def/model/eclipselink-orm.xml"), """
                <entity-mappings xmlns="http://www.eclipse.org/eclipselink/xsds/persistence/orm" version="5.0">
                    <entity class="t.Item" access="VIRTUAL">
                        <table name="ITEM"/>
                        <attributes><id name="id" attribute-type="Long"><column name="ID"/></id></attributes>
                    </entity>
                </entity-mappings>
                """);
        Files.writeString(dir.resolve("def/eclipselink-mcp.yaml"), """
                apiVersion: eclipselink-mcp/v1
                server: { name: locked }
                connection: { url: "${env:DB_URL}", user: sa, password: "" }
                model: { mappings: model/eclipselink-orm.xml }
                """);
        try (Connection owner = DriverManager.getConnection(url, "sa", ""); Statement s = owner.createStatement()) {
            s.execute("CREATE TABLE ITEM (ID BIGINT PRIMARY KEY)");
            Result r = runMain(Map.of("DB_URL", url), "--definition", dir.resolve("def").toString(), "--validate");
            assertEquals(1, r.code());
            assertTrue(r.err().contains("in use by another process") && r.err().contains(";AUTO_SERVER=TRUE"), r.err());
            assertFalse(r.err().contains("Exception ["), "no EclipseLink stack trace: " + r.err());
            assertFalse(r.err().contains("\tat "), "no stack trace: " + r.err());
        }
    }

    @Test
    void initAndGeneratedDefinitionsShareH2Files() throws IOException {
        assertEquals("jdbc:h2:file:/d/s;AUTO_SERVER=TRUE", H2Urls.shareable("jdbc:h2:file:/d/s"));
        assertEquals("jdbc:h2:~/s;AUTO_SERVER=TRUE", H2Urls.shareable("jdbc:h2:~/s"));
        assertEquals("jdbc:h2:file:/d/s;auto_server=true", H2Urls.shareable("jdbc:h2:file:/d/s;auto_server=true"));
        assertEquals("jdbc:h2:mem:x", H2Urls.shareable("jdbc:h2:mem:x"));
        assertEquals("jdbc:h2:tcp://h/s", H2Urls.shareable("jdbc:h2:tcp://h/s"));
        assertEquals("jdbc:postgresql://h/db", H2Urls.shareable("jdbc:postgresql://h/db"));

        TestDatabase.environment();
        String plain = TestDatabase.URL.replace(";AUTO_SERVER=TRUE", "");
        Path out = dir.resolve("gen");
        assertEquals(0, io.github.djclarke.eclipselink.mcp.generate.InitCommand.run(new String[] {
                "--url", plain, "--user", TestDatabase.READER_USER, "--password-env", "PW", "--table", "CUSTOMER",
                "--out", out.toString()}, Map.of("PW", TestDatabase.READER_PASSWORD),
                new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        assertTrue(Files.readString(out.resolve("eclipselink-mcp.yaml")).contains(plain + ";AUTO_SERVER=TRUE}"));
    }
}
