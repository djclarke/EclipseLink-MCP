/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.internal.helper.ConversionManager;
import org.eclipse.persistence.mappings.DatabaseMapping;
import org.eclipse.persistence.mappings.DirectToFieldMapping;

import io.github.djclarke.eclipselink.mcp.model.ModelRuntime;
import io.github.djclarke.eclipselink.mcp.tools.ToolException;

/**
 * Helpers over EclipseLink mapping metadata: which mappings the POC supports, their types, JSON types and
 * conversion of incoming JSON values.
 */
public final class Mappings {

    private Mappings() {
    }

    /** Basic columns and entity relationships. Embeddables, element collections etc. are not supported yet. */
    public static boolean isSupported(DatabaseMapping m) {
        return m.isDirectToFieldMapping()
                || (m.isForeignReferenceMapping() && (m.isOneToOneMapping() || m.isOneToManyMapping()
                    || m.isManyToManyMapping()));
    }

    /** Supported mappings of an entity, in declaration order. */
    public static List<DatabaseMapping> of(ClassDescriptor descriptor) {
        List<DatabaseMapping> result = new ArrayList<>();
        for (DatabaseMapping m : descriptor.getMappings()) {
            if (isSupported(m)) {
                result.add(m);
            }
        }
        return result;
    }

    public static List<String> keyAttributes(ClassDescriptor descriptor) {
        List<String> keys = new ArrayList<>();
        for (DatabaseMapping m : descriptor.getObjectBuilder().getPrimaryKeyMappings()) {
            keys.add(m.getAttributeName());
        }
        return keys;
    }

    public static boolean isKey(ClassDescriptor descriptor, DatabaseMapping m) {
        return descriptor.getObjectBuilder().getPrimaryKeyMappings().contains(m);
    }

    public static String description(DatabaseMapping m) {
        Object d = m.getProperty(ModelRuntime.DESCRIPTION_PROPERTY);
        return d == null ? null : d.toString();
    }

    public static Class<?> attributeType(DatabaseMapping m) {
        if (m instanceof DirectToFieldMapping d) {
            Class<?> c = d.getAttributeClassification();
            if (c == null && d.getField() != null) {
                c = d.getField().getType();
            }
            return c == null ? Object.class : c;
        }
        return Object.class;
    }

    /** JSON Schema for one basic attribute's value. */
    public static Map<String, Object> jsonSchema(Class<?> type) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (type == String.class || type == Character.class || type == char.class) {
            s.put("type", "string");
        } else if (type == Integer.class || type == int.class || type == Long.class || type == long.class
                || type == Short.class || type == short.class || type == Byte.class || type == byte.class
                || type == BigInteger.class) {
            s.put("type", "integer");
        } else if (type == BigDecimal.class || type == Double.class || type == double.class
                || type == Float.class || type == float.class) {
            s.put("type", "number");
        } else if (type == Boolean.class || type == boolean.class) {
            s.put("type", "boolean");
        } else if (type == LocalDate.class || type == java.sql.Date.class) {
            s.put("type", "string");
            s.put("format", "date");
        } else if (type == LocalDateTime.class || type == OffsetDateTime.class || type == java.sql.Timestamp.class
                || type == java.util.Date.class || type == java.util.Calendar.class) {
            s.put("type", "string");
            s.put("format", "date-time");
        } else if (type == LocalTime.class || type == java.sql.Time.class) {
            s.put("type", "string");
            s.put("format", "time");
        } else {
            s.put("type", "string");
        }
        return s;
    }

    /** Short type name shown to agents in describe_entity. */
    public static String typeName(Class<?> type) {
        Object t = jsonSchema(type).get("format");
        return t != null ? t.toString() : jsonSchema(type).get("type").toString();
    }

    /** Converts a JSON value from a tool call to the attribute's Java type. */
    public static Object convert(Object value, Class<?> type, String path) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map || value instanceof List) {
            throw new ToolException(ToolException.Code.INVALID_FILTER,
                    "'" + path + "' needs a single value, not an object or list");
        }
        try {
            if (type == LocalDate.class) {
                return LocalDate.parse(value.toString());
            }
            if (type == LocalDateTime.class) {
                return LocalDateTime.parse(value.toString());
            }
            return ConversionManager.getDefaultManager().convertObject(value, type);
        } catch (RuntimeException e) {
            throw new ToolException(ToolException.Code.INVALID_FILTER,
                    "'" + path + "': cannot use " + value + " as " + typeName(type));
        }
    }
}
