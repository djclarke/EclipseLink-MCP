/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp.model;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import javax.sql.DataSource;

import org.eclipse.persistence.config.PersistenceUnitProperties;
import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.dynamic.DynamicClassLoader;
import org.eclipse.persistence.jpa.JpaHelper;
import org.eclipse.persistence.jpa.PersistenceProvider;
import org.eclipse.persistence.sessions.server.Server;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionException;
import io.github.djclarke.eclipselink.mcp.definition.Descriptor;
import io.github.djclarke.eclipselink.mcp.definition.ServerDefinition;

/**
 * Builds and owns the EclipseLink persistence unit for one server definition: dynamic (VIRTUAL) entities from
 * the definition's ORM XML, no Java entity classes and no persistence.xml.
 *
 * <p>Each instance owns its own EntityManagerFactory, pool and cache, with no static state, so that several
 * could later share one JVM (requirements 2, 4.6).
 */
public final class ModelRuntime implements AutoCloseable {

    /** EclipseLink descriptor or mapping property carrying a human description for agents. */
    public static final String DESCRIPTION_PROPERTY = "mcp.description";

    private final ServerDefinition definition;
    private final JdbcDrivers drivers;
    private final URLClassLoader resourceLoader;
    private final EntityManagerFactory emf;
    private final Map<String, ClassDescriptor> entities;

    private ModelRuntime(ServerDefinition definition, JdbcDrivers drivers, URLClassLoader resourceLoader,
                         EntityManagerFactory emf, Map<String, ClassDescriptor> entities) {
        this.definition = definition;
        this.drivers = drivers;
        this.resourceLoader = resourceLoader;
        this.emf = emf;
        this.entities = entities;
    }

    public static ModelRuntime start(ServerDefinition definition) {
        return start(definition, (Path) null);
    }

    /**
     * @param driversOverride JDBC drivers directory from the command line, or null (docs 3.3)
     */
    public static ModelRuntime start(ServerDefinition definition, Path driversOverride) {
        JdbcDrivers drivers = JdbcDrivers.load(JdbcDrivers.directoryFor(definition, driversOverride));
        try {
            return startWith(definition, drivers);
        } catch (RuntimeException e) {
            drivers.close();
            throw e;
        }
    }

    private static ModelRuntime startWith(ServerDefinition definition, JdbcDrivers drivers) {
        Descriptor d = definition.descriptor();
        URLClassLoader resourceLoader = new URLClassLoader(
                new URL[] {toUrl(definition.directory())}, ModelRuntime.class.getClassLoader());
        DynamicClassLoader dcl = new DynamicClassLoader(resourceLoader);

        Map<String, Object> props = new HashMap<>();
        props.put(PersistenceUnitProperties.CLASSLOADER, dcl);
        props.put(PersistenceUnitProperties.JDBC_URL, d.connection().url());
        props.put(PersistenceUnitProperties.JDBC_USER, nullToEmpty(d.connection().user()));
        props.put(PersistenceUnitProperties.JDBC_PASSWORD, nullToEmpty(d.connection().password()));
        props.put(PersistenceUnitProperties.CONNECTION_POOL_MIN, String.valueOf(d.connection().pool().min()));
        props.put(PersistenceUnitProperties.CONNECTION_POOL_INITIAL, String.valueOf(d.connection().pool().min()));
        props.put(PersistenceUnitProperties.CONNECTION_POOL_MAX, String.valueOf(d.connection().pool().max()));
        props.put(PersistenceUnitProperties.WEAVING, "false");
        if (d.connection().url().startsWith("jdbc:h2:")) {
            // EclipseLink 5.0's H2Platform reads identity values with CALL IDENTITY(), which H2 2.x removed.
            // Reading them through JDBC getGeneratedKeys works with both, so generated keys work on create.
            props.put(PersistenceUnitProperties.TARGET_DATABASE_PROPERTIES, "supportsReturnGeneratedKeys=true");
        }
        props.put(PersistenceUnitProperties.DDL_GENERATION, PersistenceUnitProperties.NONE);
        // Read-only server over a database that other applications change: do not serve stale rows from cache.
        props.put(PersistenceUnitProperties.CACHE_SHARED_DEFAULT, "false");
        props.put(PersistenceUnitProperties.QUERY_TIMEOUT, String.valueOf(d.limits().queryTimeoutSeconds() * 1000));
        props.put(PersistenceUnitProperties.QUERY_TIMEOUT_UNIT, "MILLISECONDS");
        // stdout belongs to the MCP stdio transport, so EclipseLink must log through java.util.logging (stderr).
        props.put(PersistenceUnitProperties.LOGGING_LOGGER, "JavaLogger");
        // Logging is off while deploying: a failed start is reported once, briefly, by the caller, where
        // EclipseLink would log a stack trace to stderr. It is set to WARNING once the model is up.
        props.put(PersistenceUnitProperties.LOGGING_LEVEL, "OFF");
        props.put(PersistenceUnitProperties.LOGGING_EXCEPTIONS, "false");

        Path root = definition.directory();
        String mappingFile = root.relativize(definition.mappingsFile()).toString().replace('\\', '/');
        UnitInfo info = new UnitInfo(definition.descriptor().server().name(), mappingFile, toUrl(root), dcl);

        EntityManagerFactory emf;
        try {
            emf = new PersistenceProvider().createContainerEntityManagerFactory(info, props);
            // Force deployment and a first connection now, so problems surface at startup.
            EntityManager em = emf.createEntityManager();
            em.close();
        } catch (PersistenceException | org.eclipse.persistence.exceptions.EclipseLinkException e) {
            closeQuietly(resourceLoader);
            throw new DefinitionException(startupFailure(d.connection().url(), mappingFile, e), e);
        }

        Server session = JpaHelper.getServerSession(emf);
        session.getSessionLog().setLevel(org.eclipse.persistence.logging.SessionLog.WARNING);
        Map<String, ClassDescriptor> entities = new TreeMap<>();
        for (ClassDescriptor descriptor : session.getDescriptors().values()) {
            if (descriptor.isAggregateDescriptor() || descriptor.getAlias() == null) {
                continue;
            }
            entities.put(descriptor.getAlias(), descriptor);
        }
        if (entities.isEmpty()) {
            emf.close();
            closeQuietly(resourceLoader);
            throw new DefinitionException(mappingFile + " defines no entities");
        }
        return new ModelRuntime(definition, drivers, resourceLoader, emf, Collections.unmodifiableMap(entities));
    }

