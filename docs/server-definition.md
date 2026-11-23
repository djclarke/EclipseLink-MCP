# MCP Server Definition: Packaging and MCP Surface

Status: draft spec. Defaults are proposed where there is a choice and are marked *Proposed*.

This spec defines the output of the design-time workflow (requirements 4.8): the **server definition** that, together with the generic runtime, runs as an MCP server for one database. It covers what the definition contains, how it is packaged and run, and exactly what an MCP client sees.

## 1. Concepts

| Term | Meaning |
|---|---|
| **Runtime** | The generic EclipseLink MCP server JAR. Same for every deployment. |
| **Server definition** | A directory of versioned files describing one MCP server: connection, model, JSON bindings, reports, prompts and tool settings. Produced by the design-time skills, reviewed in git. |
| **Deployment** | A runtime plus one server definition plus secrets from the environment. One deployment is one MCP server instance, one database account, one trust boundary. |

The definition contains no code and no secrets. The runtime contains no deployment-specific settings.

## 2. Server definition layout

```
sales-db/                          # one server definition
├── eclipselink-mcp.yaml           # deployment descriptor (required)
├── model/
│   ├── eclipselink-orm.xml        # dynamic entities and JPA mappings (required)
│   └── eclipselink-oxm.xml        # MOXy JSON bindings: overrides only (optional)
├── reports/
│   ├── sales-by-region.yaml       # one named report per file (optional)
│   └── open-orders-by-customer.yaml
├── queries/
│   └── order-details.yaml         # one named entity query per file, with its graph (optional)
├── prompts/
│   └── quarterly-review.md        # author-defined MCP prompts (optional)
├── drivers/                       # JDBC driver JARs (optional, see 3.3)
└── README.md                      # generated summary of the definition
```

*Proposed:* one report, query or prompt per file, so reviews and diffs stay small.

### 2.1 Deployment descriptor: `eclipselink-mcp.yaml`

```yaml
apiVersion: eclipselink-mcp/v1
server:
  name: sales-db                    # MCP serverInfo.name; also the default tool prefix scope
  title: Sales database             # MCP serverInfo.title
  version: 1.0.0                    # version of this definition, not the runtime
  instructions: |                   # optional; prepended to generated instructions (4.1)
    Sales and order data for North America. Amounts are in USD.

connection:
  url: jdbc:h2:file:./data/sales
  user: ${env:SALES_DB_USER}
  password: ${env:SALES_DB_PASSWORD}
  # drivers: drivers                 # JDBC driver JARs directory (default: drivers/ if present, see 3.3)
  pool:                             # EclipseLink internal pool (NFR-6)
    min: 1
    max: 8
  # dataSource: java:comp/env/jdbc/sales   # alternative: external DataSource (later, e.g. a container-managed pool)

model:
  mappings: model/eclipselink-orm.xml
  bindings: model/eclipselink-oxm.xml       # optional
  schema: SALES                              # optional default schema

tools:
  list_entities: true
  describe_entity: true
  find_entity: true
  query_entities: true
  reports: true                     # one tool per file in reports/
limits:
  defaultPageSize: 50
  maxPageSize: 500
  maxDepth: 2                       # relationship depth in results
  queryTimeoutSeconds: 30
  maxResultBytes: 1048576

transport:
  type: stdio                       # stdio | http
  # http:
  #   port: 8080
  #   path: /mcp

logging:
  level: info
  audit: true                       # one structured log line per tool call (SR-12)
```

Rules:

- Secrets are supplied only as references: `${env:NAME}` (environment variable), `${file:PATH}` (file content, e.g. a mounted Docker or Kubernetes secret), or a plugin scheme (5.3). A literal password in the descriptor fails validation.
- Any reference takes a default with `:-` (`${env:NAME:-default}`), and `${definition.dir}` expands to the definition directory. Substitution happens on parsed values, so an environment value cannot change the YAML structure.
- Relative paths resolve against the definition directory.
- Unknown keys fail validation, so typos are caught at startup.

### 2.2 Model: `model/eclipselink-orm.xml`

Standard EclipseLink ORM XML with `<entity ... access="VIRTUAL">` dynamic entities, written by the design-time session. Only the chosen tables appear. Each entity and attribute may carry a description (an EclipseLink property `mcp.description`) that flows into tool descriptions, JSON Schema `description` fields and resources.

