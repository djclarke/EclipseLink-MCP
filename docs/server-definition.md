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
├── writes/
│   └── ship-order.yaml            # one named write operation per file; exposed only when tools.writes allows its action on its entity (optional)
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
  queries: true                     # one tool per file in queries/
  writes:                           # entity -> allowed actions; default {} (read-only). An entity that
    Order: [create, update]         # allows update or delete must be optimistically locked (4.9);
    Customer: [create, update]      # create-only needs no lock. Exposes the writes/ operations
                                    # whose root and action are allowed (2.7)
# tenancy:                          # required when the model has multitenant entities (2.9)
#   tenant: ${env:TENANT_ID}        # stdio: one tenant per process; or claim: tenant_id (HTTP)

limits:
  defaultPageSize: 50
  maxPageSize: 500
  maxDepth: 2                       # relationship depth in results
  queryTimeoutSeconds: 30
  maxResultBytes: 1048576

transport:
  type: stdio                       # stdio | http
  # http:                           # see 5.4
  #   publicUrl: https://mcp.example.com/sales   # canonical URL clients use; also the token audience
  #   port: 8080
  #   path: /mcp
  #   auth:
  #     issuer: https://login.example.com/realms/sales   # the only required auth setting

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

- `graph` paths follow mapped relationships. Each item always includes its key (and its version attribute, if it has one, 4.9), and so does every nested object, so results can be joined back to `find_entity`. A path ending in a relationship (`customer`) returns that entity's default shape: basic attributes plus to-one references.
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

### 2.7 Named write operations: `writes/*.yaml`

A server is **read-only by default** (requirements 4.10). Writes are enabled **per entity**, one business scenario at a time:

- `tools.writes` in the descriptor maps each writable entity to the actions it allows: `create`, `update` and/or `delete` (default: none). Startup fails if a listed entity does not exist, an action is unknown or the list is empty, or the entity allows `update` or `delete` but has no optimistic lock (4.9). So enabling a change to existing rows always means enabling locking for them. An entity that allows only `create` needs no lock, because a new row has nothing to conflict with.
- Each file in `writes/` defines one write tool. A tool is exposed only when `tools.writes` allows its `action` on its `root`. The sample order desk, the one write sample, allows `Order: [create, update]`, `OrderLine: [create]` and `Customer: [create, update]`, so `add_order_line` adds lines to open orders while `change_line_quantity`, `remove_order_line` (kept as examples of locking through a parent) and `reprice_product` stay hidden: existing lines are immutable, and products are read-only.
- The usual shape is one `create_<entity>`, one `update_<entity>` listing every attribute agents may change (all optional, so a call changes only what it passes), and a `condition` on each state change, either in its own operation (`ship_order`) or in a status-only update (the sample's `update_order`: SHIPPED or CANCELLED, only while OPEN). Attributes that change only through a state change, or are copied snapshots, stay out of `update_<entity>`. A parent created with its children in one call (an order and its lines) uses `items`.
- All files are validated at startup whether or not their entity is listed, so a disabled operation cannot rot unnoticed.

A write on an entity locked through a relationship also increments the parent's version. That is locking, not a data change, so the parent does not need to be listed. Rows created through `items` belong to the root's create: `Order: [create]` covers an order's lines.

```yaml
name: ship_order                    # the tool name, as written
title: Ship an order
description: Mark an open order shipped, with the time it shipped.
action: update                      # create | update | delete
root: Order
set:                                # attributes the caller supplies
  shippedAt: { required: true, description: "When it shipped, with its offset" }
fixed:                              # values the server sets; the caller cannot change them
  status: SHIPPED
condition:                          # the row must match, or the call fails with PRECONDITION_FAILED
  { path: status, op: eq, value: OPEN }
```

```yaml
name: add_order_line
title: Add an order line
description: Add a product line to an open order.
action: create
root: OrderLine                     # locked through its order (4.9): the caller passes orderVersion
set:
  order:    { required: true }      # a to-one relationship: the caller passes the target's key
  product:  { required: true }
  quantity: { required: true }
copy:                               # snapshot the product as it is now; later price changes don't touch the line
  price: product.price
  productName: product.name
condition: { path: order.status, op: eq, value: OPEN }
recompute:                          # the order's total over all of its lines, this one included
  order.total: [quantity, price]
```

```yaml
name: create_order
action: create
root: Order                         # the database generates the order and line ids
set:
  customer: { required: true }
fixed: { status: OPEN, orderDate: $today, createdAt: $now }
items:
  lines:                            # Order.lines, created with the order in one transaction
    min: 1
    set: { product: { required: true }, quantity: { required: true } }
    copy: { price: product.price, productName: product.name }
    sum: { total: [quantity, price] }
```

| Key | Meaning |
|---|---|
| `name` | Tool name, `[a-z][a-z0-9_]*`. Name it for the scenario (`ship_order`, `add_order_line`). It cannot start with `report_` or `query_`, or reuse a built-in tool name. |
| `action` | `create` inserts one root entity, `update` changes one, `delete` removes one. One entity per call. |
| `root` | The entity written. `edit rename-entity` updates it, as for reports and queries. |
| `set` | Attributes the caller supplies. Basic attributes, or owning to-one relationships, whose value is the target's key; the target must exist. Keys, versions and to-many relationships cannot be set. `required` (default false) and `allowed` (a list of permitted values, in the contract's formats) are optional. On update, an optional attribute the caller leaves out is unchanged, and an explicit `null` clears it. |
| `fixed` | Values the server sets on every call, in the same formats. An attribute is in `set` or `fixed`, not both. `$today` (a date) and `$now` (a date-time, local or with offset) take the server's clock when the call runs. |
| `copy` | Attributes the server copies from a related entity when the write is made: `target: relationship.attribute`. The first step of the path must be a relationship in `set` or `fixed`, the rest must reach a basic attribute without crossing a to-many relationship, and the types must match. The target cannot also be in `set` or `fixed`. On create it is always copied; on update only when the call changes the relationship. Use it to keep a record of details that can change later, such as a line's unit price. |
| `items` | Create only. Rows of a one-to-many relationship (mapped by the other side) created in the same call, by relationship name: `lines: { min, set, fixed, copy, sum }`. The caller passes a list of objects with the `set` attributes; `min` (default 1) is the fewest rows. The server sets each row's relationship back to the new root, and the rows' key must be generated by the database. `sum` sets root attributes from the rows: `total: [quantity, price]` is the sum over the rows of quantity × price. The output lists the created rows under the relationship's name. |
| `recompute` | Create only, on an entity locked through a relationship. Attributes of the lock holder the server recomputes: `order.total: [quantity, price]` sets the order's total to the sum of quantity × price over all of its lines, the new one included. The path must start with the lock-through relationship, so the holder's version check guards the result; the holder is written once, with its version increment. |
| `condition` | A filter in the shared grammar (4.3), with values only (no `param`). It is checked in the same transaction: before the change for update and delete, and on the new row for create, so paths through relationships work (`order.status`). |
| `locking` | Omitted (default): the entity's lock (4.9) is required. `none`: allowed only on an entity that has no version and is not locked through a relationship, and says so in the tool description (last write wins). |

