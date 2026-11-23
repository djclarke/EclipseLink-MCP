# EclipseLink MCP: Requirements

Status: draft for discussion.

## 1. Problem

Teams want AI agents to reach data in existing (often legacy) relational databases. Most database MCP servers hand the model a SQL connection. That is easy to build but weak on safety (any query the credentials allow), weak on grounding (tables and columns, little meaning), and hard to scope per caller.

## 2. Goal

EclipseLink MCP is a **factory for MCP servers**, not one server. A single generic runtime is configured per deployment, and each configuration produces one MCP server instance:

| Part of a deployment | Example |
|---|---|
| Database connection and account | JDBC URL, one shared read-only account (5.0) |
| Model | introspected schema plus overrides, or an existing persistence unit (4.1) |
| Named reports | `report_sales_by_region` (4.5) |
| Tool settings | which tools are exposed, limits (4.3, FR-16) |

One instance per database account also gives one instance per trust boundary: callers who need different access get a different instance.

It is delivered in two forms:

- **Standalone runtime (the POC).** An executable JAR (later a container image) that takes a JDBC connection and a config file and serves MCP, with no code to write.
- **Embeddable library.** An application that already has JPA entities and a persistence unit adds a dependency and exposes its own model and named reports over MCP, reusing its existing mappings, pool and transport. This targets existing EclipseLink users.

Both forms share the same core (model building, MOXy binding, tool generation); the standalone runtime is a thin launcher over the library.

Each instance:

1. Builds an object model from an existing schema with EclipseLink, without generating or compiling entity classes.
2. Exposes that model to agents as MCP tools and resources, with JSON (and optionally XML) payloads and JSON Schema derived from the mappings via MOXy.
3. Enforces role-based access to entities, rows, fields and operations for every request, in the persistence layer. *Deferred until after the initial POC; see 5.*

### Non-goals (first version)

- Arbitrary native SQL execution.
- Schema changes (DDL) of any kind.
- Writes (create/update/delete) in the first milestone; designed for but deferred.
- A UI for authoring mappings.
- The role-based security model (section 5 beyond 5.0) in the initial POC.

## 3. Users and scenarios

- **Agent developer** points the server at a database, gets a usable set of tools for an assistant in minutes.
- **Data owner / DBA** decides which entities, rows, and fields each role sees, and which operations are allowed, in a declarative policy file.
- **End user via an agent** asks questions ("open orders for my region over $10k") and the agent answers with entity queries scoped to that user.
- **Security / audit** reviews who asked for what and what was returned.

## 4. Functional requirements

### 4.1 Model building

- FR-1: Introspect a JDBC schema (tables, columns, PKs, FKs, unique constraints) and build EclipseLink dynamic entities (`DynamicHelper` / `JPADynamicHelper`).
- FR-2: Infer relationships from foreign keys (1:1, M:1, 1:M, and M:N through join tables).
- FR-3: Allow an override file (e.g. `eclipselink-orm.xml` or a YAML/JSON model config) to rename entities/attributes, hide tables/columns, add or fix relationships, and add descriptions used in tool metadata.
- FR-4: Optionally load an existing persistence unit with static entity classes instead of introspection.
- FR-5: Rebuild or reload the model without restarting (nice to have).

### 4.2 JSON/XML binding

- FR-6: Bind the same dynamic model with MOXy (`DynamicJAXBContext`, JSON binding) so entities serialize to JSON and XML consistently.
- FR-7: Generate JSON Schema per entity (MOXy JSON Schema generation) for tool input/output descriptions.
- FR-8: Control relationship depth and cycles in output (references by id beyond a configured depth).
- FR-9: Support projections (selected attributes only) to keep responses small.

### 4.3 MCP surface (first milestone, read-only)

| Tool | Purpose |
|---|---|
| `list_entities` | Entities visible to the caller, with short descriptions |
| `describe_entity` | Attributes, types, keys, relationships and JSON Schema, filtered to what the caller may see |
| `find_entity` | Fetch by primary key, with optional projection and relationship depth |
| `query_entities` | Constrained query: entity, filter, sort, projection, paging (see 4.4) |
| `report_<name>` | One tool per named report defined in the model config: a read-only projection across related entities (see 4.5) |

- FR-10: Expose entity descriptions as MCP resources as well as tools (so clients can attach schema context).
- FR-11: All list/query results are paged with a hard maximum page size and a continuation token.
- FR-12: Return structured content (JSON) plus a short text summary.

### 4.4 Query safety

- FR-13: The default query tool accepts a structured filter (attribute, operator, value; AND/OR; navigation through relationships) that the server turns into a JPA Criteria query. No string concatenation into JPQL.
- FR-14: Optional JPQL tool, disabled by default. If enabled, it is parsed with EclipseLink's JPQL parser and checked against the caller's policy (entities, attributes, no bulk update/delete, no native functions) before execution.
- FR-15: Never expose native SQL to agents.
- FR-16: Limits per request: max rows, max relationship depth, query timeout, and max result size in bytes.