### 2.3 JSON bindings: `model/eclipselink-oxm.xml`

MOXy external bindings holding **overrides only** (requirements FR-29). With no file, every entity uses default JSON binding: attribute names as mapped, relationships beyond `maxDepth` rendered as `{ "id": ... }` references.

This file is the **base** shape shared by every tool. A named query (2.5) narrows it per use case with its graph, and can add its own read-only bindings where the base shape does not fit (4.8).

*Proposed:* XML for the OXM file, since MOXy's XML bindings format is the most complete; JSON bindings can be accepted later.

### 2.4 Named reports: `reports/*.yaml`

```yaml
name: sales_by_region               # tool name becomes report_sales_by_region
title: Sales by region
description: Total order value and order count per customer region for a date range.
root: Order
select:
  - path: customer.region
    as: region
  - aggregate: count
    as: orderCount
  - aggregate: sum
    path: total
    as: totalValue
filter:
  and:
    - { path: orderDate, op: ge, param: fromDate }
    - { path: orderDate, op: lt, param: toDate }
    - { path: status, op: ne, value: CANCELLED }
groupBy: [customer.region]
orderBy: [{ by: totalValue, dir: desc }]
parameters:
  fromDate: { type: date, required: true, description: Start of range, inclusive }
  toDate:   { type: date, required: true, description: End of range, exclusive }
joins:
  customer: inner                   # inner (default) | outer
```

Filter grammar is shared with `query_entities` (4.3). Every path, parameter and type is checked at startup (FR-22).

### 2.5 Named queries: `queries/*.yaml`

A named query returns **entities with a declared amount of their related graph**, for one use case. Where a report (2.4) returns flat rows, a named query returns nested JSON: an order with its customer and its lines, each line with its product. The `graph` says exactly which attributes and relationships are loaded and returned, so the agent gets what the use case needs in one call, with no lazy loading per row.

```yaml
name: order_details                 # tool name becomes query_order_details
title: Order details
description: Orders for one customer region, each with its customer and its lines.
root: Order
filter:
  and:
    - { path: customer.region, op: eq, param: region }
    - { path: status, op: eq, param: status }    # dropped when the optional param is not given
orderBy: [{ by: orderDate, dir: desc }]
parameters:
  region: { type: string, required: true }
  status: { type: string }
graph:                              # what is loaded AND what is returned
  - orderDate
  - status
  - total
  - customer.name
  - lines.quantity
  - lines.price
  - lines.product.name
  - lines.product.category
```

- `graph` paths follow mapped relationships. Each item always includes its key, and every nested object its key, so results can be joined back to `find_entity`. A path ending in a relationship (`customer`) returns that entity's default shape: basic attributes plus to-one references.
- Filter grammar, parameters and paging are the same as for reports (2.4). Callers supply parameters and paging only, never the graph or filter.
- Name `entities` is reserved (`query_entities`). Startup fails on an unknown path, an orderBy through a to-many relationship, or an undeclared or unused parameter (6).

### 2.6 Prompts: `prompts/*.md`

Markdown with YAML front matter:

```markdown
---
name: quarterly_review
title: Quarterly sales review
description: Summarize a quarter's sales by region and flag the biggest changes.
arguments:
  - { name: quarter, description: "e.g. 2026-Q3", required: true }
---
Use report_sales_by_region for {{quarter}} and the previous quarter,
compare totals by region, and list the three largest changes with a short reason
from open orders if relevant.
```

## 3. Packaging and running

### 3.1 Forms

| Form | What it is | Use |
|---|---|---|
| **Definition directory + runtime JAR** | `java -jar eclipselink-mcp-server.jar --definition ./sales-db` | Development, POC. *Proposed default.* |
| **Bundled JAR** | One executable JAR with runtime, definition and JDBC driver, built by `eclipselink-mcp package ./sales-db` | Hand to a user or client config with no other files. |
| **Container image** | Runtime image with the definition copied or mounted at `/definition` | HTTP deployments (later milestone). |

All forms read the same definition format, so a definition moves between them unchanged.

### 3.2 Command line