Rules checked at startup (6): the root exists; every `set`, `fixed` and `condition` path exists and fits its rule above; `fixed` and `allowed` values convert to the attribute's type; and the entity has a lock, or the file says `locking: none`. Keys of a created entity are caller input unless the database generates them (4.3). Whether every `NOT NULL` column gets a value is left to the database, which reports `CONSTRAINT_VIOLATION`.

### 2.8 Entity kinds: reference, external and writable

Every entity has a **kind**, which says who changes its rows and so how the runtime may cache it. The kind is a descriptor property in the ORM file (`mcp.kind`), set with `edit set-kind` (7.5), and the design-time skill asks for it per entity.

| Kind | Who changes the rows | MCP tools | Caching | EclipseLink settings the runtime applies |
|---|---|---|---|---|
| `reference` | Nobody while the server runs, or rarely and by release (countries, currencies, units, product categories) | Read only | **Shared cache**, optionally with a time to live | `read-only="true"` on the entity, `<cache isolation="SHARED" type="FULL">`, `expiry` from `--ttl` |
| `external` (**default**) | Other applications and back-end systems, never this server | Read only | **None:** read from the database on every call | `read-only="true"` on the entity, `<cache isolation="ISOLATED">` |
| `writable` | This server's write operations (2.7), and possibly other applications too | Reads, plus the write operations `tools.writes` allows | **None**, as 5.6 requires | `<cache isolation="ISOLATED">` |

Each tool call has its own EntityManager, so an isolated entity is always read from the database: `find_entity`'s `refresh` changes nothing for it, and an agent never sees a row older than its call. Named reports never use the cache, whatever the kind (FR-21).

Rules, checked by `edit` and at startup (6):

