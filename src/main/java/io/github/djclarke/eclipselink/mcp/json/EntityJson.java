/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp.json;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.dynamic.DynamicEntity;
import org.eclipse.persistence.internal.helper.ConversionManager;
import org.eclipse.persistence.internal.helper.DatabaseField;
import org.eclipse.persistence.internal.indirection.DatabaseValueHolder;
import org.eclipse.persistence.internal.indirection.UnitOfWorkValueHolder;
import org.eclipse.persistence.internal.sessions.AbstractRecord;
import org.eclipse.persistence.mappings.DatabaseMapping;
import org.eclipse.persistence.mappings.OneToOneMapping;

import io.github.djclarke.eclipselink.mcp.query.AttributePath;
import io.github.djclarke.eclipselink.mcp.query.Mappings;

/**
 * Converts dynamic entities to JSON-ready maps using the model's mapping metadata (the default JSON binding).
 *
 * <p>Rules (docs/server-definition.md 4.3):
 * <ul>
 * <li>Without a selection: every basic attribute, plus each to-one relationship, nested while depth remains and
 * otherwise as a reference holding only the target's key. To-many relationships are left out unless selected.</li>
 * <li>With a selection ({@code select} paths): only the selected attributes, plus the key of every entity shown.</li>
 * <li>Selected to-many relationships are capped at a maximum number of elements.</li>
 * </ul>
 *
 * <p>POC note: this walks EclipseLink descriptors directly. Switching to MOXy bindings (so that OXM overrides
 * can rename or reshape output) is planned; the output shape here is the intended default.
 */
public final class EntityJson {

    private final int maxCollection;

    public EntityJson(int maxCollection) {
        this.maxCollection = maxCollection;
    }

    /** A tree of selected attribute names. A leaf (no children) means "this attribute, default shape". */
    public static final class Selection {
        final Map<String, Selection> children = new LinkedHashMap<>();
        private List<String> paths = List.of();

        /** The attribute paths this selection was parsed from. */
        public List<String> paths() {
            return paths;
        }

        public static Selection parse(ClassDescriptor root, List<String> paths) {
            if (paths == null || paths.isEmpty()) {
                return null;
            }
            Selection sel = new Selection();
            sel.paths = List.copyOf(paths);
            for (String p : paths) {
                AttributePath.resolve(root, p); // validates
                Selection node = sel;
                for (String segment : p.split("\\.")) {
                    node = node.children.computeIfAbsent(segment, k -> new Selection());
                }
            }
            return sel;
        }
    }

    public Map<String, Object> render(DynamicEntity entity, ClassDescriptor descriptor, Selection selection,
                                      int depth) {
        Map<String, Object> json = new LinkedHashMap<>();
        if (selection == null) {
            for (DatabaseMapping m : Mappings.of(descriptor)) {
                if (m.isDirectToFieldMapping()) {
                    json.put(m.getAttributeName(), value(entity.get(m.getAttributeName())));
                } else if (!m.isCollectionMapping()) {
                    if (depth > 0) {
                        DynamicEntity target = entity.get(m.getAttributeName());
                        json.put(m.getAttributeName(),
                                target == null ? null : render(target, m.getReferenceDescriptor(), null, depth - 1));
                    } else {
                        json.put(m.getAttributeName(), reference(entity, m));
                    }
                }
            }
            return json;
        }

        for (String key : Mappings.keyAttributes(descriptor)) {
            json.put(key, value(entity.get(key)));
        }
        for (Map.Entry<String, Selection> e : selection.children.entrySet()) {
            DatabaseMapping m = descriptor.getMappingForAttributeName(e.getKey());
            Selection child = e.getValue().children.isEmpty() ? null : e.getValue();
            if (m.isDirectToFieldMapping()) {
                json.put(m.getAttributeName(), value(entity.get(m.getAttributeName())));
            } else if (m.isCollectionMapping()) {
                Collection<?> targets = entity.get(m.getAttributeName());
                List<Object> list = new ArrayList<>();
                if (targets != null) {
                    for (Object t : targets) {
                        if (list.size() >= maxCollection) {
                            break;
                        }
                        list.add(render((DynamicEntity) t, m.getReferenceDescriptor(), child, 0));
                    }
                }
                json.put(m.getAttributeName(), list);
            } else {
                DynamicEntity target = entity.get(m.getAttributeName());
                json.put(m.getAttributeName(),
                        target == null ? null : render(target, m.getReferenceDescriptor(), child, 0));
            }
        }
        return json;
    }

    /**
     * The target's key, as {@code {"id": 7}}, or null. Read from the foreign key in the source row when the
     * relationship has not been loaded, so a reference never costs a lazy-load query (one per row otherwise).
     */
    private Map<String, Object> reference(DynamicEntity entity, DatabaseMapping m) {
        Map<String, Object> fromRow = foreignKeyReference(m.getAttributeValueFromObject(entity), m);
        if (fromRow != NOT_AVAILABLE) {
            return fromRow;
        }
        DynamicEntity target = entity.get(m.getAttributeName());
        if (target == null) {
            return null;
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        for (String key : Mappings.keyAttributes(m.getReferenceDescriptor())) {
            ref.put(key, value(target.get(key)));
        }
        return ref;
    }

    private static final Map<String, Object> NOT_AVAILABLE = java.util.Collections.unmodifiableMap(new LinkedHashMap<>());

    /** Key of an unloaded to-one target from the held source row; {@link #NOT_AVAILABLE} if it cannot be read. */
    private static Map<String, Object> foreignKeyReference(Object attributeValue, DatabaseMapping m) {
        Object holder = attributeValue;
        while (holder instanceof UnitOfWorkValueHolder<?> uow && !uow.isInstantiated()) {
            holder = uow.getWrappedValueHolder();
        }
        if (!(holder instanceof DatabaseValueHolder<?> vh) || vh.isInstantiated() || vh.getRow() == null
                || !(m instanceof OneToOneMapping o) || !o.isForeignKeyRelationship()) {
            return NOT_AVAILABLE;
        }
        ClassDescriptor target = m.getReferenceDescriptor();
        AbstractRecord row = vh.getRow();
        Map<String, Object> ref = new LinkedHashMap<>();
        boolean allNull = true;
        for (Map.Entry<DatabaseField, DatabaseField> e : o.getSourceToTargetKeyFields().entrySet()) {
            if (!row.containsKey(e.getKey())) {
                return NOT_AVAILABLE;
            }
            DatabaseMapping keyMapping = target.getObjectBuilder().getMappingForField(e.getValue());
            if (keyMapping == null || !keyMapping.isDirectToFieldMapping()
                    || !Mappings.isKey(target, keyMapping)) {
                return NOT_AVAILABLE;
            }
            Object raw = row.get(e.getKey());
            allNull &= raw == null;
            Class<?> type = Mappings.attributeType(keyMapping);
            Object converted = raw == null || type == null ? raw
                    : ConversionManager.getDefaultManager().convertObject(raw, type);
            ref.put(keyMapping.getAttributeName(), value(converted));
        }
        if (allNull) {
            return null;
        }
        // Keys in the target's declared order.
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String key : Mappings.keyAttributes(target)) {
            if (!ref.containsKey(key)) {
                return NOT_AVAILABLE;
            }
            ordered.put(key, ref.get(key));
        }
        return ordered;
    }

    /** JSON-friendly value: dates and times as ISO-8601 strings, binary as base64. */
    public static Object value(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof TemporalAccessor) {
            return v.toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().toString();
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant().toString();
        }
        if (v instanceof byte[] b) {
            return Base64.getEncoder().encodeToString(b);
        }
        return v.toString();
    }
}