```
eclipselink-mcp-server.jar
  --definition <dir|zip>     server definition (required unless bundled)
  --validate                 load and validate, print a summary, exit
  --transport stdio|http     override descriptor transport
  --port <n>                 HTTP port
  --drivers <dir>            JDBC driver JARs directory (overrides the descriptor, 3.3)
```

`--validate` connects to the database, builds the model and checks reports and prompts, then exits non-zero on any error. Design-time skills and CI use it.

### 3.3 JDBC drivers

*Decided (2026-11-23):* only the **H2** driver ships inside the runtime JAR, for the sample and tests. Every other driver (PostgreSQL, MySQL, Oracle, SQL Server, ...) is supplied by the deployment, so the runtime stays vendor-neutral and never redistributes a driver whose license does not allow it.

The runtime loads driver JARs from the first of these that is set:

1. the `--drivers <dir>` command line option;
2. `connection.drivers` in the descriptor (relative to the definition directory);
3. a `drivers/` folder in the definition directory, if it exists.

Every `*.jar` in that directory is loaded through a child classloader, and each `java.sql.Driver` it provides (via `META-INF/services/java.sql.Driver`) is registered for the life of the server. A named `drivers` directory that does not exist, or holds no JAR, fails startup.

### 3.4 Client configuration

Claude Desktop / Claude Code (`mcpServers`), stdio:

```json
{
  "mcpServers": {
    "sales-db": {
      "command": "java",
      "args": ["-jar", "/opt/eclipselink-mcp/eclipselink-mcp-server.jar",
               "--definition", "/Users/me/defs/sales-db"],
      "env": { "SALES_DB_USER": "reader", "SALES_DB_PASSWORD": "..." }
    }
  }
}
```

HTTP (later milestone): `{ "type": "http", "url": "https://host/mcp" }`.

The client's `env` block keeps the password in clear text in the client's config file. Until a keychain resolver exists (5.3), a desktop user can set the descriptor's password to `${file:/Users/me/.secrets/sales-db.pw}` and leave the password out of `env`. That file should be readable by the user alone. Use an absolute path, because `~` is not expanded.

The generated `README.md` in each definition includes these snippets with the right paths and environment variable names.

## 4. MCP surface

### 4.1 Server metadata

- `serverInfo`: `name`, `title`, `version` from the descriptor.
- `instructions`: the descriptor's `instructions`, then a generated summary: entity names with one-line descriptions, report tool names, and the rules agents should know (read-only; use paging; prefer report tools for aggregates; results are limited to what the server's database account can read).
- Capabilities declared: `tools`, `resources`, `prompts`, `logging`, `completions`. Not used: `sampling`, `elicitation`, `roots` (no writes, no client file access).

### 4.2 Common conventions

- All tools are read-only and carry annotations `readOnlyHint: true`, `idempotentHint: true`, `destructiveHint: false`, `openWorldHint: false`.
- Every tool declares an `inputSchema` and an `outputSchema`, and returns `structuredContent` matching it plus a short text summary in `content` (FR-12).
- Paging: input `pageSize` (default and max from `limits`) and `cursor` (opaque, from a previous result). Output `nextCursor` is present only when more rows exist.
- Errors are tool results with `isError: true` and `structuredContent: { "error": { "code": ..., "message": ..., "details": ... } }`. Codes: `UNKNOWN_ENTITY`, `UNKNOWN_ATTRIBUTE`, `INVALID_FILTER`, `INVALID_PARAMETER`, `NOT_FOUND`, `LIMIT_EXCEEDED`, `TIMEOUT`, `DATABASE_ERROR` (message sanitized; no SQL or credentials).
- Entity JSON is produced by MOXy from the model's bindings, so tool output and `describe_entity` schemas always agree.

### 4.3 Tools

#### `list_entities`

Input: `{}`.
Output:

```json
{ "entities": [
  { "name": "Customer", "description": "A customer account", "key": ["id"],
    "relationships": ["orders"] } ] }
```

#### `describe_entity`

Input: `{ "entity": "Customer" }` (enum of entity names).
Output: attributes (name, type, nullable, key, description), relationships (name, target, cardinality), and `jsonSchema` (MOXy-generated JSON Schema of one entity as returned by `find_entity`).

#### `find_entity`

Input:

