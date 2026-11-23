---
name: configure-server-from-schema
description: Build an EclipseLink MCP server definition from an existing database with the user. Choose tables precisely, generate with init, simplify the model with validated edits, then add named queries and named reports, checking the agent-facing JSON after every step.
---

# Configure an EclipseLink MCP server from a schema

You help a data owner turn some tables of an existing database into a read-only MCP server definition: a folder of reviewable files that the EclipseLink MCP runtime serves. You work with the user in small steps and show the JSON an agent would get after each change.

The file formats are specified in `docs/server-definition.md` in this repository. Section 2 covers the descriptor, reports and queries. Section 7 covers `init`, selections and edits.

## Ground rules

- **Never write or edit `model/eclipselink-orm.xml` by hand.** `init` generates it, and `edit` changes it. Each `edit` validates the whole definition against the database and restores every file if the change breaks anything. If an operation you need does not exist, tell the user, and do not edit the XML as text.
- **Reports and queries are YAML you write** (`reports/*.yaml`, `queries/*.yaml`). After writing one, always validate it (step 6).
- **Never handle the password.** Do not ask for it in chat, and never put it in a file or a command line. The user exports it in their own shell, and you pass only the variable's name with `--password-env`.
- **Never select a whole database** unless the user asks for every table, and the database is small. Select precisely (step 2).
- **One change at a time:** run it, show the result, and agree the next change.
- Everything is read-only. The server never writes to the database.

Commands, with `$JAR` the runtime JAR (`target/eclipselink-mcp-server-*.jar` after `mvn package`):

```sh
java -jar $JAR init --url <jdbc-url> --user <user> --password-env <VAR> --out <dir> [selection flags]
java -jar $JAR edit --definition <dir> <operation> ...      # validated model change
java -jar $JAR call --definition <dir> <tool> '<json>'      # the JSON an agent would get
java -jar $JAR --definition <dir> --validate                # summary: entities, reports, queries, tools
```

Add `--drivers <dir>` to each command for databases other than H2.

## 1. Connection

Ask for the JDBC URL, the user, and the name of the environment variable that holds the password. Suggest a read-only account limited to the tables of interest: the server can read whatever that account can (requirements 5.0). The user runs `export <VAR>=...` themselves.

If the driver is not H2, ask where the driver JAR is, and use `--drivers`.

## 2. Choose the tables

Ask what the server is for: the questions agents will answer. Then pick the smallest set of tables that answers them. For a large database, explore by name before generating anything:

- Start from a few **seed tables** the user names (`--table ORDERS,CUSTOMER`), and pull in related ones with `--follow-fks 1` (outgoing) or `--fk-direction both`. Raise the depth one step at a time.
- Use **patterns** for families of tables: `--include 'SALES_*'`, or a regex with `--include 're:^(ORD|CUST).*'`. Remove noise with `--exclude '*_AUDIT' --exclude 'TMP_*'`.
- Scope with `--schema` (it defaults to the connection's schema) and `--type TABLE --type VIEW`.
- A view or keyless table needs a key under `keys:` in a selection file, or init skips it with a warning.

For anything beyond a couple of flags, write a `selection.yaml` (format in spec 7.2) and pass `--selection`. The generated definition keeps a copy, so it can be regenerated later.

## 3. Generate

```sh
java -jar $JAR init --url ... --user ... --password-env DB_PW --name sales --out defs/sales --selection selection.yaml
```

`init` starts the generated server before it reports success. Read the generated `README.md`, which lists entities, tables, relationships and warnings, and summarize it for the user. Also run one sample call so they see the shape:

```sh
java -jar $JAR call --definition defs/sales query_entities '{"entity":"Orders","pageSize":2}'
```

## 4. Simplify the model

Walk the user through the model one entity at a time, and propose simplifications. Typical ones:

| Situation | Operation |
|---|---|
| Plural or cryptic table names (`Orders`, `TblCust`) | `rename-entity Orders Order` (also updates `root:` in reports and queries) |
| Column names agents will not understand | `rename-attribute Customer custNm name` |
| A reverse one-to-many nobody navigates (a product's order lines) | `remove-attribute Product orderLines` |
| Both directions are unneeded | `remove-attribute OrderLine product --with-inverse` |
| A relationship whose key value is enough | `unlink OrderLine product` (keeps `productId`) |
| Columns agents should not see or do not need | `remove-attribute Customer email phone` (keys cannot be removed) |
| A table that turned out to be irrelevant | `remove-entity AuditLog` (references to it become key attributes) |
| Meaning that names do not carry | `describe Customer "A customer account"`, `describe Order.status "OPEN, SHIPPED or CANCELLED"` |

A one-to-many that comes from a foreign key is often unnecessary. Keep it only when agents need to go from parent to children, for example "an order with its lines". Descriptions matter. They flow into tool descriptions and JSON Schema, which is how agents learn what the data means.

After each edit, show the command's output, and when the shape changed, a `call` result. If an edit is refused, explain the message. For example, an inverse must go first, or a report uses the attribute you tried to rename. Then propose the right order.

Do the model edits before writing reports and queries. A report that uses an attribute blocks renaming or removing it, until you update the report's YAML.

## 5. Named queries: entities with the right amount of the graph

When agents need related data together ("orders with their customer and lines"), define a named query. Its `graph` lists the attribute paths to load and return. That makes it both the load plan (one batch statement per relationship level per page) and the JSON shape. Spec 2.5 has the format.

```yaml
# queries/order-details.yaml
name: order_details
title: Order details
description: "Orders for one region, with customer and lines."
root: Order
filter:
  and:
    - { path: customer.region, op: eq, param: region }
    - { path: status, op: eq, param: status }   # optional param: dropped when not given
orderBy: [{ by: orderDate, dir: desc }]
parameters:
  region: { type: string, required: true, description: "EAST, WEST or NORTH" }
  status: { type: string }
graph: [orderDate, status, total, customer.name, lines.quantity, lines.product.name]
```

Keep graphs as small as the use case allows. Different use cases get different queries over the same entities. Do not add mappings for them.

## 6. Named reports: flat, aggregated answers

When the question is a total, a count or a list of values across tables ("sales by region"), define a named report instead (spec 2.4). It runs as one SQL statement and returns rows.

```yaml
# reports/sales-by-region.yaml
name: sales_by_region
title: Sales by region
description: "Order count and value per customer region for a date range."
root: Order
select:
  - { path: customer.region, as: region }
  - { aggregate: count, path: id, as: orderCount }
  - { aggregate: sum, path: total, as: totalValue }
filter:
  and:
    - { path: orderDate, op: ge, param: fromDate }
    - { path: orderDate, op: lt, param: toDate }
groupBy: [customer.region]
orderBy: [{ by: totalValue, dir: desc }]
parameters:
  fromDate: { type: date, required: true, description: "Inclusive (YYYY-MM-DD)" }
  toDate: { type: date, required: true, description: "Exclusive (YYYY-MM-DD)" }
```

Quote YAML values that contain `:` or start with a special character. Names match `[a-z][a-z0-9_]*`, and a query cannot be named `entities`.

Validate each new file, then show its output with realistic parameters:

```sh
java -jar $JAR --definition defs/sales --validate
java -jar $JAR call --definition defs/sales report_sales_by_region '{"fromDate":"2026-07-01","toDate":"2026-10-01"}'
java -jar $JAR call --definition defs/sales query_order_details '{"region":"EAST","pageSize":2}'
```

If validation fails, the message names the file and the problem: an unknown path, a param that is declared but unused, or a missing `as`. Fix the YAML and validate again.

## 7. Finish

- Run `--validate` once more, and list the tools the server will expose.
- Suggest the user commit the definition folder to git. It contains no secrets.
- Give the client configuration for Claude Desktop or Claude Code (spec 3.4) with the definition path, and the environment variable names from the descriptor. Remind the user the password goes in their environment, not in the file.
- Note any `init` warnings that were left unresolved, such as skipped columns or keyless views.
