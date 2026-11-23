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
├── prompts/
│   └── quarterly-review.md        # author-defined MCP prompts (optional)
├── lib/                           # JDBC driver JARs (optional, see 3.3)
└── README.md                      # generated summary of the definition
```

*Proposed:* one report per file and one prompt per file, so reviews and diffs stay small.

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

- `${env:NAME}` is the only way to supply secrets. A literal password in the descriptor fails validation.
- `${env:NAME:-default}` supplies a default, and `${definition.dir}` expands to the definition directory. Substitution happens on parsed values, so an environment value cannot change the YAML structure.
- Relative paths resolve against the definition directory.
- Unknown keys fail validation, so typos are caught at startup.

### 2.2 Model: `model/eclipselink-orm.xml`

Standard EclipseLink ORM XML with `<entity ... access="VIRTUAL">` dynamic entities, written by the design-time session. Only the chosen tables appear. Each entity and attribute may carry a description (an EclipseLink property `mcp.description`) that flows into tool descriptions, JSON Schema `description` fields and resources.

### 2.3 JSON bindings: `model/eclipselink-oxm.xml`

MOXy external bindings holding **overrides only** (requirements FR-29). With no file, every entity uses default JSON binding: attribute names as mapped, relationships beyond `maxDepth` rendered as `{ "id": ... }` references.

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

### 2.5 Prompts: `prompts/*.md`

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
```

`--validate` connects to the database, builds the model and checks reports and prompts, then exits non-zero on any error. Design-time skills and CI use it.

### 3.3 JDBC drivers

*Proposed:* drivers for H2 and PostgreSQL ship in the runtime; others go in the definition's `lib/` folder (or the bundled JAR). Oracle and others are not redistributed.

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

### 4.4 Resources

| URI | Content |
|---|---|
| `eclipselink://model` | Overview: entities, relationships, reports (Markdown) |
| `eclipselink://entity/{name}` | Entity description and JSON Schema (JSON) |
| `eclipselink://report/{name}` | Report description, parameters and columns (JSON) |

Exposed as static resources plus resource templates for `{name}`. Resources describe structure only; data is reached through tools.

*Proposed:* no row-level resources (`eclipselink://entity/{name}/{id}`) in the POC, to keep one path to data.

### 4.5 Prompts

- **Built-in:** `explore_data` (argument: optional `topic`). Walks an agent through the model resource, picks entities or reports and answers the question with tools.
- **Author-defined:** one per file in `prompts/` (2.5).

### 4.6 Completions

Argument completion for `entity` and `path` inputs on tools, and for prompt arguments, from the model. *Proposed:* include, since it is cheap and helps clients that support it.

### 4.7 Notifications

- `notifications/message` (logging) at the descriptor's level; audit events are not sent to the client.
- `notifications/progress` for report and query calls that run longer than ~2 seconds, when the client supplies a progress token.
- `listChanged` is declared `false` for tools, resources and prompts: the definition is static for the life of the process. Hot reload (FR-5) would turn these on later.
- Cancellation: a cancelled request cancels the running JDBC statement.

## 5. Hosting

What a host needs to run one deployment.

### 5.1 Requirements

| Need | Detail |
|---|---|
| JVM | Java 21 or later (JRE is enough). *Proposed:* no other runtime dependencies. |
| Runtime | `eclipselink-mcp-server.jar` (or a bundled JAR / container image, 3.1). |
| Definition | The definition directory or zip, read-only to the process. |
| JDBC driver | Bundled (H2, PostgreSQL) or in the definition's `lib/` (3.3). |
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

- Credentials come only from environment variables, which hosts fill from their secret store (client config `env`, Docker/Kubernetes secrets, a cloud secret manager).
- The definition never contains secrets and can be committed to git as is.
- The database account should be read-only and limited to the mapped tables; the runtime opens connections read-only where the driver supports it.
- Credentials never appear in logs, errors (`DATABASE_ERROR` is sanitized) or MCP output.

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
6. Every prompt: front matter, argument names used in the body.
7. Tool name uniqueness (including `report_` names) and MCP name rules.

## 7. Open decisions in this spec

1. Descriptor format: YAML (proposed) or JSON.
2. Report and prompt formats: YAML and Markdown files (proposed) or embedded in the descriptor.
3. Which JDBC drivers ship with the runtime.
4. Whether the bundled JAR (`package` command) is in the POC or follows it. *Proposed: follows.*
5. Tool name prefixing (e.g. `sales_db.query_entities`) when one client connects to several definitions. *Proposed: no prefix; clients already namespace by server.*