```json
{ "entity": "Order", "id": 1042,
  "select": ["id", "orderDate", "total", "customer.name"],
  "depth": 1 }
```

`id` is a scalar, or an object for composite keys. `select` is optional (default: all attributes, relationships to `depth`).
Output: `{ "entity": "Order", "item": { ... } }`, or error `NOT_FOUND`.

#### `query_entities`

Input:

```json
{ "entity": "Order",
  "filter": { "and": [
      { "path": "status", "op": "eq", "value": "OPEN" },
      { "path": "customer.region", "op": "in", "value": ["EAST", "WEST"] },
      { "path": "total", "op": "gt", "value": 10000 } ] },
  "select": ["id", "orderDate", "total", "customer.name"],
  "orderBy": [{ "by": "orderDate", "dir": "desc" }],
  "pageSize": 50, "cursor": null }
```

Filter grammar: `and` / `or` / `not` nodes over leaves `{ path, op, value }`. Operators: `eq ne lt le gt ge in notIn like isNull isNotNull between`. Paths navigate mapped relationships only. Built as a JPA Criteria query; values are always bound parameters (FR-13).
Output: `{ "entity": "Order", "items": [ ... ], "nextCursor": "..." }`.

*Proposed:* no total count by default (it costs a second query); add `"count": true` to request one.

#### `report_<name>` (one per named report)

Input: the report's `parameters` as a JSON Schema object, plus `pageSize` and `cursor`. Callers cannot change the selection, filter, grouping or joins.
Output: `{ "report": "sales_by_region", "columns": [ { "name": "region", "type": "string" }, ... ], "rows": [ { "region": "EAST", "orderCount": 120, "totalValue": 1830000.00 } ], "nextCursor": null }`.

Tool `title` and `description` come from the report file.

#### `query_<name>` (one per named query)

Input: the query's `parameters` as a JSON Schema object, plus `pageSize` and `cursor`.
Output: `{ "query": "order_details", "entity": "Order", "items": [ { "id": 1004, "orderDate": "2026-09-02", "status": "CANCELLED", "total": 240.00, "customer": { "id": 3, "name": "Initech" }, "lines": [ { "id": 5, "quantity": 2, "price": 120.00, "product": { "id": 2, "name": "Gadget", "category": "HARDWARE" } } ] } ], "nextCursor": null }`.

The tool description lists the graph, so agents can pick the query whose shape fits the question. Each page is loaded as described in 4.8.

### 4.4 Resources

| URI | Content |
|---|---|
| `eclipselink://model` | Overview: entities, relationships, reports (Markdown) |
| `eclipselink://entity/{name}` | Entity description and JSON Schema (JSON) |
| `eclipselink://report/{name}` | Report description, parameters and columns (JSON) |
| `eclipselink://query/{name}` | Named query description, parameters and graph (JSON) |

Exposed as static resources plus resource templates for `{name}`. Resources describe structure only; data is reached through tools.

*Proposed:* no row-level resources (`eclipselink://entity/{name}/{id}`) in the POC, to keep one path to data.

### 4.5 Prompts

- **Built-in:** `explore_data` (argument: optional `topic`). Walks an agent through the model resource, picks entities or reports and answers the question with tools.
- **Author-defined:** one per file in `prompts/` (2.6).

### 4.6 Completions

Argument completion for `entity` and `path` inputs on tools, and for prompt arguments, from the model. *Proposed:* include, since it is cheap and helps clients that support it.

### 4.7 Notifications

- `notifications/message` (logging) at the descriptor's level; audit events are not sent to the client.
- `notifications/progress` for report and query calls that run longer than ~2 seconds, when the client supplies a progress token.
- `listChanged` is declared `false` for tools, resources and prompts: the definition is static for the life of the process. Hot reload (FR-5) would turn these on later.
- Cancellation: a cancelled request cancels the running JDBC statement.

### 4.8 Loading related data and JSON shapes

Relationships in the model are lazy. Rendering a page of entities one lazy load at a time would cost one SQL statement per row per relationship (N+1). Every entity tool therefore decides up front what to load, from the shape it will return:

| Tool | Load graph |
|---|---|
| `query_<name>` | The query's `graph` |
| `query_entities`, `find_entity` with `select` | The `select` paths |
| `find_entity` without `select` | To-one relationships to `depth` |
| `query_entities` without `select` | None: basic attributes only |

