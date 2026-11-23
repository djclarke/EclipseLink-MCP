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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/** The secret resolvers available to a {@link DefinitionLoader}: built-in {@code env} and {@code file}, plus plugins. */
final class SecretResolvers {

    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Map<String, SecretResolver> byScheme = new TreeMap<>();

    SecretResolvers(Function<String, String> environment, ClassLoader plugins) {
        add(new Env(environment));
        add(new FileSecret());
        try {
            for (SecretResolver r : ServiceLoader.load(SecretResolver.class, plugins)) {
                add(r);
            }
        } catch (ServiceConfigurationError e) {
            throw new DefinitionException("Cannot load secret resolver plugins: " + e.getMessage(), e);
        }
    }

    private void add(SecretResolver r) {
        if (!SCHEME.matcher(r.scheme()).matches() || r.scheme().equals("definition")) {
            throw new DefinitionException("Secret resolver " + r.getClass().getName() + " has invalid scheme '"
                    + r.scheme() + "'");
        }
        SecretResolver previous = byScheme.putIfAbsent(r.scheme(), r);
        if (previous != null) {
            throw new DefinitionException("Two secret resolvers for scheme '" + r.scheme() + "': "
                    + previous.getClass().getName() + " and " + r.getClass().getName());
        }
    }

    SecretResolver get(String scheme) {
        SecretResolver r = byScheme.get(scheme);
        if (r == null) {
            throw new DefinitionException("Unknown reference scheme '${" + scheme + ":...}'; available: "
                    + String.join(", ", byScheme.keySet()));
        }
        return r;
    }

    /** {@code ${env:NAME}}: an environment variable. */
    private record Env(Function<String, String> environment) implements SecretResolver {
        @Override
        public String scheme() {
            return "env";
        }

        @Override
        public String resolve(String name, Path definitionDir) {
            if (!ENV_NAME.matcher(name).matches()) {
                throw new DefinitionException("Invalid environment variable name in ${env:" + name + "}");
            }
            return environment.apply(name);
        }
    }

    /**
     * {@code ${file:PATH}}: the content of a file, such as a Docker or Kubernetes secret mounted at
     * {@code /run/secrets/...}. Relative paths resolve against the definition directory. Trailing line breaks
     * are removed, since secret files usually end with one.
     */
    private static final class FileSecret implements SecretResolver {
        @Override
        public String scheme() {
            return "file";
        }

        @Override
        public String resolve(String reference, Path definitionDir) {
            if (reference.isBlank()) {
                throw new DefinitionException("${file:} needs a path");
            }
            Path file = definitionDir.resolve(reference).normalize();
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try {
                return Files.readString(file, StandardCharsets.UTF_8).replaceAll("[\\r\\n]+\\z", "");
            } catch (IOException e) {
                // The message names the file, never its content.
                throw new DefinitionException("Cannot read secret file " + file, e);
            }
        }
    }
}