    public ServerDefinition definition() {
        return definition;
    }

    /** JDBC drivers loaded from the deployment's drivers directory, if any. */
    public JdbcDrivers drivers() {
        return drivers;
    }

    /** Entities by name (JPA entity name), sorted. */
    public Map<String, ClassDescriptor> entities() {
        return entities;
    }

    public ClassDescriptor entity(String name) {
        return entities.get(name);
    }

    public EntityManager createEntityManager() {
        return emf.createEntityManager();
    }

    public EntityManagerFactory entityManagerFactory() {
        return emf;
    }

    public static String description(ClassDescriptor descriptor) {
        Object d = descriptor.getProperty(DESCRIPTION_PROPERTY);
        return d == null ? null : d.toString();
    }

    @Override
    public void close() {
        if (emf.isOpen()) {
            emf.close();
        }
        closeQuietly(resourceLoader);
        drivers.close();
    }

    /** A short message for a failed start, with a hint for the common H2 "file in use" case. */
    static String startupFailure(String url, String mappingFile, Throwable e) {
        String root = rootMessage(e);
        if (H2Urls.isEmbeddedFile(url) && fileInUse(e)) {
            return "The H2 database file is in use by another process, such as another copy of this server "
                    + "(MCP clients may start several)."
                    + (H2Urls.hasAutoServer(url) ? " Every process opening it must use ;AUTO_SERVER=TRUE."
                    : " Add ;AUTO_SERVER=TRUE to the JDBC URL so they can share it, e.g. "
                    + "jdbc:h2:file:/path/to/db;AUTO_SERVER=TRUE");
        }
        if (root.contains("\n")) {
            root = root.substring(0, root.indexOf('\n')).strip();
        }
        return "Could not start the model from " + mappingFile + ": " + root;
    }

    /** H2's "Database may be already in use" (90020), or its cause "The file is locked". */
    private static boolean fileInUse(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String m = String.valueOf(t.getMessage());
            if (m.contains("90020") || m.contains("already in use") || m.contains("file is locked")) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : message.strip();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    /** Minimal persistence unit: one mapping file, no managed classes, resource-local. */
    private record UnitInfo(String name, String mappingFile, URL root, ClassLoader loader)
            implements PersistenceUnitInfo {

        @Override public String getPersistenceUnitName() { return name; }
        @Override public String getPersistenceProviderClassName() { return PersistenceProvider.class.getName(); }
        @Override public String getScopeAnnotationName() { return null; }
        @Override public List<String> getQualifierAnnotationNames() { return List.of(); }
        @Override public PersistenceUnitTransactionType getTransactionType() { return PersistenceUnitTransactionType.RESOURCE_LOCAL; }
        @Override public DataSource getJtaDataSource() { return null; }
        @Override public DataSource getNonJtaDataSource() { return null; }
        @Override public List<String> getMappingFileNames() { return List.of(mappingFile); }
        @Override public List<URL> getJarFileUrls() { return List.of(); }
        @Override public URL getPersistenceUnitRootUrl() { return root; }
        @Override public List<String> getManagedClassNames() { return List.of(); }
        @Override public boolean excludeUnlistedClasses() { return true; }
        @Override public SharedCacheMode getSharedCacheMode() { return SharedCacheMode.NONE; }
        @Override public ValidationMode getValidationMode() { return ValidationMode.NONE; }
        @Override public Properties getProperties() { return new Properties(); }
        @Override public String getPersistenceXMLSchemaVersion() { return "3.2"; }
        @Override public ClassLoader getClassLoader() { return loader; }
        @Override public void addTransformer(ClassTransformer transformer) { }
        @Override public ClassLoader getNewTempClassLoader() { return loader; }
    }
}
