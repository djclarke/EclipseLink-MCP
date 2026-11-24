/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp.write;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;

import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.dynamic.DynamicEntity;
import org.eclipse.persistence.exceptions.DatabaseException;
import org.eclipse.persistence.exceptions.OptimisticLockException;
import org.eclipse.persistence.expressions.Expression;
import org.eclipse.persistence.expressions.ExpressionBuilder;
import org.eclipse.persistence.jpa.JpaHelper;
import org.eclipse.persistence.mappings.DatabaseMapping;
import org.eclipse.persistence.mappings.OneToOneMapping;
import org.eclipse.persistence.queries.ReadAllQuery;

import io.github.djclarke.eclipselink.mcp.definition.DefinitionException;
import io.github.djclarke.eclipselink.mcp.definition.WriteDefinition;
import io.github.djclarke.eclipselink.mcp.json.EntityJson;
import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.query.AttributePath;
import io.github.djclarke.eclipselink.mcp.query.Filters;
import io.github.djclarke.eclipselink.mcp.query.Mappings;
import io.github.djclarke.eclipselink.mcp.tools.ToolException;
import io.github.djclarke.eclipselink.mcp.tools.ToolException.Code;

/**
 * A named write operation checked against the model at startup (docs/server-definition.md 2.7, 4.3, 4.9). Each
 * call is one transaction that changes one root entity, checks the caller's version against the entity's lock,
 * checks the operation's condition, and increments the version, so concurrent writers cannot overwrite each
 * other's changes. The server keeps no state between calls: versions travel in the JSON.
 */
public final class NamedWrite {

    public enum Action { CREATE, UPDATE, DELETE }

    /** An attribute the caller supplies, or the server sets. */
    private record Field(DatabaseMapping mapping, boolean required, List<Object> allowed, String description) {
        boolean isRelationship() {
            return !mapping.isDirectToFieldMapping();
        }
    }

    private final WriteDefinition definition;
    private final ClassDescriptor root;
    private final Action action;
    private final Lock lock;
    private final List<String> keyInputs;
    private final Map<String, Field> set;
    private final Map<DatabaseMapping, Object> fixed;
    private final Map<DatabaseMapping, AttributePath> copy;

    private NamedWrite(WriteDefinition definition, ClassDescriptor root, Action action, Lock lock,
                       List<String> keyInputs, Map<String, Field> set, Map<DatabaseMapping, Object> fixed,
                       Map<DatabaseMapping, AttributePath> copy) {
        this.definition = definition;
        this.root = root;
        this.action = action;
        this.lock = lock;
        this.keyInputs = keyInputs;
        this.set = set;
        this.fixed = fixed;
        this.copy = copy;
    }