**Loading.** Each relationship in the load graph is batch read with EclipseLink `BatchFetchType.IN`: after the page of roots is read, one statement per relationship level fetches the related rows for the whole page (`WHERE ID IN (...)`). `order_details` above costs four statements per page (orders, customers, lines, products) whether the page has 3 rows or 500. IN batching is used rather than JOIN batching because it stays correct with paging. Queries run read-only (`setIsReadOnly`), so no change tracking copies are made.

**References.** A to-one relationship outside the graph is still returned as a reference holding the target's key (`"customer": { "id": 3 }`). The key is read from the foreign key in the row already fetched, so a reference never triggers a lazy load. To-many relationships outside the graph are omitted.

**Several shapes over one model.** Different use cases need different amounts of the same entities. The model stays single (one ORM mapping of each table, one persistence unit and connection pool), and each use case is a named query with its own graph. Two ways to give each use case its own read-only MOXy shape were weighed:

| | A. Object graphs over shared bindings (proposed default) | B. Separate binding set per use case |
|---|---|---|
| What | One base MOXy context over the dynamic entities (2.3). Each named query's graph becomes a MOXy object graph (`xml-named-object-graph`), passed per marshal call (`MarshallerProperties.OBJECT_GRAPH`). | A query names its own OXM file (`bindings: views/order-summary-oxm.xml`) and gets its own `DynamicJAXBContext` over the same entities. |
| Can do | Choose which attributes and relationships appear, and how deep. | Also rename, flatten (`xml-path`: `customerName` from `customer/name`), reorder, change value formats. |
| Cost | One context for the server; graphs are cheap. Names and types stay the same across tools, so `describe_entity` stays true everywhere. | One context per variant, more startup time and memory. Each variant needs its own JSON Schema, and the same entity looks different per tool. |
| Fits | Most use cases: subsets of the same graph. | Integration-facing shapes that must match an existing contract. |

*Proposed:* A by default, with B as an opt-in `bindings` key on a named query for when a use case needs a shape that A cannot express. Both are read-only: the server only marshals, never unmarshals, and mappings in per-query OXM files are marked `read-only`. A third option, a second ORM mapping of the same table (a read-only "view entity") or a database view, stays available to the data owner for a flat projection, but reports (2.4) usually cover that case without growing the model.

In both options, one graph drives the JPA load plan (batch reads) and the MOXy object graph. That keeps what is loaded and what is serialized the same, so marshalling never touches an unloaded relationship.

*As built in the POC:* named queries, graph-driven IN batch loading, foreign-key references and read-only queries are implemented and tested, including the statement counts above. JSON is still rendered by the POC's descriptor-based renderer with the same semantics as option A. Rendering through a MOXy context built on the dynamic entity classes is the next step (EclipseLink's JPA-RS already builds MOXy contexts over JPA entities, which is the precedent to follow). Option B is not built.

## 5. Hosting

What a host needs to run one deployment.

### 5.1 Requirements

| Need | Detail |
|---|---|
| JVM | Java 21 or later (JRE is enough). *Proposed:* no other runtime dependencies. |
| Runtime | `eclipselink-mcp-server.jar` (or a bundled JAR / container image, 3.1). |
| Definition | The definition directory or zip, read-only to the process. |
| JDBC driver | H2 is bundled; any other driver JAR goes in the definition's `drivers/` folder or a directory given by `--drivers` (3.3). |
| Database access | Network route from the host to the database; the shared account (requirements 5.0) with read grants on the mapped tables only. |
| Secrets | Database credentials supplied as environment variables (2.1). |
| Resources | Starting point: 1 vCPU, 512 MB heap for a small schema; size the heap to `maxPageSize` x row size x concurrent calls. EclipseLink's shared cache is off for report queries (FR-21); entity cache size is configurable. |
| Disk | None beyond logs; the server keeps no local state. |

### 5.2 Hosting options

