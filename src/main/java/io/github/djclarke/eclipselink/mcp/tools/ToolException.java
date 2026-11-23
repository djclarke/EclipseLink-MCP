/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp.tools;

/**
 * A tool call failed in a way the agent can act on. Returned to the client as a tool result with
 * {@code isError: true}; see docs/server-definition.md section 4.2 for the codes.
 */
public class ToolException extends RuntimeException {

    public enum Code {
        UNKNOWN_ENTITY, UNKNOWN_ATTRIBUTE, INVALID_FILTER, INVALID_PARAMETER, NOT_FOUND, LIMIT_EXCEEDED, TIMEOUT,
        DATABASE_ERROR
    }

    private final Code code;

    public ToolException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
