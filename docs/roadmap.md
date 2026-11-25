# Roadmap: tracked future work

Items agreed for later milestones, so they are not lost. Each one links to the spec section that describes it. When an item is scheduled, open a GitHub issue for it and link the issue here.

## Credentials (spec: [server-definition 5.3](server-definition.md#53-secrets-and-credentials))

Done in the POC: `${env:...}` and `${file:...}` references, the `SecretResolver` plugin interface (`ServiceLoader`), rejecting literal passwords, and masking the password in the descriptor's `toString`.

| # | Item | Milestone | Notes |
|---|---|---|---|
| C1 | `keychain` resolver: macOS Keychain, Windows Credential Manager, Linux Secret Service | Desktop | Removes the clear-text password from the client config. |
| C2 | `plugins/` folder and `--plugins <dir>` for resolver JARs, through a child classloader | Cloud packaging | Same pattern as `drivers/` (3.3). |
| C3 | `aws-sm` resolver: AWS Secrets Manager | Cloud packaging | Optional JAR. Authenticates with the workload's IAM role. |
| C4 | `azure-kv` resolver: Azure Key Vault | Cloud packaging | Managed identity. |
| C5 | `gcp-sm` resolver: GCP Secret Manager | Cloud packaging | Workload identity. |
| C6 | `oci-vault` resolver: OCI Vault | Cloud packaging | Instance or resource principal. |
| C7 | `vault` resolver: HashiCorp Vault | Cloud packaging | Kubernetes or AppRole authentication. |
| C8 | Token-based database login: RDS IAM, Azure AD (Azure SQL, PostgreSQL), Cloud SQL IAM | Cloud packaging | A token provider hook that fetches a fresh token as connections are opened. Also enables rotation without a restart. |
| C9 | Oracle Wallet | Docs | The driver reads a wallet from the JDBC URL (`TNS_ADMIN`). Document it, and add a sample. |
| C10 | Warn at startup when a `file` secret can be read by other users | Next | POSIX permissions check. |

## Packaging (spec: [server-definition 3.5](server-definition.md#35-building-packages-package))

| # | Item | Milestone | Notes |
|---|---|---|---|
| P1 | `package --as jar`: bundled JAR with runtime, definition and drivers | Packaging | No Java runtime included. |
| P2 | `package --as mcpb --target ...`: desktop bundle per platform, default Eclipse Temurin JRE 21 unchanged | Packaging | Connection settings as `user_config`; passwords `sensitive`. |
| P3 | `--jdk <target>=<dir>` (jlink) and `--runtime <target>=<dir>` overrides | Packaging | Any vendor, Java 21 or later. No GraalVM. |
| P4 | `--writes none\|definition` and the bundle's *Allow changes* setting, default off | Packaging | Narrows the definition's writes, never widens them. |
| P5 | `package --as image` on an `eclipse-temurin` base image | HTTP milestone | Built in H4, with the HTTP transport on Eclipse Jetty (5.4). |
| P6 | Subset form of `--writes` (`Order:update`) | Later | Checked against the definition at startup. |
| P7 | Trim the default runtime with `jlink` | Later | Roughly halves bundle size. |

## HTTP and OAuth (spec: [server-definition 5.4](server-definition.md#54-http-endpoint-and-oauth-later-milestone))

Phases in order; sizes are rough working days.

| # | Item | Size | Notes |
|---|---|---|---|
| H1 | Stateless streamable HTTP on embedded Jetty, health endpoints, Origin check, localhost-only without auth | 2 to 3 | Usable with MCP Inspector locally. |
| H2 | OAuth resource server: protected resource metadata, `401`/`403` challenges, JWT validation (Nimbus), audience, write scope per caller, caller in the audit | about 5 | Unit and integration tests with in-test keys. |
| H3 | Writable entities never shared-cached; two-instance `CONFLICT` test | 1 | Startup check against cache settings. |
| H4 | `package --as image` (P5) and `samples/hosted`: Compose with Keycloak realm import and PostgreSQL | 3 to 4 | `docker compose up` to a working OAuth setup. |
| H5 | Claude custom connector end to end; setup pages for Entra ID, Okta, Auth0, Keycloak | about 2 | Longer if a provider needs workarounds. |
| H6 | Later: opaque tokens (introspection), metrics, per-caller row filters and multitenancy (security model) | | |

## Other later milestones

See [requirements 4.6](requirements.md#46-later-milestones): writes beyond named write operations, multi-definition hosting, composite persistence units, HTTP transport, change notifications, and the security model (section 5). See [server-definition 4.8](server-definition.md#48-loading-related-data-and-json-shapes) for MOXy rendering of named query graphs, plus opt-in per-query binding sets.