| Option | Transport | How the client reaches it | Fits |
|---|---|---|---|
| **Local process** | stdio | The MCP client launches `java -jar ...` (3.4); one process per client session | POC, desktop users, DB reachable from the laptop |
| **Container** | HTTP | `docker run -e SALES_DB_PASSWORD=... -v ./sales-db:/definition:ro -p 8080:8080 eclipselink-mcp` | Shared team server, later milestone |
| **Kubernetes / cloud container service** | HTTP | Deployment + Service + Ingress; secrets from a Secret or cloud secret manager | Production, several definitions side by side |

Each definition is its own process or pod, covering one database and one mapped model (requirements 2). Several definitions are hosted by running several instances, not by loading them into one process; this keeps one account per trust boundary. Hosting several definitions in one runtime is future work (requirements 4.6).

### 5.3 Secrets and credentials

The definition never holds a secret, so it can be committed to git as is. Connection settings refer to secrets, and a **secret resolver** turns each reference into a value at startup.

```yaml
connection:
  user: ${env:SALES_DB_USER}
  password: ${file:/run/secrets/sales-db-password}   # or ${env:...}, or a plugin scheme
```

**Resolvers.**

| Scheme | Source | Status |
|---|---|---|
| `env` | Environment variable | Built in (POC) |
| `file` | File content, trailing line breaks removed; relative to the definition directory | Built in (POC) |
| `keychain` | macOS Keychain, Windows Credential Manager, Linux Secret Service | Planned: desktop |
| `aws-sm`, `azure-kv`, `gcp-sm`, `oci-vault`, `vault` | AWS Secrets Manager, Azure Key Vault, GCP Secret Manager, OCI Vault, HashiCorp Vault | Planned: cloud packaging |

A resolver implements `SecretResolver` (`scheme()`, `resolve(reference, definitionDir)`). Plugins are found with Java's `ServiceLoader`, so each cloud provider is a separate optional JAR and the core runtime has no cloud SDK. An unknown scheme fails startup and lists the available ones. *Proposed:* plugins load from a `plugins/` folder (and `--plugins <dir>`) through a child classloader, as JDBC drivers do (3.3). The POC finds plugins on the runtime classpath only.

**Where each fits.**

- **Desktop (stdio):** `env` from the client config works but keeps the password in clear text in that file. `file` with an owner-only file is better. A `keychain` resolver removes the secret from disk entirely.
- **Containers:** Kubernetes, ECS, Cloud Run and Docker inject secrets as environment variables or mounted files, so `env` and `file` cover them with no plugin.
- **Cloud, direct from a secret manager:** the provider plugin, authenticated by the workload's own identity (IAM role, managed identity, workload identity). The plugin holds no credentials of its own.
- **No stored password:** some databases accept a short-lived token from the workload's identity: AWS RDS IAM authentication, Azure AD for Azure SQL and PostgreSQL, Cloud SQL IAM. *Planned:* a token provider hook on the connection that fetches a fresh token as connections are opened, rather than once at startup.
- **Oracle Wallet:** the Oracle JDBC driver already reads a wallet named in the URL (`jdbc:oracle:thin:@sales_high?TNS_ADMIN=/path/to/wallet`), so the descriptor carries no password and no resolver is needed. Documented, not built.

**Handling rules** (built where marked):

- Secrets never appear in logs, errors or MCP output. No tool or resource exposes connection settings. `DATABASE_ERROR` messages are sanitized (built).
- The descriptor's `toString` masks the password, so a printed or logged definition cannot leak it (built). Resolver errors name the reference, never the value (built).
- Secrets are resolved once at startup, and are held only in the connection settings passed to EclipseLink. Rotation needs a restart until the token provider hook exists.
- *Proposed:* warn at startup when a `file` secret can be read by other users.
- The database account should be read-only and limited to the mapped tables. The runtime opens connections read-only where the driver supports it.

### 5.4 HTTP endpoint (later milestone)

- MCP streamable HTTP transport at `/mcp` (configurable).
- TLS terminated by the host's ingress or load balancer (*Proposed*), or by the runtime with a configured keystore.
- Authentication: OAuth 2.1 per the MCP authorization spec, with the server as a resource server validating tokens from the organization's identity provider. Per the decision in requirements 5.0, any authenticated caller can read what the account can read.
- `Origin` header checks and binding to the configured interface, per the MCP transport security guidance.
- Request limits: max body size, max concurrent calls, and per-caller rate limit (configurable, off by default).