### 4.5 Report queries (read-only projections)

Many agent questions need a flat result built from several tables ("customer name, order count and total by region"). Loading full entity graphs for that is wasteful. EclipseLink's `ReportQuery` returns only the selected values in one SQL statement, without building or caching entities.

Decided (2026-11-23): report queries are **named reports only**. MCP callers cannot build ad hoc reports; they pick a published report and supply its parameters.

- FR-17: The data owner defines named reports in the model config. Each has a name, description, root entity, selected attribute paths (navigating relationships, e.g. `customer.name`, `orderLines.product.category`), fixed filter, grouping, aggregates (count, sum, avg, min, max), default sort, and typed parameters (e.g. `region`, `fromDate`).
- FR-18: Each named report is exposed as its own MCP tool (e.g. `report_sales_by_region`), whose input schema is the report's parameters plus paging. Callers can supply parameter values and page through results, nothing else; they cannot change the selection, joins, filter or grouping.
- FR-19: Reports run as EclipseLink `ReportQuery` (or the equivalent JPQL/Criteria multi-select). Joins come from mapped relationships only, with inner or outer join set per path in the definition.
- FR-20: Results are rows of named columns, with a JSON Schema for the row derived from the selected attributes' mapped types.
- FR-21: Report queries never return managed entities and never touch the shared object cache, so they suit large read-only scans within the limits in FR-16.
- FR-22: Report definitions are validated at startup against the model (paths exist, types match, parameters are used), and an invalid report fails startup rather than being silently dropped.
- FR-23 (security): Reports run under the same caller context as every other tool: database permissions in milestone 1 (5.0), and in later milestones the caller's row filters on every entity reached, plus field visibility on every selected or filtered attribute. Reports can also be limited to roles (SR-3).

### 4.6 Later milestones

- Ad hoc report queries are out of scope (see 4.5).
- Writes (`create_entity`, `update_entity`, `delete_entity`) with optimistic locking, validation from constraints, and per-role permission.
- Change notifications as MCP resource subscriptions.

### 4.7 Claude skills

The repo ships its own Claude skills for working with the factory. They double as living documentation for the config format.

- FR-24: Skills for the factory's main tasks:
  - **Configure a server from a schema:** inspect a database, generate the model config, and refine it (names, hidden tables and columns, relationships, descriptions).
  - **Author named reports:** turn a request such as "sales by region" into a report definition that passes startup validation (FR-22).
  - **Embed in an app:** add the library to an existing JPA project and expose chosen entities and reports over MCP.
  - **Validate and troubleshoot:** explain startup failures, mapping mismatches and invalid reports.
- FR-25: Self-contained (decided 2026-11-23). The skills live in this repo and do not depend on the EclipseLink-JPA-skills repo. Shared material can be factored out later if that proves useful.
- FR-26: Timing. Skills are written once the POC config format settles, so they do not churn with it.

### 4.8 Design-time workflow

The work splits into **design time** and **run time**. At design time, the skills (4.7) drive an iterative session in which Claude and the user connect to the database, explore the schema, choose the tables of interest and shape the mappings. At run time, the server only loads the result.

- FR-27: Live working context. During the session, EclipseLink dynamic persistence plus default MOXy JSON bindings give a working JPA context over the chosen tables, which can run queries and convert results to JSON before any config is final.
- FR-28: Iterate with feedback. After each change (adding a table, renaming an attribute, fixing a relationship, defining a report), the skill runs a sample query and shows the JSON that agents would receive, so the user sees the effect immediately.
- FR-29: Defaults first, overrides second. The session starts from default mappings and default JSON bindings, and records overrides only where the user wants a different name, shape or relationship. Overrides are kept minimal so the config stays readable.
- FR-30: Save the outcome as files. The session writes reviewable files that are versioned in git and loaded by the runtime, so the same files always produce the same server:
  - `eclipselink-orm.xml` for the dynamic entities and their mappings;
  - MOXy bindings (e.g. `eclipselink-oxm.json` or `.xml`) for the JSON shape;
  - the named report definitions (4.5);
  - a small deployment config naming the connection, the files above and tool settings.
- FR-31: Stay inside the account. The schema scan runs as the shared database account (5.0) and only sees what that account can read.
- FR-32: Re-entrant. The session can reopen existing files to add tables or reports later, rather than starting over.

## 5. Security requirements

Access control must not depend on the model behaving. The agent should never receive data the caller is not allowed to see.

**Phasing (decided 2026-11-23):** a security model is required, but it comes after the initial POC. The POC relies on the starting assumption in 5.0 (database permissions on one shared account). Sections 5.1 to 5.6 are the target for the milestone after the POC and will be revisited then.

### 5.0 Starting assumption (milestone 1)

The database account the server connects with has the same permissions as the MCP client it serves. Access is therefore enforced by the database's own grants (and views, row-level security or VPD where the DBA has set them up), and the server can only ever return what that account can read.