    /** Validates the operation against the model (spec 6, item 5b). */
    public static NamedWrite compile(WriteDefinition def, ModelRuntime model) {
        String where = "Write operation '" + def.name() + "': ";
        ClassDescriptor root = model.entity(def.root());
        if (root == null) {
            throw new DefinitionException(where + "unknown root entity '" + def.root() + "'");
        }
        Action action = Action.valueOf(def.action().toUpperCase(java.util.Locale.ROOT));
        Lock lock = Lock.of(root);
        if (lock == null && def.locking() == null) {
            throw new DefinitionException(where + root.getAlias() + " has no optimistic lock. Give it a version "
                    + "(edit set-version) or lock it through a relationship (edit lock-through), or declare "
                    + "'locking: none' to accept that the last write wins");
        }
        if (lock != null && def.locking() != null) {
            throw new DefinitionException(where + root.getAlias() + " is locked by " + lock.describe()
                    + "; remove 'locking: none'");
        }
        try {
            Map<String, Field> set = new LinkedHashMap<>();
            for (Map.Entry<String, WriteDefinition.Field> e : def.set().entrySet()) {
                WriteDefinition.Field f = e.getValue() == null ? new WriteDefinition.Field(null, null, null)
                        : e.getValue();
                DatabaseMapping m = settable(root, e.getKey(), lock, action, where);
                List<Object> allowed = null;
                if (f.allowed() != null) {
                    if (!m.isDirectToFieldMapping()) {
                        throw new DefinitionException(where + "'allowed' applies to basic attributes, not "
                                + e.getKey());
                    }
                    allowed = new ArrayList<>();
                    for (Object v : f.allowed()) {
                        allowed.add(Mappings.convert(v, Mappings.attributeType(m), e.getKey()));
                    }
                }
                set.put(e.getKey(), new Field(m, f.required(), allowed, f.description()));
            }
            Map<DatabaseMapping, Object> fixed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : def.fixed().entrySet()) {
                if (set.containsKey(e.getKey())) {
                    throw new DefinitionException(where + "'" + e.getKey() + "' is under both set and fixed");
                }
                DatabaseMapping m = settable(root, e.getKey(), lock, action, where);
                fixed.put(m, m.isDirectToFieldMapping()
                        ? Mappings.convert(e.getValue(), Mappings.attributeType(m), e.getKey()) : e.getValue());
            }
            if (action == Action.CREATE && lock != null && lock.through() != null
                    && !set.containsKey(lock.through().getAttributeName()) && !fixed.containsKey(lock.through())) {
                throw new DefinitionException(where + root.getAlias() + " is locked through '"
                        + lock.through().getAttributeName() + "', so a create must set it");
            }
            if (def.condition() != null) {
                Filters.toExpression(def.condition(), root, new ExpressionBuilder(), Set.of(), null,
                        new ArrayList<>());
            }
            List<String> keyInputs = action == Action.CREATE && !root.usesSequenceNumbers()
                    ? Mappings.keyAttributes(root) : List.of();
            for (String k : keyInputs) {
                if (!root.getMappingForAttributeName(k).isDirectToFieldMapping()) {
                    throw new DefinitionException(where + "creating " + root.getAlias()
                            + " needs a key of basic attributes; '" + k + "' is a relationship");
                }
                if (set.containsKey(k)) {
                    throw new DefinitionException(where + "key '" + k + "' is always caller input; remove it from set");
                }
            }
            Map<DatabaseMapping, AttributePath> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : def.copy().entrySet()) {
                if (set.containsKey(e.getKey()) || def.fixed().containsKey(e.getKey())) {
                    throw new DefinitionException(where + "'" + e.getKey() + "' is copied, so it cannot also be "
                            + "under set or fixed");
                }
                DatabaseMapping target = settable(root, e.getKey(), lock, action, where);
                if (!target.isDirectToFieldMapping()) {
                    throw new DefinitionException(where + "copy target '" + e.getKey() + "' must be a basic attribute");
                }
                AttributePath source = AttributePath.resolve(root, e.getValue());
                DatabaseMapping via = source.mappings().get(0);
                if (source.mappings().size() < 2 || !source.isBasic() || source.traversesCollection()
                        || (!set.containsKey(via.getAttributeName()) && !fixed.containsKey(via))) {
                    throw new DefinitionException(where + "copy source '" + e.getValue() + "' must be a basic "
                            + "attribute reached through a to-one relationship under set or fixed");
                }
                if (!wrapper(source.type()).equals(wrapper(Mappings.attributeType(target)))) {
                    throw new DefinitionException(where + "cannot copy " + e.getValue() + " ("
                            + source.type().getSimpleName() + ") into " + e.getKey() + " ("
                            + Mappings.attributeType(target).getSimpleName() + ")");
                }
                copy.put(target, source);
            }
            return new NamedWrite(def, root, action, lock, keyInputs, set, fixed, copy);
        } catch (ToolException e) {
            throw new DefinitionException(where + e.getMessage(), e);
        }
    }

    private static Class<?> wrapper(Class<?> c) {
        return java.lang.invoke.MethodType.methodType(c).wrap().returnType();
    }

    private static DatabaseMapping settable(ClassDescriptor root, String name, Lock lock, Action action,
                                            String where) {
        DatabaseMapping m = root.getMappingForAttributeName(name);
        if (m == null || !Mappings.isSupported(m)) {
            throw new DefinitionException(where + root.getAlias() + " has no attribute '" + name + "'");
        }
        if (Mappings.isKey(root, m)) {
            throw new DefinitionException(where + "'" + name + "' is part of the key and cannot be set");
        }
        if (name.equals(Mappings.versionAttribute(root))) {
            throw new DefinitionException(where + "'" + name + "' is the version; the server maintains it");
        }
        if (m.isCollectionMapping() || (!m.isDirectToFieldMapping()
                && !(m instanceof OneToOneMapping o && o.isForeignKeyRelationship()))) {
            throw new DefinitionException(where + "'" + name + "' must be a basic attribute or an owning to-one "
                    + "relationship");
        }
        if (m.isReadOnly() || (m.getField() != null && (!m.getField().isUpdatable() && action == Action.UPDATE))) {
            throw new DefinitionException(where + "'" + name + "' is read-only in the mapping");
        }
        if (action == Action.UPDATE && lock != null && lock.through() == m) {
            throw new DefinitionException(where + root.getAlias() + " is locked through '" + name
                    + "', which an update cannot change");
        }
        return m;
    }

    public WriteDefinition definition() {
        return definition;
    }

    public Action action() {
        return action;
    }

    public ClassDescriptor root() {
        return root;
    }

    /** The entity's lock, or null with {@code locking: none}. */
    public Lock lock() {
        return lock;
    }

    /** Tool description: the definition's own, then what the call needs and does. */
    public String toolDescription() {
        StringBuilder sb = new StringBuilder();
        if (definition.description() != null) {
            sb.append(definition.description().strip()).append(' ');
        }
        sb.append(switch (action) {
            case CREATE -> "Creates one " + root.getAlias() + ".";
            case UPDATE -> "Changes one " + root.getAlias() + ".";
            case DELETE -> "Deletes one " + root.getAlias() + ".";
        });
        if (lock == null) {
            sb.append(" No optimistic lock: the last write wins.");
        } else if (lock.through() == null) {
            if (action != Action.CREATE) {
                sb.append(" Pass the '").append(lock.inputName()).append("' you read; on CONFLICT read it again.");
            }
        } else {
            sb.append(" Pass '").append(lock.inputName()).append("', the version of the ").append(lock.holder().getAlias())
                    .append(" it belongs to; the call checks it and returns the new one in 'locked'.");
        }
        if (!copy.isEmpty()) {
            List<String> c = new ArrayList<>();
            copy.forEach((m, p) -> c.add(m.getAttributeName() + " from " + p.text()));
            sb.append(" Copies ").append(String.join(", ", c)).append(" when the write is made, so they do not "
                    + "change later.");
        }
        if (!fixed.isEmpty()) {
            List<String> f = new ArrayList<>();
            fixed.forEach((m, v) -> f.add(m.getAttributeName() + "=" + EntityJson.value(v)));
            sb.append(" Sets ").append(String.join(", ", f)).append('.');
        }
        if (definition.condition() != null) {
            sb.append(" Only when: ").append(io.github.djclarke.eclipselink.mcp.tools.Json.write(definition.condition()))
                    .append('.');
        }
        return sb.toString();
    }

    public Map<String, Object> inputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (action == Action.CREATE) {
            for (String k : keyInputs) {
                props.put(k, schemaOf(root.getMappingForAttributeName(k), null, "Key of the new " + root.getAlias()));
                required.add(k);
            }
        } else {
            props.put("id", Map.of("description", "Key of the " + root.getAlias()
                    + ": a value, or an object of key attributes"));
            required.add("id");
        }
        if (lock != null && (action != Action.CREATE || lock.through() != null)) {
            Map<String, Object> s = new LinkedHashMap<>(Mappings.jsonSchema(lock.versionType()));
            s.put("description", lock.through() == null
                    ? "The version of the " + root.getAlias() + " as you read it"
                    : "The version of the " + lock.holder().getAlias() + " as you read it");
            props.put(lock.inputName(), s);
            required.add(lock.inputName());
        }
        for (Map.Entry<String, Field> e : set.entrySet()) {
            Field f = e.getValue();
            props.put(e.getKey(), schemaOf(f.mapping(), f.allowed(), f.description()));
            if (f.required()) {
                required.add(e.getKey());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> schemaOf(DatabaseMapping m, List<Object> allowed, String description) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (m.isDirectToFieldMapping()) {
            s.putAll(Mappings.jsonSchema(m));
            if (allowed != null) {
                s.put("enum", allowed.stream().map(EntityJson::value).toList());
            }
            String d = description != null ? description : Mappings.description(m);
            if (d != null) {
                s.put("description", d);
            }
        } else {
            ClassDescriptor target = m.getReferenceDescriptor();
            List<String> keys = Mappings.keyAttributes(target);
            if (keys.size() == 1) {
                s.putAll(Mappings.jsonSchema(target.getMappingForAttributeName(keys.get(0))));
            } else {
                s.put("type", "object");
            }
            s.put("description", (description == null ? "" : description + ". ") + "Key of the "
                    + target.getAlias());
        }
        return s;
    }

    /**
     * Runs the operation in its own transaction.
     *
     * @param dryRun flush (so the database checks constraints and versions), then roll back
     */
    public Map<String, Object> run(EntityManager em, Map<String, Object> args, EntityJson json, boolean dryRun) {
        checkArguments(args);
        em.getTransaction().begin();
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("operation", definition.name());
            out.put("action", action.name().toLowerCase(java.util.Locale.ROOT));
            out.put("entity", root.getAlias());
            DynamicEntity holder;
            switch (action) {
                case CREATE -> {
                    DynamicEntity entity = create(em, args);
                    holder = lockHolderOf(entity);
                    checkVersion(holder, args);
                    forceIncrement(em, holder);
                    em.persist(entity);
                    em.flush();
                    checkCondition(em, Mappings.keyValue(root, entity));
                    out.put("item", json.render(entity, root, null, 0));
                }
                case UPDATE -> {
                    DynamicEntity entity = find(em, root, args.get("id"), "id");
                    holder = lockHolderOf(entity);
                    checkVersion(holder, args);
                    checkCondition(em, args.get("id"));
                    apply(em, entity, args);
                    forceIncrement(em, holder);
                    em.flush();
                    out.put("item", json.render(entity, root, null, 0));
                }
                default -> {
                    DynamicEntity entity = find(em, root, args.get("id"), "id");
                    holder = lockHolderOf(entity);
                    checkVersion(holder, args);
                    checkCondition(em, args.get("id"));
                    forceIncrement(em, holder);
                    em.remove(entity);
                    em.flush();
                    out.put("id", Mappings.keyValue(root, entity));
                    out.put("deleted", true);
                }
            }
            if (lock != null && lock.through() != null && holder != null) {
                Map<String, Object> locked = new LinkedHashMap<>();
                locked.put("entity", lock.holder().getAlias());
                locked.put("id", Mappings.keyValue(lock.holder(), holder));
                locked.put(lock.versionAttribute(), EntityJson.value(holder.get(lock.versionAttribute())));
                out.put("locked", locked);
            }
            if (dryRun) {
                out.put("dryRun", true);
                em.getTransaction().rollback();
            } else {
                em.getTransaction().commit();
            }
            return out;
        } catch (PersistenceException | DatabaseException | OptimisticLockException e) {
            throw translate(e);
        } finally {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
        }
    }

    private void checkArguments(Map<String, Object> args) {
        List<String> known = new ArrayList<>(keyInputs);
        if (action != Action.CREATE) {
            known.add("id");
        }
        if (lock != null && (action != Action.CREATE || lock.through() != null)) {
            known.add(lock.inputName());
        }
        known.addAll(set.keySet());
        for (String a : args.keySet()) {
            if (!known.contains(a)) {
                throw new ToolException(Code.INVALID_PARAMETER, "Unknown parameter '" + a + "'; "
                        + definition.name() + " takes " + known);
            }
        }
        for (String k : keyInputs) {
            requireValue(args, k);
        }
        if (action != Action.CREATE) {
            requireValue(args, "id");
        }
        if (lock != null && (action != Action.CREATE || lock.through() != null)) {
            requireValue(args, lock.inputName());
        }
        set.forEach((name, f) -> {
            if (f.required()) {
                requireValue(args, name);
            }
        });
    }

    private static void requireValue(Map<String, Object> args, String name) {
        if (args.get(name) == null) {
            throw new ToolException(Code.INVALID_PARAMETER, "'" + name + "' is required");
        }
    }

    private DynamicEntity create(EntityManager em, Map<String, Object> args) {
        DynamicEntity entity = (DynamicEntity) root.getObjectBuilder().buildNewInstance();
        if (!keyInputs.isEmpty()) {
            Map<String, Object> id = new LinkedHashMap<>();
            for (String k : keyInputs) {
                Object v = Mappings.convert(args.get(k), Mappings.attributeType(root.getMappingForAttributeName(k)), k);
                entity.set(k, v);
                id.put(k, args.get(k));
            }
            Object key = keyInputs.size() == 1 ? args.get(keyInputs.get(0)) : id;
            if (!query(em, root, key, null).isEmpty()) {
                throw new ToolException(Code.ALREADY_EXISTS, root.getAlias() + " " + key + " already exists");
            }
        }
        apply(em, entity, args);
        return entity;
    }

    /** Sets the caller's attributes that are present, then the fixed values. */
    private void apply(EntityManager em, DynamicEntity entity, Map<String, Object> args) {
        for (Map.Entry<String, Field> e : set.entrySet()) {
            if (!args.containsKey(e.getKey())) {
                continue;
            }
            Field f = e.getValue();
            Object raw = args.get(e.getKey());
            entity.set(e.getKey(), resolve(em, f.mapping(), raw, f.allowed()));
        }
        for (Map.Entry<DatabaseMapping, Object> e : fixed.entrySet()) {
            DatabaseMapping m = e.getKey();
            entity.set(m.getAttributeName(), m.isDirectToFieldMapping() ? e.getValue()
                    : resolve(em, m, e.getValue(), null));
        }
        // Snapshots: copied when the relationship is set (always on create), never refreshed afterwards.
        for (Map.Entry<DatabaseMapping, AttributePath> e : copy.entrySet()) {
            DatabaseMapping via = e.getValue().mappings().get(0);
            if (action != Action.CREATE && !args.containsKey(via.getAttributeName()) && !fixed.containsKey(via)) {
                continue;
            }
            Object value = entity;
            for (DatabaseMapping m : e.getValue().mappings()) {
                value = value == null ? null : ((DynamicEntity) value).get(m.getAttributeName());
            }
            entity.set(e.getKey().getAttributeName(), value);
        }
    }

    private Object resolve(EntityManager em, DatabaseMapping m, Object raw, List<Object> allowed) {
        String name = m.getAttributeName();
        if (raw == null) {
            return null;
        }
        if (!m.isDirectToFieldMapping()) {
            return find(em, m.getReferenceDescriptor(), raw, name);
        }
        Object value = Mappings.convert(raw, Mappings.attributeType(m), name);
        if (allowed != null && !allowed.contains(value)) {
            throw new ToolException(Code.INVALID_PARAMETER, "'" + name + "' must be one of "
                    + allowed.stream().map(EntityJson::value).toList());
        }
        return value;
    }

    private DynamicEntity lockHolderOf(DynamicEntity entity) {
        if (lock == null) {
            return null;
        }
        return lock.through() == null ? entity : entity.get(lock.through().getAttributeName());
    }

    /** Compares the caller's version with the lock holder's current one (spec 4.9 step 2). */
    private void checkVersion(DynamicEntity holder, Map<String, Object> args) {
        if (lock == null || holder == null || (action == Action.CREATE && lock.through() == null)) {
            return;
        }
        Object expected = Mappings.convert(args.get(lock.inputName()), lock.versionType(), lock.inputName());
        Object current = holder.get(lock.versionAttribute());
        if (!Objects.equals(asLong(expected), asLong(current))) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("entity", lock.holder().getAlias());
            details.put("id", Mappings.keyValue(lock.holder(), holder));
            details.put("expectedVersion", expected);
            details.put("currentVersion", current);
            throw new ToolException(Code.CONFLICT, lock.holder().getAlias() + " "
                    + Mappings.keyValue(lock.holder(), holder) + " has changed since you read it (version " + current
                    + ", you sent " + expected + "). Read it again, check the change still makes sense with the "
                    + "new data, and retry with " + lock.inputName() + " " + current + ".", details);
        }
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    /** A lock held through a relationship is incremented, so the parent records every change to its children. */
    private void forceIncrement(EntityManager em, DynamicEntity holder) {
        if (lock != null && lock.through() != null && holder != null) {
            em.lock(holder, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        }
    }

    private void checkCondition(EntityManager em, Object id) {
        if (definition.condition() == null) {
            return;
        }
        if (query(em, root, id, definition.condition()).isEmpty()) {
            throw new ToolException(Code.PRECONDITION_FAILED, root.getAlias() + " " + id
                    + " does not meet this operation's condition "
                    + io.github.djclarke.eclipselink.mcp.tools.Json.write(definition.condition()));
        }
    }

    private static DynamicEntity find(EntityManager em, ClassDescriptor d, Object id, String what) {
        List<?> found = query(em, d, id, null);
        if (found.isEmpty()) {
            throw new ToolException(Code.NOT_FOUND, d.getAlias() + " " + id + " not found"
                    + (what.equals("id") ? "" : " (for '" + what + "')"));
        }
        return (DynamicEntity) found.get(0);
    }

    private static List<?> query(EntityManager em, ClassDescriptor d, Object id, Object condition) {
        ExpressionBuilder eb = new ExpressionBuilder();
        Expression where = Mappings.keyExpression(d, eb, id);
        if (condition != null) {
            List<AttributePath> used = new ArrayList<>();
            where = where.and(Filters.toExpression(condition, d, eb, Set.of(), null, used));
        }
        ReadAllQuery q = new ReadAllQuery(d.getJavaClass(), eb);
        q.setSelectionCriteria(where);
        return JpaHelper.createQuery(q, em).setMaxResults(1).getResultList();
    }

    /** Database failures as agent-facing codes, without SQL (spec 4.9). Unknown ones go to DATABASE_ERROR. */
    private RuntimeException translate(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof OptimisticLockException || t instanceof jakarta.persistence.OptimisticLockException) {
                String holder = lock == null ? root.getAlias() : lock.holder().getAlias();
                return new ToolException(Code.CONFLICT, holder + " was changed by another writer while this call "
                        + "ran. Read it again, check the change still makes sense, and retry with the new version.");
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState() == null ? "" : sql.getSQLState();
                if (state.equals("42501") || sql.getErrorCode() == 90096) {
                    return new ToolException(Code.PERMISSION_DENIED, "The server's database account is not allowed "
                            + "to make this change; the data owner must grant it");
                }
                if (state.startsWith("23")) {
                    return new ToolException(Code.CONSTRAINT_VIOLATION, "The database refused the change: it breaks "
                            + "a constraint (not null, unique, foreign key or check). SQLState " + state);
                }
                if (state.startsWith("22")) {
                    return new ToolException(Code.INVALID_PARAMETER, "A value does not fit its column (too long, "
                            + "out of range or wrong format). SQLState " + state);
                }
            }
        }
        return e;
    }
}