- `external` is the default. An entity with no `mcp.kind` is read from the database every time, as every entity is today, so only an explicit choice turns caching on.
- `tools.writes` may list only `writable` entities. An entity locked through another (`mcp.lock-through`) is `writable` when its lock holder is.
- A `writable` entity need not be in `tools.writes`. It stays uncached, so a package whose writes are switched off at install (3.5.5) behaves the same, and so does a server that reads data another instance writes.
- A `reference` entity's relationships may only target other `reference` entities. A cached object that points at uncached ones would either hold stale rows or pull them into the shared cache. Remove the reverse one-to-many (`remove-attribute Product orderLines`) or unlink the relationship first; `edit set-kind` says which.
- A `reference` entity cannot be multitenant (2.9): the shared cache is shared by every caller.
- A write operation reads its `copy` sources (an order line's product price) from the database, not the cache, so a snapshot never takes a stale value from a `reference` entity.

**Choosing between `reference` and `external`.** The question to ask is "can these rows change while the server is running, other than by a new release?" Product names and prices in the order desk are a judgment call: `reference` when prices change only with a catalogue release (restart the server, or set a TTL), `external` when a back-end pricing system changes them during the day. When in doubt, `external` is always correct; `reference` is only faster.

*Later:* EclipseLink cache coordination or database change notification could invalidate `reference` entities when they change, instead of a TTL or restart. Not planned (database change notification is Oracle-specific).

### 2.9 Multitenancy

A model can mix **multitenant** entities, whose rows belong to one tenant, with **shared** entities that every tenant sees. Multitenancy is set per entity with EclipseLink's `<multitenant>` mapping, written by `edit multitenant` (7.5). Any entity without it is shared. Agents never see a tenant: the tenant column is not an attribute, does not appear in JSON, JSON Schema or `describe_entity`, and is not a write input.

**Mechanisms.** The skill asks which one each tenant entity uses:

| Mechanism | Database layout | `edit` | What EclipseLink does |
|---|---|---|---|
| **Single table** (`SINGLE_TABLE`) | One table for all tenants, with a tenant column (`TENANT_ID`), or several columns | `edit multitenant Order single-table TENANT_ID` | Adds `TENANT_ID = :tenant` to every query on the entity, relationship loads and reports included, and sets the column on insert |
| **Table per tenant** (`TABLE_PER_TENANT`) | One table per tenant, told apart by schema (`ACME.ORDERS`), or by a table name prefix or suffix (`ACME_ORDERS`) | `edit multitenant Order table-per-tenant schema` (or `prefix`, `suffix`) | Resolves the table name from the tenant for every statement |
| None (shared) | One table, no tenant | `edit multitenant Order none` | Nothing |

Oracle VPD is not offered: it is Oracle-specific (see the differentiation roadmap's Oracle section). A database per tenant needs no mapping: it is one deployment per tenant, each with its own connection (5.2).

**Where the tenant comes from.** A definition with any multitenant entity must say, in the descriptor:

```yaml
tenancy:
  tenant: ${env:TENANT_ID}          # stdio and desktop bundles: one tenant per process
  # claim: tenant_id                # HTTP (5.4): read from each caller's validated token
```

The runtime sets the tenant as an EntityManager property (`eclipselink.tenant-id`) on every call's EntityManager. With `tenant`, every call uses the same tenant; a desktop bundle asks for it in `user_config`. With `claim`, every call uses the tenant in the caller's token, and a token without the claim is refused (`403`). Startup fails if the model has tenant entities and neither is set, or if a definition with no tenant entities sets one.

The tenant is a security boundary only when it comes from a token. In a stdio process, whoever configures the process chooses the tenant, so the database account (requirements 5.0) is still the boundary there; a desktop user who must not see other tenants needs an account that cannot.

**Rules**, checked by `edit` and at startup (6):

- Tenant entities may refer to shared entities (an order line to a product, a customer to a country). Shared entities may not refer to tenant entities, so a shared row never leads to another tenant's data; remove or unlink such a relationship first.
- Tenant entities are `external` or `writable` (2.8), never `reference`, so no tenant's rows are ever in the shared cache. Per-tenant reference data, such as a tenant's own price list, is `external`.
- In a definition with tenancy, shared entities are `reference` or `external` (*decided 2026-11-25*). A shared entity cannot be `writable`, because one tenant's agent would change rows every tenant sees.
- An entity locked through another (`lock-through`) has the same multitenancy as its lock holder.
- For table per tenant, startup checks the tables of the configured `tenant`. With `claim`, a tenant whose tables are missing gets an error on its first call (`NOT_FOUND`, naming the tenant but no table).

Write operations need no change: EclipseLink sets the tenant column on insert, and updates and deletes match only the caller's tenant's rows, so another tenant's row is `NOT_FOUND`. Paging cursors, the `refresh` hint and `CONFLICT` details carry no tenant.

## 3. Packaging and running

### 3.1 Forms

| Form | What it is | Needs Java on the machine | Use |
|---|---|---|---|
| **Definition directory + runtime JAR** | `java -jar eclipselink-mcp-server.jar --definition ./sales-db` | Yes | Design time, development, CI. |
| **Bundled JAR** | One executable JAR with runtime, definition and JDBC drivers (`package --as jar`, 3.5) | Yes | A single file for a client config or a server that already has Java. |
| **Desktop bundle (`.mcpb`)** | An [MCP Bundle](https://github.com/modelcontextprotocol/mcpb) with runtime, definition, drivers and a Java runtime for one platform (`package --as mcpb`, 3.5) | No | Installing in Claude Desktop and other MCPB clients with a double click. |
| **Container image** | Runtime and definition on an `eclipse-temurin` base image (`package --as image`, 3.5) | No | HTTP hosting (later milestone, 5.2). |

All forms read the same definition format, so a definition moves between them unchanged. Packaging never changes the model, tools or writes.

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

Clients may start several copies of one server (Claude Desktop starts extra copies for Cowork and Claude Code sessions), so a definition must tolerate concurrent processes. Client/server databases do. An embedded H2 file database needs `;AUTO_SERVER=TRUE` in its URL; `init` adds it, and the runtime explains a locked file in one line instead of failing with a stack trace.

A desktop bundle (`.mcpb`, 3.5) needs none of this: the client installs it, asks for the connection settings in a form, and writes no JSON by hand.

The generated `README.md` in each definition includes these snippets with the right paths and environment variable names.

### 3.5 Building packages: `package`

*Decided (2026-11-25), not yet built.* This section describes the experience of turning a finished definition into something a data owner hands to colleagues.

#### 3.5.1 Audience and principles

- **Audience: the data owner.** The first user of `package` is the person who built and reviewed a definition (usually with the design-time skill) and now hands a server to colleagues. The colleague installs it and supplies their own connection settings; they never see the definition's files.
- **No demo mode.** A package always asks for real connection settings. The samples use the same path: an `order-desk` bundle is installed and pointed at the H2 database that the build creates (see the README), rather than shipping a separate demo database.
- **Eclipse technologies by default.** Where there is a choice, the default is an Eclipse project: Eclipse Temurin for the Java runtime and base image, Eclipse Jetty for the HTTP transport (5.4). Every default can be overridden.
- **The definition decides what the server can do.** Packaging adds a runtime and install-time settings, never tools, entities or write actions (3.5.5).

#### 3.5.2 Command

```
eclipselink-mcp-server.jar package
  --definition <dir>             definition to package (checked offline first)
  --as jar|mcpb|image            package form (3.1); repeatable
  --target <os>-<arch>[,...]     platforms for mcpb and image: macos-aarch64, macos-x64,
                                 windows-x64, linux-x64, linux-aarch64 (required for mcpb)
  --jdk <target>=<dir>           build that target's runtime with this JDK's jlink (3.5.3)
  --runtime <target>=<dir>       use this Java runtime image for that target as is (3.5.3)
  --drivers <dir>                JDBC driver JARs to include (default: as for running, 3.3)
  --out <dir>                    output directory (default: target/packages)
  --validate-against <url>       optional: also run the full startup validation (6) against this database
```

Example: a data owner packages `order-desk` for colleagues on Apple silicon Macs and Windows.

```sh
java -jar eclipselink-mcp-server.jar package --definition samples/order-desk \
  --as mcpb --target macos-aarch64,windows-x64
# target/packages/order-desk-0.1.0-macos-aarch64.mcpb
# target/packages/order-desk-0.1.0-windows-x64.mcpb
```

`package` checks the definition's files, model and writes without a database, since the build machine often cannot reach it; `--validate-against` adds the full check. The developer names the target platforms. `package` never guesses them from the build machine, because the people installing a bundle are usually on different machines.

#### 3.5.3 Choosing the Java runtime

A desktop bundle and a container image carry their own Java runtime, so the person installing them needs no Java. A runtime is specific to one OS and CPU, so each target gets its own.

| For each target | Runtime used |
|---|---|
| Neither `--jdk` nor `--runtime` given (**default**) | The **Eclipse Temurin JRE 21** for that target, downloaded from the Adoptium API, checked against its published SHA-256 checksum, cached locally (`~/.eclipselink-mcp/runtimes`) and bundled **unchanged**. |
| `--jdk <target>=<dir>` | Any JDK 21 or later from any vendor. `package` runs that JDK's `jlink` to build a runtime with only the modules the server needs. `jlink` can link for another platform from that platform's JDK, so one machine can build every target. |
| `--runtime <target>=<dir>` | An existing Java runtime image (for example one a team has already vetted), copied as is. `package` checks that it is Java 21 or later, matches the target and has the modules the server needs. |

- **Java 21 by default**, the version the runtime is built and tested on. A newer runtime is the builder's choice through `--jdk` or `--runtime`.
- **The default is not trimmed** with `jlink`, so a bundle's runtime is byte-for-byte a published Temurin release and easy to trace. Trimming the default may come later.
- OpenJDK's own builds (jdk.java.net) are JDKs only and are removed when the next release ships, so Temurin is the default OpenJDK build.
- **Recorded in the package:** runtime vendor, version, target and checksum go into the package metadata (3.5.6), so it is clear what shipped.
- **Licensing is the builder's responsibility.** Redistributing a Java runtime falls under its vendor's terms. Temurin is GPLv2 with the Classpath Exception; other vendors differ. `package` records what was bundled but enforces nothing.
- **Not supported:** GraalVM native images. The runtime is always a standard Java runtime.

The bundled JAR (`--as jar`) carries no Java runtime and uses whatever Java 21 or later is on the machine.

#### 3.5.4 Connection settings and credentials at install

A package carries no secrets (5.3). The settings the definition refers to with `${env:...}` become install-time settings:

- **Desktop bundle:** each `${env:NAME}` reference in `connection` becomes a `user_config` field in the bundle's manifest, which the client shows as a form at install. A reference in `password`, or any `${file:...}` secret, becomes a field marked `sensitive`, which the client keeps in the operating system's credential store instead of in a config file. A `${env:NAME:-default}` reference supplies the field's default. This removes the clear-text password that a hand-written `env` block leaves in the client config (3.4), without the `keychain` resolver.
- **Container image:** settings stay `${env:...}` and `${file:...}` references, supplied by the orchestrator as environment variables or mounted secrets (5.3).
- **Bundled JAR:** as for the runtime JAR (3.4).

For example, `samples/order-desk` produces a form with *Database URL* (`SALES_DB_URL`), *Database user* (`ORDER_DESK_DB_USER`, default `MCP_ORDER_DESK`) and *Database password* (`ORDER_DESK_DB_PASSWORD`, sensitive). Field labels come from the variable name. *Proposed later:* optional labels and descriptions for each setting in the descriptor.

#### 3.5.5 Writes in a package

A package is read-only unless its definition enables writes (`tools.writes`, 2.7), and even then the person installing it chooses whether to use them:

- **The definition's `tools.writes` is a ceiling.** A package can expose those writes or fewer, never more. Nothing at install or run time can add an entity or an action.
- **Desktop bundles ask, defaulting to off.** A bundle whose definition has writes adds an *Allow changes* setting (`user_config`, boolean, default `false`) that lists the write tools in its description. Off, the server starts read-only and the write tools are not registered.
- **Runtime option** `--writes none|definition` (default `definition` when run directly, `none` in a bundle until the setting is turned on). *Proposed later:* a subset form (`--writes Order:update`), checked against the definition at startup; anything outside the definition fails startup.
- **The database account is still the enforcement.** The install-time setting narrows what the agent is offered; the account the colleague connects with decides what the database allows (requirements 5.0). A read-only colleague should be given a read-only account.

#### 3.5.6 What is in a package

Every form holds the same pieces, laid out for its format:

```
order-desk-0.1.0-macos-aarch64.mcpb     # a zip
├── manifest.json        # MCPB manifest: name, version, description from server.*, tools list,
│                        #   user_config (3.5.4, 3.5.5), and mcp_config launching the bundled runtime
├── runtime/             # Java runtime for the target (3.5.3); absent in --as jar
├── server/
│   └── eclipselink-mcp-server.jar
├── definition/          # the validated definition, unchanged; drivers/ included
└── package.json         # package metadata: definition name and version, runtime version,
                         #   Java runtime vendor, version, target and checksum, build time
```

- The bundle's tool list is the definition's read tools, plus its write tools marked as needing *Allow changes*, so the client can show them before install.
- The bundle's version is the definition's `server.version`. A new definition version means a new package.
- The container image uses the same `server/` and `definition/` layout under `/opt/eclipselink-mcp`, on an `eclipse-temurin` base image for the target, with `transport` set to `http` (5.4).

## 4. MCP surface

### 4.1 Server metadata

- `serverInfo`: `name`, `title`, `version` from the descriptor.
- `instructions`: the descriptor's `instructions`, then a generated summary: entity names with one-line descriptions, report tool names, and the rules agents should know (read-only; use paging; prefer report tools for aggregates; results are limited to what the server's database account can read).
- Capabilities declared: `tools`, `resources`, `prompts`, `logging`, `completions`. Not used: `sampling`, `elicitation`, `roots` (no client file access; write approval is left to the client's confirmation of destructive tools for now).
- With write tools exposed, the generated summary says so, lists them, and gives the locking rules in 4.9 (send back the version you read; on `CONFLICT`, read again before retrying).

### 4.2 Common conventions

- Read tools carry annotations `readOnlyHint: true`, `idempotentHint: true`, `destructiveHint: false`, `openWorldHint: false`. Write tools (2.7) carry `readOnlyHint: false`, `idempotentHint: false`, `openWorldHint: false`, and `destructiveHint: true` for update and delete (`false` for create), so clients can ask the user first.
- Every tool declares an `inputSchema` and an `outputSchema`, and returns `structuredContent` matching it plus a short text summary in `content` (FR-12).
- Paging: input `pageSize` (default and max from `limits`) and `cursor` (opaque, from a previous result). Output `nextCursor` is present only when more rows exist.
- Errors are tool results with `isError: true` and `structuredContent: { "error": { "code": ..., "message": ..., "details": ... } }`. Codes: `UNKNOWN_ENTITY`, `UNKNOWN_ATTRIBUTE`, `INVALID_FILTER`, `INVALID_PARAMETER`, `NOT_FOUND`, `LIMIT_EXCEEDED`, `TIMEOUT`, `DATABASE_ERROR` (message sanitized; no SQL or credentials). Write tools add `CONFLICT`, `PRECONDITION_FAILED`, `ALREADY_EXISTS`, `CONSTRAINT_VIOLATION` and `PERMISSION_DENIED` (4.9).
- Entity JSON is produced by MOXy from the model's bindings, so tool output and `describe_entity` schemas always agree.
- Numbers, dates and times follow one fixed contract (4.2.1).

#### 4.2.1 Numbers, dates and times

Agents commonly lose precision on decimals and confuse time zones, so every server uses one fixed contract. **It has no options:** there is no setting to change formats, time zones or rounding, and no transformation of values. A value is returned as the database holds it, in the format below.

| Column type (JDBC) | Java attribute type | JSON value | JSON Schema | Example |
|---|---|---|---|---|
| `DECIMAL`, `NUMERIC` | `BigDecimal` | **string**, plain notation, scale as stored | `{"type":"string","format":"decimal","scale":2}` | `"1234.50"` |
| `REAL`, `FLOAT`, `DOUBLE` | `Float`, `Double` | number | `{"type":"number"}` | `0.25` |
| `SMALLINT`, `INTEGER`, `BIGINT` | `Integer`, `Long` | number | `{"type":"integer"}` | `1042` |
| `DATE` | `LocalDate` | string, ISO-8601 date | `format: date` | `"2026-11-23"` |
| `TIME` | `LocalTime` | string, ISO-8601 local time | `format: local-time` | `"08:44:00"` |
| `TIMESTAMP` | `LocalDateTime` | string, ISO-8601 local date-time, **no zone** | `format: local-date-time` | `"2026-11-23T08:44:00"` |
| `TIMESTAMP WITH TIME ZONE` | `OffsetDateTime` | string, ISO-8601 with the **stored offset** | `format: date-time` | `"2026-11-23T08:44:00-05:00"` |
| `TIME WITH TIME ZONE` | `OffsetTime` | string, ISO-8601 time with offset | `format: time` | `"08:44:00-05:00"` |
| `BINARY`, `BLOB` | `byte[]` | string, base64 | `contentEncoding: base64` | |

Rules:

- **Decimals are strings,** because a JSON number becomes a binary double in most clients and silently loses digits: `1234567890123456.7891` survives only as a string. The string is the stored value in plain notation, never exponent form. Its scale is as the database returned it (`"50000.0000"` for a `NUMERIC(20,4)`), and is not rounded or padded. The schema's `scale` comes from the mapping's column `scale` when the model declares one. `init` writes `precision` and `scale` for every decimal column.
- **Floating point is a JSON number,** since the value is approximate anyway. Non-finite values are the strings `"NaN"`, `"Infinity"` and `"-Infinity"`, which JSON numbers cannot express.
- **Times always show seconds** (`"2026-10-01T00:00:00"`, not `"…T00:00"`). Fractions of a second appear only when stored (`"…T16:42:07.25"`).
- **Zones are never invented or converted.** A `TIMESTAMP` without a zone is returned without one, and means whatever the database means by it. `describe_entity` marks such attributes `"timeZone": "none: the value as stored in the database"`. A `TIMESTAMP WITH TIME ZONE` keeps the offset it was stored with, and is not shifted to UTC or the server's zone. A zero offset is written `Z`.
- **Aggregates in reports:** `count` is an integer. `sum` keeps its operand's kind, so a sum of decimals is a decimal string. `avg` is a number. `min` and `max` follow their column.
- **Inputs** (filter values and report or query parameters) use the same formats. The server converts them to the column's Java type before binding, so agents never write SQL literals.
  - Decimals: a string, or a number (strings keep every digit).
  - Dates and times: ISO-8601 strings of the column's kind, for example a `local-date-time` for a `TIMESTAMP` column.
  - Comparisons on `TIMESTAMP WITH TIME ZONE` are made by the database, which compares instants in H2, PostgreSQL and Oracle. So `2026-07-22T06:30:00-04:00` equals `2026-07-22T10:30:00Z`.
  - A value in another format is rejected with `INVALID_FILTER` and an example of the right one. It is never guessed: there is no `"23/11/2026"` or date-only value for a timestamp.
- Report and query parameter types are `string`, `integer`, `decimal`, `date`, `timestamp` (local), `timestamptz` (with offset) and `boolean`. Their input schemas use the formats above.
- `describe_entity` lists each attribute's `type` (the format name above), `scale` for decimals, and an `example`.

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
Output: the entity's `kind` (2.8: `reference`, `external` or `writable`, so an agent knows whether a re-read can change), attributes (name, type, nullable, key, description), relationships (name, target, cardinality), and `jsonSchema` (MOXy-generated JSON Schema of one entity as returned by `find_entity`).

#### `find_entity`

Input:

```json
{ "entity": "Order", "id": 1042,
  "select": ["id", "orderDate", "total", "customer.name"],
  "depth": 1 }
```

`"refresh": true` reads the entity, and every related entity the call returns, from the database rather than any cache (EclipseLink refresh with cascade to all parts). The shared cache is off by default (`eclipselink.cache.shared.default=false`), so this matters once caching is enabled, but agents should pass it after a `CONFLICT` either way.

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

#### Write tools (one per named write operation)

Exposed only when `tools.writes` allows the operation's action on its root entity (2.7). Input, by action, built from the entity's mappings:

| Action | Input |
|---|---|
| `create` | the key attributes (unless the key is generated by the database), the `set` attributes, and the lock version when the entity is locked through a relationship |
| `update` | `id` (as for `find_entity`), the lock version, and the `set` attributes |
| `delete` | `id` and the lock version |

The lock version input is named after the version attribute (`version`) for an entity with its own, or `<relationship>Version` (`orderVersion`) for one locked through a relationship (4.9). It is required.

```json
{ "id": 1002, "version": 1, "shippedAt": "2026-11-23T10:30:00-05:00" }
```

Output:

```json
{ "operation": "ship_order", "action": "update", "entity": "Order",
  "item": { "id": 1002, "version": 2, "status": "SHIPPED", "shippedAt": "2026-11-23T10:30:00-05:00", "...": "..." } }
```

```json
{ "operation": "add_order_line", "action": "create", "entity": "OrderLine",
  "item": { "id": 1000, "quantity": 2, "price": "25.00", "order": { "id": 1002 }, "product": { "id": 1 } },
  "locked": { "entity": "Order", "id": 1002, "version": 3 } }
```

`item` is the entity after the change in its default shape (depth 0), including its version. `locked` appears when the lock is a relationship's target, with that entity's new version. A delete returns `{ "operation", "action": "delete", "entity", "id", "deleted": true }`, plus `locked` where it applies. Each call is one transaction.

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

### 4.9 Optimistic locking

Every write is checked with optimistic locking, and the server keeps no state between calls (requirements FR-44 to FR-47).

**Where the version lives.** An entity has a lock when:

- it has a **version attribute** (an ORM `<version>` mapping on an integer column, as `init` generates for recognized columns, 7.4), or
- it is **locked through a relationship**: its descriptor property `mcp.lock-through` names an owning to-one relationship whose target has a version attribute. The sample's `OrderLine` is locked through `order`, because ORDER_LINE has no version column and an order's lines belong to the order.

`edit set-version`, `unset-version` and `lock-through` set these up (7.5). An entity cannot have both, and the target of `lock-through` must have its own version.

**Versions travel in the JSON.** Every entity item returned by a read tool includes its version attribute, in the default shape and whenever `select` paths or a query graph are used, just like its key. `describe_entity` marks the attribute `"version": true`, or names the relationship an entity is locked through. The agent sends back the version it read, and every write returns the new version (in `item`, or in `locked`), so a sequence of changes needs no extra reads and the server needs no session.

**What a write does**, in one transaction:

1. Reads the row by key (for update and delete) and the lock holder: the row itself, or the relationship's target (for create, the target named in the input). The read refreshes from the database, so the check never compares against a cached copy.
2. Compares the lock holder's current version with the caller's. A difference is `CONFLICT`, with `details: { entity, id, expectedVersion, currentVersion, refresh }` and nothing changed.
3. Checks the `condition` (on the new row for create, after it is flushed). A mismatch is `PRECONDITION_FAILED`.
4. Applies the change. EclipseLink writes `UPDATE ... SET VERSION = v + 1 ... WHERE ID = ? AND VERSION = v` (or `DELETE ... WHERE ... AND VERSION = v`). A lock held through a relationship is incremented with `OPTIMISTIC_FORCE_INCREMENT`, so adding, changing or removing a line also updates its order's version.
5. Commits. If another writer changed the row between steps 1 and 4, the database matches no row and the call is a `CONFLICT` too, so the check holds across concurrent servers and other applications that maintain the version column.

Both kinds of `CONFLICT` tell the agent what to do, in the message, in the write tool's description, and in the server instructions:

1. Nothing was changed, so do not retry straight away.
2. Read the lock holder again with the call in `details.refresh`, for example `{ "tool": "find_entity", "arguments": { "entity": "Order", "id": 1006, "refresh": true } }`. (It is left out when `find_entity` is disabled; the message then says to read the entity again.)
3. Compare the current state with the change it meant to make, and tell the user what is different. When the server can tell that the current state no longer meets the operation's `condition` (an update's row, or a create whose condition is all on the lock holder, such as `order.status`), it says so instead, with `details.conditionHolds: false`: a logical conflict that must not be retried, such as adding a line to an order that another user has just shipped.
4. Retry with the new version only if the change still applies to the new state, asking the user when unsure.

The step 5 `CONFLICT` has `details: { entity, id, refresh }` and no versions, since the database's version is only known after the read.

**Other outcomes.** `NOT_FOUND` (no row with that key, or a relationship target in the input that does not exist), `ALREADY_EXISTS` (a create whose key exists), `CONSTRAINT_VIOLATION` (the database refused the change: not null, unique, foreign key or check; SQLState class 23), `INVALID_PARAMETER` (a value that does not fit its type, its `allowed` list or its column; class 22), and `PERMISSION_DENIED` (the account lacks the grant, 5.0 of the requirements).

*Later:* tables with no version column and no parent to lock through could use EclipseLink's field locking (`CHANGED_COLUMNS`), with the values the agent read as the expected values. Not built.

## 5. Hosting

What a host needs to run one deployment.

### 5.1 Requirements

| Need | Detail |
|---|---|
| JVM | Java 21 or later (JRE is enough), unless the package carries its own (3.5.3). *Proposed:* no other runtime dependencies. |
| Runtime | `eclipselink-mcp-server.jar` (or a bundled JAR, desktop bundle or container image, 3.1). |
| Definition | The definition directory or zip, read-only to the process. |
| JDBC driver | H2 is bundled; any other driver JAR goes in the definition's `drivers/` folder or a directory given by `--drivers` (3.3). |
| Database access | Network route from the host to the database; the shared account (requirements 5.0) with read grants on the mapped tables only. |
| Secrets | Database credentials supplied as environment variables (2.1). |
| Resources | Starting point: 1 vCPU, 512 MB heap for a small schema; size the heap to `maxPageSize` x row size x concurrent calls. EclipseLink's shared cache is off for report queries (FR-21); entity cache size is configurable. |
| Disk | None beyond logs; the server keeps no local state. |

### 5.2 Hosting options

| Option | Transport | How the client reaches it | Fits |
|---|---|---|---|
| **Local process** | stdio | The MCP client launches `java -jar ...` (3.4); one process per client session | POC, development, DB reachable from the laptop |
| **Desktop bundle** | stdio | Installed from a `.mcpb` (3.5); the client launches its bundled runtime | Colleagues of a data owner, DB reachable from the laptop |
| **Container** | HTTP | `docker run -e SALES_DB_PASSWORD=... -p 8080:8080 order-desk:0.1.0` (image from `package --as image`), or the generic image with the definition mounted at `/definition` | Shared team server, later milestone |
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

### 5.4 HTTP endpoint and OAuth (later milestone)

*Scoped 2026-11-25, not yet built.* The goal is a server a data owner can deploy once and colleagues add to Claude (web, desktop and mobile) as a custom connector, with nothing installed and the smallest possible OAuth setup.

#### 5.4.1 Transport

- MCP **streamable HTTP** at `/mcp` (configurable), served by embedded **Eclipse Jetty** through the MCP Java SDK's servlet transport.
- **Stateless only** (*decided 2026-11-25*): the SDK's stateless servlet transport, with no MCP session and no sticky sessions. Paging cursors and optimistic lock versions already travel in the JSON (4.9), so any instance can serve any call. Server-to-client notifications (4.7) are not offered over HTTP for now.
- TLS is terminated by the host's ingress or load balancer. The runtime can also serve TLS from a configured keystore, for small deployments without an ingress.
- Transport security per the MCP guidance: `Origin` validated against `allowedOrigins` (default: none, so browsers cannot call it directly), and the server binds to the configured interface (default `127.0.0.1` when `auth` is not configured, so an unauthenticated server is never reachable from the network).
- Request limits: max body size, max concurrent calls, and per-caller rate limit (configurable, off by default).

#### 5.4.2 OAuth 2.1 resource server

The server is an OAuth **resource server** as the MCP authorization spec defines it. It never issues tokens, and never handles user passwords or client registration: those belong to the organization's **authorization server** (the identity provider).

- **Protected resource metadata** (RFC 9728) at `/.well-known/oauth-protected-resource`, naming the `publicUrl` as the resource, the configured issuer as its authorization server, and the scopes it understands.
- **Challenges.** A call with no token, or an invalid one, gets `401` with `WWW-Authenticate: Bearer resource_metadata="<publicUrl>/.well-known/oauth-protected-resource"`, which is how an MCP client discovers where to sign in. A valid token that lacks a needed scope gets `403` with `error="insufficient_scope"`.
- **Token validation** of JWT access tokens with **Nimbus JOSE+JWT** (*decided 2026-11-25*; no Eclipse project fits): signature against the issuer's keys (JWKS, found through the issuer's discovery document and cached with rotation), `exp` and `nbf` with a small clock skew, `iss` equal to the configured issuer, and **`aud` containing the `publicUrl`** (RFC 8707 resource indicators). The audience check means a token issued for another service is refused here.
- **No token passthrough.** The token is never forwarded to the database or anywhere else. The server connects with its own account (5.3).
- **Which authorization servers.** Any standards-compliant OAuth 2.1 / OpenID Connect issuer that publishes discovery metadata and signs JWT access tokens. Keycloak is the tested reference (5.4.5). Opaque tokens (RFC 7662 introspection) are later work.

#### 5.4.3 Identity, database access and writes

- **Reading.** Unchanged from requirements 5.0: one shared database account, and **any authenticated caller can read what that account can read**. An optional `readScope` narrows who may connect at all.
- **Writing.** A definition with writes (2.7) also needs a **write scope** on the caller's token, by default `<server.name>:write` (for example `order-desk:write`). Configurable as a scope (default) or as a value of another claim, such as a groups or roles claim (*decided 2026-11-25*).
  - A caller without it does not see the write tools in `tools/list`, and a direct call to one is refused with `403` before the database is touched (SR-3 per caller).
  - It narrows further, never widens: the definition's `tools.writes` ceiling and the `--writes` switch (3.5.5) still apply, and the database account still enforces its grants.
- **Audit.** Every audit line (5.5) carries the token's subject (`sub`) and client id (`azp` or `client_id`), never the token.
- The tenant of a multitenant definition comes from a token claim (`tenancy.claim`, 2.9). Per-caller row filters and field visibility (requirements 5.1 to 5.6) are the later security model; they will read the same validated claims.

#### 5.4.4 Configuration

The aim is two settings for a working setup: the server's public URL and the issuer.

```yaml
transport:
  type: http
  http:
    publicUrl: https://mcp.example.com/order-desk   # required for http; the resource and the token audience
    port: 8080                                       # default 8080
    path: /mcp                                       # default /mcp
    allowedOrigins: []                               # default: none
    auth:
      issuer: https://login.example.com/realms/acme  # required; JWKS found through discovery
      # audience: <publicUrl>                        # default: publicUrl
      # readScope: order-desk:read                   # default: none (any authenticated caller)
      # writeScope: order-desk:write                 # default: <server.name>:write
      # writeClaim: scope                            # default scope; or e.g. groups, roles
```

- Every value accepts `${env:...}` and `${file:...}` references (5.3), so one image serves several environments.
- Validating JWTs needs no secret: the issuer's keys are public. The only secret stays the database password.
- `--transport http` and `--port` override the descriptor (3.2). Starting with `type: http` and no `auth` is allowed only on `127.0.0.1`, for local testing with MCP Inspector, and logs a warning.

#### 5.4.5 Minimal OAuth setup

- **Sample, one command.** `samples/hosted` holds a Docker Compose file that starts the `package --as image` server for `order-desk`, PostgreSQL with the sample schema, and **Keycloak** with a realm imported at startup. That realm has dynamic client registration enabled (so Claude and MCP Inspector register themselves), the `order-desk:read` and `order-desk:write` client scopes, and two test users: one who can write, one who can only read. `docker compose up` gives a working OAuth setup with no console steps.
- **Real identity providers.** A short page each for Entra ID, Okta, Auth0 and Keycloak listing exactly what to create (an API or resource with the server's URL as its identifier, the scopes, and a client for Claude), and the two settings to copy into the descriptor. Providers without dynamic client registration need a client registered by hand for each MCP client; each page says how.
- **Claude.** Adding the server as a custom connector needs only its `publicUrl`; Claude discovers the issuer from the protected resource metadata (5.4.2).

#### 5.4.6 Testing

- **Unit:** the token filter against tokens signed with an in-test key and served from an in-test JWKS: valid, expired, not yet valid, wrong issuer, wrong audience, bad signature, missing write scope, and key rotation.
- **Integration:** embedded Jetty with the sample definition, driven by the MCP Java SDK's HTTP client: the `401` challenge and metadata, reads, write tools hidden and refused without the scope, and a `CONFLICT` between two server instances writing the same order.
- **Keycloak:** an optional Maven profile runs the sample realm with Testcontainers, for CI with Docker.
- **Manual:** MCP Inspector and a Claude custom connector against the Compose sample, before each release.

### 5.5 Health, logging and monitoring

- `GET /health/live`: process is up. `GET /health/ready`: definition loaded and a pooled connection validates. (HTTP only; for stdio, `--validate` serves the same purpose.)
- Logs: structured JSON lines to stderr (stdout is reserved for the stdio transport). Audit lines (SR-12) carry tool, arguments, generated query, row count, duration and outcome; for HTTP also the caller's token subject and client id (5.4.3).
- Metrics (*Proposed*, HTTP only): `/metrics` in Prometheus format with tool call counts, durations, errors, and pool usage; OpenTelemetry tracing as an option.

### 5.6 Scaling and availability

- The server is stateless apart from the connection pool and EclipseLink cache, so HTTP instances scale horizontally behind a load balancer with no sticky sessions (5.4.1). Paging cursors are self-contained (encoded query position), so any instance can serve the next page.
- **Writable entities are never in the shared cache** (*decided 2026-11-25*; entity kinds in 2.8 make this a per-entity setting). Several instances, or several stdio copies on one desktop (3.4), may write the same rows, and a shared cache in one would serve stale reads after another writes. So every entity whose writes are enabled in `tools.writes`, and every entity locked through one of them (`mcp.lock-through`, such as `OrderLine`), is read from the database on every call. The shared cache is off by default already (`eclipselink.cache.shared.default=false`). Caching is turned on per entity by marking it `reference` (2.8); `external` entities, changed by other systems, stay uncached like writable ones. The runtime keeps writable entities isolated regardless, and startup fails if the definition asks to cache one. Optimistic locking (4.9) still catches concurrent writes to the same row; this rule removes the stale reads before them.
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
5b. Every write operation (2.7), whether or not `tools.writes` enables it; every entity in `tools.writes` exists, lists known actions, and is locked if it allows update or delete; and every entity's lock: version attributes are integers, and `mcp.lock-through` names an owning to-one relationship to an entity with a version.
5c. Entity kinds and tenancy (2.8, 2.9): `tools.writes` lists only `writable` entities; `reference` entities refer only to `reference` entities and are not multitenant; shared entities do not refer to tenant entities; `tenancy` is set exactly when the model has tenant entities; for table per tenant with a fixed `tenant`, that tenant's tables exist.
6. Every prompt: front matter, argument names used in the body.
7. Tool name uniqueness (including `report_`, `query_` and write operation names) and MCP name rules.

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
versions: { LEGACY_ORDERS: REV_NO, AUDIT_LOG: none }   # version column per table, or none (7.4)
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
- **Version columns** become `<version>` mappings, for optimistic locking (4.9). A column is recognized when it is an integer type, not part of the key or a foreign key, and named `VERSION`, `ROW_VERSION`, `OPT_LOCK`, `OPTLOCK`, `LOCK_VERSION` or `OBJ_VERSION` (any case). `versions:` in the selection names another column for a table, or `none` to skip detection. Entities locked through a parent are declared afterwards with `edit lock-through`.
- **Generated keys:** a single-column key the database fills in (JDBC `IS_AUTOINCREMENT`, such as an identity column) gets `<generated-value strategy="IDENTITY"/>`, so create operations do not ask the caller for it.
- **Comments** (`REMARKS`) on tables and columns become `mcp.description` properties.
- **JSON:** no OXM file. The default bindings apply until the user asks for overrides.
- **Composite keys:** written as several `<id>` attributes, and `find_entity` takes an `id` object.

The round-trip test generates a definition from the sample database with `include: ["*"]` and starts it. It then checks that the generated server returns the same items as the hand-written sample for `query_entities`, `find_entity` and a select through a one-to-many.

### 7.5 Refining the model: `edit` and `call`

After `init`, the design-time skill simplifies the model with the user. A typical session renames entities, drops reverse one-to-many relationships nobody navigates, trims columns and adds descriptions. It then defines named queries (2.5) and named reports (2.4). Model changes go through `edit`, so mappings are never edited as text:

```
java -jar eclipselink-mcp-server.jar edit --definition <dir> [--drivers <dir>] <operation>
```

| Operation | Effect |
|---|---|
| `rename-entity <Entity> <New>` | Renames the entity, updates every `target-entity`, and updates `root:` in report and query files |
| `rename-attribute <Entity> <attr> <new>` | Renames an attribute. Renaming an owning relationship also updates the inverse's `mapped-by` |
| `remove-attribute <Entity> <attr>... [--with-inverse]` | Removes attributes. Keys cannot be removed. An owning relationship with an inverse needs the inverse removed first, or `--with-inverse` |
| `unlink <Entity> <relationship>` | Replaces a to-one relationship with its foreign key column(s) as basic attributes (`product` → `productId`) and removes its inverse |
| `remove-entity <Entity>` | Removes an entity. To-one references to it are unlinked, and to-many references are removed |
| `describe <Entity>[.<attr>] <text>` | Sets or replaces `mcp.description`. Empty text removes it |
| `set-version <Entity> <attr>` | Makes an integer basic attribute the entity's optimistic lock version (4.9) |
| `unset-version <Entity>` | Turns the version attribute back into a basic attribute. Refused while write operations or `lock-through` rely on it |
| `lock-through <Entity> <relationship>` | Locks the entity through an owning to-one relationship whose target has a version (`lock-through OrderLine order`). An empty relationship (`""`) removes it |
| `set-kind <Entity> reference\|external\|writable [--ttl <duration>]` | Sets the entity's kind (2.8). `--ttl` (`30m`, `1h`, `1d`) applies to `reference` only. Refused for `reference` while the entity refers to a non-reference entity or is multitenant, and for anything but `writable` while `tools.writes` lists it |
| `multitenant <Entity> single-table <COLUMN>[,<COLUMN>...]` | Makes the entity multitenant with tenant column(s) (2.9). The columns stop being attributes. Refused if a report, query or write uses them, or the entity is `reference` |
| `multitenant <Entity> table-per-tenant schema\|prefix\|suffix` | Makes the entity multitenant with a table per tenant (2.9) |
| `multitenant <Entity> none` | Makes the entity shared again. Refused while a shared entity would then refer to a tenant entity |

Every edit is **validated and atomic**. The change is written, and then the whole definition is loaded and started against the database, reports and queries included. If anything fails, the ORM file and every report and query file are restored, and the error is printed ("Edit not applied: Report 'by_region': ... region"). A definition is therefore never left broken. One consequence is that renaming or removing an attribute that a report or query uses is refused until that YAML is updated. The skill therefore simplifies the model before writing reports and queries.

Edits keep comments and layout outside the elements they change. The XML serializer does not keep the order of attributes within an element.

To show the user what agents will see after each step (FR-28), `call` runs one tool of a definition and prints its JSON result, with no MCP client:

```
java -jar eclipselink-mcp-server.jar call --definition <dir> query_order_details '{"region":"EAST","pageSize":2}'
```

For a write tool, `call` is a **dry run** by default: it runs the operation in a transaction, flushes it so the database checks constraints and versions, prints the result with `"dryRun": true`, and rolls back. `--commit` applies it, and needs `tools.writes` to allow the operation's action on its entity. A dry run works before that, so the skill can preview an operation before the user enables writes on its entity. The design-time skill only ever uses the dry run.

The skill itself is in `skills/configure-server-from-schema/SKILL.md`.

## 8. Open decisions in this spec

1. Descriptor format: YAML (proposed) or JSON.
2. Report and prompt formats: YAML and Markdown files (proposed) or embedded in the descriptor.
3. ~~Which JDBC drivers ship with the runtime.~~ *Decided: H2 only; others from a drivers directory (3.3).*
4. ~~Whether the bundled JAR (`package` command) is in the POC or follows it.~~ *Decided (2026-11-25): follows the POC. `package` builds a bundled JAR, a desktop bundle (`.mcpb`) or a container image; the audience is the data owner; the default Java runtime is the unmodified Eclipse Temurin JRE 21 per named target, overridable with `--jdk` or `--runtime`; no GraalVM; the definition's writes are a ceiling that install-time settings can only narrow, off by default in a bundle (3.5).*
6. Per-use-case JSON shapes: object graphs over shared bindings by default, separate binding sets opt-in (4.8). *Proposed.*
7. Secret resolver plugins: a `plugins/` folder like `drivers/` (5.3). *Proposed.*
8. Default naming in `init`: no singularization; the design-time skill proposes renames (7.4). *Proposed.*
9. Tool name prefixing (e.g. `sales_db.query_entities`) when one client connects to several definitions. *Proposed: no prefix; clients already namespace by server.*
10. ~~Writes.~~ *Decided (2026-11-23): read-only by default; named write operations in `writes/`, enabled per entity and action in `tools.writes` (an entity that allows update or delete must be optimistically locked; integer versions only for now) (2.7, 4.9). No generic write tools.*
11. ~~HTTP and OAuth.~~ *Decided (2026-11-25): stateless streamable HTTP on Eclipse Jetty; OAuth 2.1 resource server with JWT validation by Nimbus JOSE+JWT and audience = `publicUrl`; one shared account, any authenticated caller reads; writes need a write scope (or configured claim) per caller; writable entities never shared-cached; Keycloak sample for a minimal OAuth setup (5.4, 5.6).*
12. Entity kinds and multitenancy (2.8, 2.9). *Proposed (2026-11-25):* three kinds, `external` by default, only `reference` cached; multitenancy per entity with single table or table per tenant (no VPD), the tenant fixed per process for stdio or read from a token claim for HTTP; shared entities never refer to tenant entities and cannot be writable in a multitenant definition (*decided 2026-11-25*).