### 5.5 Health, logging and monitoring

- `GET /health/live`: process is up. `GET /health/ready`: definition loaded and a pooled connection validates. (HTTP only; for stdio, `--validate` serves the same purpose.)
- Logs: structured JSON lines to stderr (stdout is reserved for the stdio transport). Audit lines (SR-12) carry tool, arguments, generated query, row count, duration and outcome; for HTTP also the caller identity.
- Metrics (*Proposed*, HTTP only): `/metrics` in Prometheus format with tool call counts, durations, errors, and pool usage; OpenTelemetry tracing as an option.

### 5.6 Scaling and availability

- The server is stateless apart from the connection pool and EclipseLink cache, so HTTP instances scale horizontally behind a load balancer. Paging cursors are self-contained (encoded query position), so any instance can serve the next page.
- The database is the limit: total connections = instances x `pool.max`. Size the pool to the database's capacity, not the client count.
- The definition is read at startup; deploying a new definition version means a rolling restart.
- stdio deployments are one process per client session and need no scaling.

### 5.7 Upgrades and compatibility

- The runtime reads any definition with a supported `apiVersion`; a definition states the minimum runtime version it needs (`server.requiresRuntime`, optional).
- Definition `version` changes whenever tools, schemas or reports change, and is reported in `serverInfo.version`, so clients and logs show which definition answered.

## 6. Validation at startup

The runtime refuses to start, with a clear message, if any of these fail:

1. Descriptor schema (`apiVersion`, unknown keys, literal secrets).
2. Database connection with the configured account.
3. ORM XML loads and every mapped table and column exists and is readable.
4. OXM bindings refer only to mapped entities and attributes.
5. Every report: paths, joins, aggregates, parameter types and uses.
5a. Every named query: graph and orderBy paths, filter, parameter types and uses; per-query bindings refer only to mapped attributes.
6. Every prompt: front matter, argument names used in the body.
7. Tool name uniqueness (including `report_` names) and MCP name rules.

## 7. Generating a starter definition (`init`)

The design-time skill (requirements 4.8) does not write mappings freehand. It calls a deterministic generator that reads JDBC metadata for chosen tables and writes a starter definition, then iterates on the output with the user (rename, trim, add reports and queries) and checks it with sample calls.

### 7.1 Command

```
java -jar eclipselink-mcp-server.jar init --url <jdbc-url> --user <name> --password-env <VAR> \
     --out <dir> [--name <server>] [--selection selection.yaml] [selection flags] [--drivers <dir>] [--force]
```

The password is read from the environment variable named by `--password-env`, never from an argument, so it stays out of process listings and shell history. The connection is set read-only. After writing, `init` loads the new definition and starts it against the same database, so it only reports success for a definition that runs.

### 7.2 Choosing tables

Large databases are never scanned wholesale. A selection must name tables or patterns. The way to ask for every table is an explicit `include: ["*"]`, which is what the sample database's round-trip test uses. Selections live in a reusable `selection.yaml`, and command line flags add to it:

```yaml
catalog: null                          # optional
schemas: [SALES]                       # default: the connection's current schema only
tables: [ORDERS, SALES.CUSTOMER]       # exact names (seeds); unquoted names follow the database's case
include: ["ORDER*", "re:^CUST(OMER)?$"]   # glob, or regex with "re:"; case-insensitive
exclude: ["*_AUDIT", "TMP_*"]          # removes pattern and FK matches; never removes a named table
types: [TABLE, VIEW]                   # JDBC table types; default TABLE
followForeignKeys: { depth: 1, direction: both }  # outgoing (default) | both; depth 0-10
keys: { ORDER_SUMMARY_V: [ORDER_ID] }  # key for views or tables that declare no primary key
maxTables: 200                         # fail rather than generate a huge model
```

| Flag | Selection key |
|---|---|
| `--schema` (repeatable), `--catalog` | `schemas`, `catalog` |
| `--table A,B` (repeatable) | `tables` |
| `--include`, `--exclude` (repeatable) | `include`, `exclude` |
| `--type` (repeatable) | `types` |
| `--follow-fks <n>`, `--fk-direction` | `followForeignKeys` |
| `--max-tables` | `maxTables` |

