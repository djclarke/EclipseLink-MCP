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

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.Function;

import org.h2.tools.RunScript;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionLoader;
import io.github.djclarke.eclipselink.mcp.definition.ServerDefinition;

/**
 * Test harness: creates the sample "existing" sales database as an H2 file database under
 * {@code target/test-db/sales}, from {@code src/test/resources/db}. It is rebuilt once per test JVM, so every
 * {@code mvn test} starts from the same data, and the file is left behind for manual runs (see README).
 * {@code mvn -Psample-db process-test-resources} builds the same database from the same script without the tests.
 *
 * <p>The MCP server connects as the read-only {@code MCP_READER} account, never as the owner.
 */
public final class TestDatabase {

    public static final Path SAMPLE_DEFINITION = Path.of("samples/sales-db");
    public static final Path FILE = Path.of("target/test-db/sales").toAbsolutePath();
    /** AUTO_SERVER lets other processes (e.g. a server started from the README) share the file. */
    public static final String URL = "jdbc:h2:file:" + FILE.toString().replace('\\', '/') + ";AUTO_SERVER=TRUE";
    public static final String READER_USER = "MCP_READER";
    public static final String READER_PASSWORD = "reader";

    private static boolean created;

    private TestDatabase() {
    }

    /** Creates the database (once per JVM) and returns environment values for the sample definition. */
    public static synchronized Function<String, String> environment() {
        if (!created) {
            create();
            created = true;
        }
        Map<String, String> env = Map.of(
                "SALES_DB_URL", URL,
                "SALES_DB_USER", READER_USER,
                "SALES_DB_PASSWORD", READER_PASSWORD);
        return env::get;
    }

    public static final Path ORDER_DESK_DEFINITION = Path.of("samples/order-desk");
    public static final String ORDER_DESK_USER = "MCP_ORDER_DESK";
    public static final String ORDER_DESK_PASSWORD = "orderdesk";

    /**
     * A fresh private copy of the sample database, in memory, for tests that write. Returns environment values
     * for both sample definitions pointing at it.
     */
    public static Function<String, String> freshCopy(String name) {
        // Kept open for the JVM by the owner's setting; other accounts may not set DB_CLOSE_DELAY themselves.
        String url = "jdbc:h2:mem:" + name;
        try (Connection c = DriverManager.getConnection(url + ";DB_CLOSE_DELAY=-1", "sa", "")) {
            run(c, "/db/sales-db.sql");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create test database " + name, e);
        }
        Map<String, String> env = Map.of(
                "SALES_DB_URL", url,
                "SALES_DB_USER", READER_USER,
                "SALES_DB_PASSWORD", READER_PASSWORD,
                "ORDER_DESK_DB_PASSWORD", ORDER_DESK_PASSWORD);
        return env::get;
    }

    /** Runs SQL as the database owner, e.g. to simulate another application changing a row. */
    public static void execute(Function<String, String> env, String sql) {
        try (Connection c = DriverManager.getConnection(env.apply("SALES_DB_URL"), "sa", "")) {
            c.createStatement().execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One value from SQL run as the database owner. */
    public static Object query(Function<String, String> env, String sql) {
        try (Connection c = DriverManager.getConnection(env.apply("SALES_DB_URL"), "sa", "");
             var rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getObject(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ServerDefinition sampleDefinition() {
        return new DefinitionLoader(environment()).load(SAMPLE_DEFINITION);
    }

    private static void create() {
        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            // Not sales-db.sql: its RUNSCRIPT FROM 'classpath:...' runs in the JVM that owns the H2 file, which
            // under AUTO_SERVER may be another process (e.g. a sales-db server in Claude Desktop) without these
            // resources. Reading the scripts here sends their statements over the connection instead.
            c.createStatement().execute("DROP ALL OBJECTS");
            run(c, "/db/sales-schema.sql");
            run(c, "/db/sales-data.sql");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create test database at " + FILE, e);
        }
    }

    private static void run(Connection c, String resource) throws SQLException {
        try (Reader r = new InputStreamReader(TestDatabase.class.getResourceAsStream(resource),
                StandardCharsets.UTF_8)) {
            RunScript.execute(c, r);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
