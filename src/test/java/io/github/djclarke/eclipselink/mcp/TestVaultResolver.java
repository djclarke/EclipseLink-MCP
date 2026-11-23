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

import java.nio.file.Path;

import io.github.djclarke.eclipselink.mcp.definition.SecretResolver;

/** A plugin secret resolver, registered in the test classpath's META-INF/services, standing in for a vault. */
public final class TestVaultResolver implements SecretResolver {

    @Override
    public String scheme() {
        return "test-vault";
    }

    @Override
    public String resolve(String reference, Path definitionDir) {
        return reference.equals("sales/db-password") ? "from-vault" : null;
    }
}