**Foreign key expansion** lets a user start from a few tables and pull in their neighbours. From the seeds (named tables and pattern matches), the generator follows foreign keys breadth first to `depth` hops. It follows outgoing keys (tables the seeds reference), plus incoming ones with `direction: both`. It stays inside the selected schemas and table types, and excludes still apply. For example, `--table ORDERS --follow-fks 1 --fk-direction both` selects ORDERS, CUSTOMER and ORDER_LINE.

A table with no primary key (typically a view) is skipped with a warning, unless `keys` gives one.

*Proposed next:* more mechanisms for picking the tables of one application in a shared database, all producing the same `selection.yaml`:
- tables used by named SQL queries or a sample workload;
- tables an account has been granted;
- tables named in an existing `persistence.xml` or ORM file;
- table comments or tags.

### 7.3 Metadata reads stay scoped

| Step | JDBC call | Scope |
|---|---|---|
| Named table | `getTables(catalog, schema, exactName, types)` | One table |
| Glob | `getTables(..., LIKE pattern, types)` | The database filters names |
| Regex | `getTables(..., "%", types)`, filtered in the generator | Names only, selected schemas only |
| FK expansion | `getImportedKeys` (and `getExportedKeys` for `both`), then `getTables` per related table | Seeds and their neighbours, to `depth` |
| Details | `getColumns`, `getPrimaryKeys`, `getImportedKeys` | Selected tables only |

Column, key and foreign key metadata is never read for tables outside the selection. Names are matched exactly after each lookup, because `_` in a name is a LIKE wildcard.

### 7.4 What is generated

```
<out>/
├── eclipselink-mcp.yaml     # url and user as ${env:<NAME>_DB_URL:-...} defaults; password ${env:<VAR>}
├── model/eclipselink-orm.xml
├── selection.yaml           # the selection used, so the definition can be regenerated (FR-32)
└── README.md                # tables, relationships, environment variables, warnings
```

- **Entities:** one VIRTUAL entity per selected table with a key. Table names become `UpperCamel` (`ORDER_LINE` → `OrderLine`) and columns become `lowerCamel`. Names are not singularized: renaming `Orders` to `Order` is the skill's first suggestion. A schema is written on `<table>` only when the selection names schemas.
- **Types** come from `java.sql.Types`: integers → `Integer`/`Long`, `DECIMAL` with scale → `BigDecimal`, `DATE`/`TIME`/`TIMESTAMP` → `java.time`, character → `String`, binary → `byte[]`. A column of another type is skipped with a warning.
- **Relationships:**
  - A foreign key to a selected table's primary key becomes a lazy `many-to-one`, named from the column (`CUSTOMER_ID` → `customer`). Its columns are no longer basic attributes. When the key columns are also part of the primary key, the join columns are written read-only.
  - The target gets the inverse `one-to-many` (`Customer.orders`).
  - A foreign key to an unselected table stays a basic attribute (`productId`).
- **Comments** (`REMARKS`) on tables and columns become `mcp.description` properties.
- **JSON:** no OXM file. The default bindings apply until the user asks for overrides.
- **Composite keys:** written as several `<id>` attributes, and `find_entity` takes an `id` object.

The round-trip test generates a definition from the sample database with `include: ["*"]` and starts it. It then checks that the generated server returns the same items as the hand-written sample for `query_entities`, `find_entity` and a select through a one-to-many.

## 8. Open decisions in this spec

1. Descriptor format: YAML (proposed) or JSON.
2. Report and prompt formats: YAML and Markdown files (proposed) or embedded in the descriptor.
3. ~~Which JDBC drivers ship with the runtime.~~ *Decided: H2 only; others from a drivers directory (3.3).*
4. Whether the bundled JAR (`package` command) is in the POC or follows it. *Proposed: follows.*
6. Per-use-case JSON shapes: object graphs over shared bindings by default, separate binding sets opt-in (4.8). *Proposed.*
7. Secret resolver plugins: a `plugins/` folder like `drivers/` (5.3). *Proposed.*
8. Default naming in `init`: no singularization; the design-time skill proposes renames (7.4). *Proposed.*
9. Tool name prefixing (e.g. `sales_db.query_entities`) when one client connects to several definitions. *Proposed: no prefix; clients already namespace by server.*
