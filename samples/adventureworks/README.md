# AdventureWorks sample (PostgreSQL)

Two MCP servers over [AdventureWorks](https://github.com/microsoft/sql-server-samples), Microsoft's
well-known sample database for a fictitious bicycle and parts wholesaler, running on PostgreSQL: 68 tables
in five schemas, about 20,000 customers, 500 products and 31,000 sales orders with 121,000 lines. It shows
the server on a schema the size and shape of a real business system rather than one built for a demo.

| Definition | Connects as | What an agent can do |
|---|---|---|
| [analyst](analyst) | `mcp_reader` (read-only) | Browse 20 entities; reports `sales_by_territory` and `top_products`; queries `open_orders` and `list_price_history` |
| [order-entry](order-entry) | `mcp_order_entry` | The same reads plus `query_customer_addresses`, and writes: `create_sales_order` (with its lines), `add_order_line`, `update_sales_order` (approve, ship or cancel) and `update_email_address` |

Everything for this sample is in this folder: the database build (`db/`), the table selection, the script
that shapes the model, and both definitions.

## Run it

You need Docker (or your own PostgreSQL, see below), Java 21 and Maven. From the repository root:

```sh
mvn -q package -DskipTests                       # builds target/eclipselink-mcp-server-*.jar
mvn -q dependency:copy -Dartifact=org.postgresql:postgresql:42.7.8 \
    -DoutputDirectory=samples/adventureworks/drivers          # the runtime ships only the H2 driver
(cd samples/adventureworks && docker compose up -d)           # first build takes about a minute
```

The database listens on `localhost:5432`, database `adventureworks`. Check both definitions against it:

```sh
JAR=$(ls target/eclipselink-mcp-server-*.jar | grep -v original)
AW_READER_PASSWORD=reader java -jar $JAR --definition samples/adventureworks/analyst --validate
AW_ORDER_ENTRY_PASSWORD=orderentry java -jar $JAR --definition samples/adventureworks/order-entry --validate
```

To add a server to Claude Desktop or Claude Code, use an absolute path to the JAR and the definition:

```json
{
  "mcpServers": {
    "adventureworks-analyst": {
      "command": "java",
      "args": ["-jar", "/path/to/eclipselink-mcp-server.jar",
               "--definition", "/path/to/samples/adventureworks/analyst"],
      "env": { "AW_READER_PASSWORD": "reader" }
    }
  }
}
```

Settings: `AW_DB_URL` (default `jdbc:postgresql://localhost:5432/adventureworks`), `AW_DB_USER` and
`AW_ORDER_ENTRY_DB_USER` (default the accounts below), `AW_READER_PASSWORD`, `AW_ORDER_ENTRY_PASSWORD`.

**Reset** the data after a demo: `docker compose up -d --force-recreate`. The data is part of the image,
so every new container starts from the same state.

## Things to try

- *"Which territories sold the most in the year to June 2025?"* (`report_sales_by_territory`)
- *"What did the Sport-100 Helmet, Red cost in mid 2023?"* (`query_list_price_history`)
- *"Place an order for Bicycle Accessories and Kits: three Sport-100 helmets, red, shipped to their main
  office."* (order-entry: finds the customer and its addresses, then `create_sales_order`)

**Adding a line while someone ships the order.** Users A and B both read in-process order 75124 at revision
1. B ships it; A then adds a line with the revision it read:

```sh
call="java -jar $JAR call --definition samples/adventureworks/order-entry"
export AW_ORDER_ENTRY_PASSWORD=orderentry
$call --commit update_sales_order \
  '{"id":75124,"revisionNumber":1,"status":5,"shipDate":"2026-12-01T10:00:00"}'   # B ships: revision 2
$call add_order_line '{"salesOrder":75124,"salesOrderVersion":1,"product":707,"orderQty":3}'
```

A gets `CONFLICT` with `details.conditionHolds: false`: the order is no longer in process, so this is a
logical conflict, and A's agent must not retry but tell A that the order shipped without the line.
`details.refresh` is the `find_entity` call that shows the shipped order and its lines. (The first call
commits, so reset afterwards. The ship date must not be before the order date, which a check constraint in
the database enforces.)

## The database

`db/install.sh` builds it, and the `Dockerfile` runs it while building the image:

1. Downloads Microsoft's AdventureWorks OLTP data and Lorin Thwaits'
   [PostgreSQL port](https://github.com/lorint/AdventureWorks-for-Postgres) at pinned versions, and checks
   their SHA-256.
2. Converts the CSV files with the port's `update_csvs.rb` and loads them with its `install.sql`.
3. Applies [`db/overlay.sql`](db/overlay.sql), this sample's only changes:
   - moves each identity sequence past the loaded ids (the port leaves them at 1);
   - declares two foreign keys the schema implies (a line's product and special offer);
   - adds a `version` column to `person.emailaddress` (sales orders already have `revisionnumber`);
   - seeds 20 in-process orders, two per territory, dated around the build date, because every historical
     order is shipped;
   - creates the two accounts below.

| Account | Password | May |
|---|---|---|
| `mcp_reader` | `reader` | read every table; its sessions are read-only |
| `mcp_order_entry` | `orderentry` | read every table; insert and update sales orders and lines; update email addresses |
| `postgres` | none | owner; connects only from inside the container |

**Your own PostgreSQL instead of Docker:** run `db/install.sh` with `PGHOST`, `PGPORT`, `PGUSER` and
`PGPASSWORD` set for an account that can create databases and roles. It needs `curl`, `unzip`, `ruby` and
`psql`.

## How the model was made

`selection.yaml` picks 20 of the 68 tables. [`shape.sh`](shape.sh) runs `init` over it, then the `edit`
commands that turn the generated model into the one both definitions use: it removes row timestamps,
card details and reverse lists nobody navigates, renames entities and attributes back to AdventureWorks'
own names (PostgreSQL folds unquoted names to lower case), locks order lines through their order, and adds
descriptions. Each edit is checked against the database as it is made. The table and column comments in the
database become the descriptions agents see.

To change the model, change `shape.sh` and run it with the database up:
`samples/adventureworks/shape.sh samples/adventureworks/drivers`. It writes the same model to both
definitions, and a test checks that they stay identical.

## Tests

`AdventureWorksSampleTest` checks the two models are identical in every build. With the database running,
`AW_DB_URL=jdbc:postgresql://localhost:5432/adventureworks mvn test` also runs the reports and queries,
places an order, and reproduces the conflict above. Those tests change data; reset afterwards.

## Not yet in this sample

- **Multitenancy:** a `tenant_id` on customers, orders and lines, one tenant per territory group (North
  America, Europe, Pacific), with the product catalogue shared. It comes with multitenancy in the runtime
  (roadmap K3).
- **History:** AdventureWorks' own effective-dated tables (list price, cost, pay and department history)
  are queryable now; an EclipseLink-maintained history of sales orders comes with history support.

## Licenses

The sample's files are under the repository's license. The data and the PostgreSQL port are under the MIT
License; see [db/NOTICE.md](db/NOTICE.md). AdventureWorks is fictitious, and so are the people in it.
