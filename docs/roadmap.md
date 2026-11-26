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

## Entity kinds and multitenancy (spec: [server-definition 2.8](server-definition.md#28-entity-kinds-reference-external-and-writable) and [2.9](server-definition.md#29-multitenancy))

| # | Item | Milestone | Notes |
|---|---|---|---|
| K1 | `mcp.kind` and `edit set-kind`; the runtime maps kinds to EclipseLink read-only and cache settings; `describe_entity` reports the kind | Next | Default `external` keeps today's behavior. |
| K2 | Startup and `edit` rules: `tools.writes` only on writable entities; reference entities refer only to reference entities; `copy` reads its source from the database | Next | |
| K3 | `edit multitenant` (single table, table per tenant) and descriptor `tenancy.tenant` for stdio, with the relationship rules | After K1 | Samples: a tenant column added to the order desk, with PRODUCT shared; in AdventureWorks, `tenant_id` on customers, orders and lines per territory group (S1). Tests prove one tenant never sees, or writes, another's rows. |
| K4 | `tenancy.claim` from the validated token | With H2 (OAuth) | Needs the OAuth resource server. |
| K5 | Desktop bundle asks for the tenant (`user_config`) | With P2 | |
| K6 | Skill step: classify each entity's kind and tenancy | With K1 | Written in this spec change; commands land with K1 and K3. |

## Samples (see [samples/README.md](../samples/README.md))

| # | Item | Milestone | Notes |
|---|---|---|---|
| S1 | AdventureWorks multitenancy: one tenant per territory group (North America, Europe, Pacific), product catalogue shared | With K3 | `db/overlay.sql` adds and fills the column; `shape.sh` adds `edit multitenant`. |
| S2 | AdventureWorks entity kinds: catalogue and lookup tables `reference`, products `external`, orders `writable` | With K1 | `shape.sh` adds `edit set-kind`. |
| S3 | AdventureWorks history: an EclipseLink-maintained history of sales orders for as-of reads | With history support | Its own effective-dated tables are queryable today. |
| S4 | Publish the AdventureWorks database image (for example to GitHub Container Registry) so `docker compose up` needs no build | Packaging | Built by CI from `db/`. |
| S5 | Run the AdventureWorks tests in CI against the image | With S4 | Today they run when `AW_DB_URL` is set. |
| S6 | AdventureWorks over HTTP with OAuth: a Keycloak realm with a reader and an order entry user, only the second holding `adventureworks:write` | With H2 | One deployment; on stdio today, `--writes none` gives the read-only view. |

## Other later milestones

See [requirements 4.6](requirements.md#46-later-milestones): writes beyond named write operations, multi-definition hosting, composite persistence units, HTTP transport, change notifications, and the security model (section 5). See [server-definition 4.8](server-definition.md#48-loading-related-data-and-json-shapes) for MOXy rendering of named query graphs, plus opt-in per-query binding sets.
