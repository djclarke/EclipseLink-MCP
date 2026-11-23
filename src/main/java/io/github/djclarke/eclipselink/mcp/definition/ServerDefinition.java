/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */
package io.github.djclarke.eclipselink.mcp.definition;

import java.nio.file.Path;
import java.util.List;

/**
 * A loaded server definition: the descriptor plus its reports, with paths resolved against its directory.
 */
public record ServerDefinition(Path directory, Descriptor descriptor, List<ReportDefinition> reports) {

    public Path mappingsFile() {
        return directory.resolve(descriptor.model().mappings()).normalize();
    }
}