Consequences:

- Milestone 1 does not need the role-to-policy layer (SR-4, SR-5, SR-8, SR-9, SR-11) to be secure. Those requirements stay in scope for later milestones, when one server serves callers with different permissions.
- Model building should only see what the account can see: introspection runs as that account, so tables it cannot read do not become entities.
- Tool exposure (SR-3) still applies, so a read-only deployment never offers write tools even if the account could write.
- This holds with the internal connection pool (NFR-6) because every pooled connection uses the one account, which matches the single caller in milestone 1.
- Decided (2026-11-23): this carries into the HTTP, multi-user milestone. The server uses **one shared database account**, and **any authenticated MCP user can read whatever that account can access**. Authentication (SR-1) decides who may connect at all; it does not narrow what they can read.
- Deferred: per-caller access (separate database identity per caller via EclipseLink exclusive connections or Oracle proxy authentication, or the in-app policy layer in 5.2 to 5.4). Deployments that need different permission sets run separate server instances with separate accounts until then.

### 5.1 Identity

- SR-1: Use MCP's HTTP transport authorization (OAuth 2.1) to identify the caller. For stdio/local use, identity comes from configuration.
- SR-2: Map token claims (subject, groups, scopes, tenant) to roles and attribute values (e.g. `userId`, `region`, `tenantId`).

### 5.2 Which operations (tool level)

- SR-3: Tools are registered or hidden per role. A read-only role never sees write tools; the JPQL tool is visible only to roles that are granted it.

### 5.3 Which entities and rows

- SR-4: Entities are visible per role (allow-list).
- SR-5: Row filters per entity per role, applied to every query including relationship traversal, using `@AdditionalCriteria`-style expressions bound to per-request EntityManager properties (e.g. `this.region = :region`).
- SR-6: Support EclipseLink multitenancy (`@Multitenant` single-table / table-per-tenant) for tenant isolation.
- SR-7: Optional push-down to the database (Oracle VPD or equivalent) where available.

### 5.4 Which fields

- SR-8: Field visibility per entity per role, enforced both in what is fetched (JPA fetch groups) and in what is serialized (MOXy object graphs). Hidden fields do not appear in `describe_entity` or JSON Schema either.
- SR-9: Optional masking (e.g. last four digits) for sensitive fields.

### 5.5 Request handling

- SR-10: Each MCP request gets its own EntityManager created with the caller's properties. No shared EntityManager across callers. Shared cache use must respect row filters (isolated or partitioned cache where needed).
- SR-11: Policy is declarative (one file, per entity and role), validated at startup, and fails closed on errors.
- SR-12: Audit log for every tool call: caller, roles, tool, arguments, generated query, row count, duration, outcome.
- SR-13: Database credentials are never exposed to the agent; the server connects with a least-privilege database account that, in milestone 1, matches the MCP client's permissions (5.0).

### 5.6 Example policy (illustrative)

```yaml
roles:
  sales_rep:
    tools: [list_entities, describe_entity, find_entity, query_entities]
    entities:
      Customer:
        rows: "this.region = :region"
        fields: { exclude: [creditLimit, taxId] }
      Order:
        rows: "this.customer.region = :region"
  finance:
    tools: [list_entities, describe_entity, find_entity, query_entities, jpql_query]
    entities:
      Customer: { fields: all }
      Order: { fields: all }
claims:
  region: token.claims.region
```

## 6. Non-functional requirements

- NFR-1: Java 21, Maven build, runs as a single executable JAR; container image later.
- NFR-2: Supports stdio and streamable HTTP MCP transports.
- NFR-3: Works with common databases (Derby/H2 for the sample, then PostgreSQL, MySQL, Oracle).
- NFR-4: Startup on a schema of ~200 tables in under 10 seconds (target, to verify).
- NFR-5: Integration tests run against an embedded sample database with fixed seed data, including tests that prove each role cannot see filtered rows or fields.
- NFR-6: Connection pooling. *Decided (2026-11-23):* use EclipseLink's internal connection pool for milestone 1 (stdio, single caller, H2, read-only), configured through persistence unit properties (`eclipselink.connection-pool.*`). Keep a config switch to use an external `DataSource` instead (e.g. HikariCP, or a container/JNDI pool) for the HTTP, multi-user milestone. Either way, per-caller security comes from EntityManager properties, not separate database connections or credentials per caller (unless VPD push-down needs connection-level context, see SR-7).

## 7. Risks

- Large results: mitigated by paging, projections and hard limits.
- Messy schemas (no FKs, odd naming): mitigated by the override file and descriptions.
- Filter bypass through JPQL or native queries: mitigated by keeping JPQL optional and validated, and never exposing native SQL.
- Cache leakage across callers: needs deliberate cache configuration (see SR-10).
- Competition from generic SQL MCP servers: the value has to show in safety and grounding.
