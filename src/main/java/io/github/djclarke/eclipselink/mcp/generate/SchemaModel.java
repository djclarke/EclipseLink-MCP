/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp.generate;

import java.util.List;

/** The part of a database schema a {@link TableSelection} selected, as read from JDBC metadata. */
public record SchemaModel(List<Table> tables, List<String> warnings) {

    public record Table(String catalog, String schema, String name, String type, String remarks,
                        List<Column> columns, List<String> primaryKey, List<ForeignKey> foreignKeys) {

        public String qualifiedName() {
            return schema == null ? name : schema + "." + name;
        }

        public Column column(String columnName) {
            return columns.stream().filter(c -> c.name().equals(columnName)).findFirst().orElse(null);
        }
    }

    /**
     * @param jdbcType a {@link java.sql.Types} constant
     * @param autoIncrement the database fills the value in (JDBC {@code IS_AUTOINCREMENT}), e.g. an identity
     */
    public record Column(String name, int jdbcType, String typeName, int size, int scale, boolean nullable,
                         String remarks, boolean autoIncrement) {
    }

    /** A foreign key of this table, by position: {@code columns.get(i)} references {@code targetColumns.get(i)}. */
    public record ForeignKey(String name, List<String> columns, String targetSchema, String targetTable,
                             List<String> targetColumns) {
    }
}
