# AdventureWorks sample (PostgreSQL)

One MCP server over [AdventureWorks](https://github.com/microsoft/sql-server-samples), Microsoft's
well-known sample database for a fictitious bicycle and parts wholesaler, running on PostgreSQL: 68 tables
in five schemas, about 20,000 customers, 500 products and 31,000 sales orders with 121,000 lines. It shows
the server on a schema the size and shape of a real business system rather than one built for a demo.

Everything for this sample is in this folder: the database build (`db/`), the table selection, the script
that shapes the model, and the [definition](definition).

## One deployment, access per caller

The definition is deployed once. What a caller can do depends on who they are, not on which server they
reach:

| Caller | Tools |
|---|---|
| Anyone allowed to connect | Browse 20 entities; reports `sales_by_territory` and `top_products`; queries `open_orders`, `list_price_history` and `customer_addresses` |
| A caller allowed to write | The same, plus `create_sales_order` (with its lines), `add_order_line`, `update_sales_order` (approve, ship or cancel) and `update_email_address` |

The definition's `tools.writes` is the most any caller can do. Who gets the write tools is decided per caller:

- **Over HTTP with OAuth** (spec 5.4, roadmap H1 to H5): every authenticated caller reads, and only a token
  with the scope `adventureworks:write` (or the configured role claim) sees the write tools. A caller without
  it does not get them in `tools/list`, and a direct call is refused before the database is touched. The
  Keycloak sample will have one user of each kind.
- **On stdio**, where there is no token, the person who starts the server decides: `--writes definition`
  (the default when run directly) exposes the write tools, `--writes none` runs the same definition
  read-only. A desktop bundle starts with writes off until its *Allow changes* setting is turned on (3.5.5).

Either way the server connects as one database account, `mcp_adventureworks`, shared by every caller. It
can read every table and change only what the write operations change, so the database enforces the
ceiling too.

## Run it

Every command below runs from the repository root, in one terminal (bash or zsh), in order.

### 1. What you need

- Java 21 and Maven 3.9.10 or later (on a Mac: `brew install openjdk@21 maven`).
- A PostgreSQL server for the data, either Docker (step 3a) or your own PostgreSQL 14 or later (step 3b).
  Step 3b also needs `curl`, `unzip`, `ruby` and `psql`; macOS has the first three.
- Nothing else is listening on port 5432, or set `AW_DB_URL` to the port you use (step 4).

### 2. Build the server and fetch the PostgreSQL driver

```sh
mvn -q package -DskipTests              # builds target/eclipselink-mcp-server-*.jar (a few minutes the first time)
mvn -q dependency:copy -Dartifact=org.postgresql:postgresql:42.7.8 \
    -DoutputDirectory=samples/adventureworks/drivers      # the runtime bundles only the H2 driver
```

### 3a. The database, with Docker

The image downloads the data and loads it while it builds, about a minute the first time:

```sh
(cd samples/adventureworks && docker compose up -d)
```

Without the Compose plugin, the same with plain Docker:

```sh
docker build -t eclipselink-mcp/adventureworks-db:0.1.0 samples/adventureworks/db
docker run -d --name adventureworks -p 5432:5432 eclipselink-mcp/adventureworks-db:0.1.0
```

### 3b. The database, on your own PostgreSQL

`db/install.sh` creates the `adventureworks` database and its two accounts (see [The database](#the-database)).
It connects with psql's usual `PGHOST`, `PGPORT`, `PGUSER` and `PGPASSWORD`, as an account that can create
databases and roles. With Homebrew on a Mac, whose superuser is your macOS user:

```sh
brew install postgresql@16
brew services start postgresql@16
export PATH="$(brew --prefix postgresql@16)/bin:$PATH"   # Homebrew does not link psql for a versioned formula
PGHOST=localhost samples/adventureworks/db/install.sh       # about 10 seconds
```

The script can be run again after a `dropdb adventureworks`, and the Chinook sample can share the server.

### 4. Check the definition against the database

```sh
JAR=$(ls target/eclipselink-mcp-server-*.jar | grep -v original)
export AW_DB_PASSWORD=adventureworks
java -jar "$JAR" --definition samples/adventureworks/definition --validate                 # with writes
java -jar "$JAR" --definition samples/adventureworks/definition --validate --writes none   # read-only
```

Each prints `Server definition OK` with the entities and tools. The settings, all optional apart from the
password: `AW_DB_URL` (default `jdbc:postgresql://localhost:5432/adventureworks`), `AW_DB_USER` (default
`mcp_adventureworks`) and `AW_DB_PASSWORD`.

### 5. Try the tools from the command line

`call` runs one tool, as an agent would, and prints its JSON result. Write tools are a dry run, rolled back,
unless you add `--commit`. In the same terminal as step 4:

```sh
call() { java -jar "$JAR" call --definition samples/adventureworks/definition "$@"; }

call report_sales_by_territory '{"fromDate":"2024-07-01T00:00:00","toDate":"2025-07-01T00:00:00"}'
call query_list_price_history '{"productId":707}'     # the Sport-100 Helmet, Red: three prices
call query_open_orders                                # the 20 seeded orders, status 1 (in process)
call create_sales_order '{"customer":29490,"billToAddress":887,"shipToAddress":887,"shipMethod":5,
  "dueDate":"2026-12-08T00:00:00","lines":[{"product":707,"orderQty":3}]}'     # dry run: totalDue 104.97
```

**The conflict: adding a line while someone ships the order.** Users A and B, both allowed to write, have read
order 75124: in process (`status` 1) at `revisionNumber` 1. B ships it; A then adds a line with the revision
it read.

```sh
call find_entity '{"entity":"SalesOrderHeader","id":75124,"select":["salesOrderId","revisionNumber","status"]}'
now=$(date +%Y-%m-%dT%H:%M:%S)
call --commit update_sales_order \
  '{"id":75124,"revisionNumber":1,"status":5,"shipDate":"'"$now"'"}'   # B ships: status 5, revision 2
call add_order_line '{"salesOrder":75124,"salesOrderVersion":1,"product":707,"orderQty":3}'      # A
```

A gets `CONFLICT` with `details.conditionHolds: false`: the order is no longer in process, so this is a
logical conflict, and A's agent must not retry but tell A that the order shipped without the line.
`details.refresh` is the `find_entity` call that shows the shipped order and its lines. B's call committed, so
running the demo again needs a reset (step 7). (The database's check constraint keeps a ship date from being
before the order date. The seeded orders are dated up to four days before the database was built, so shipping
them now always passes.)

### 6. Connect it to Claude

**Claude Desktop:** add the server to `~/Library/Application Support/Claude/claude_desktop_config.json`
(Settings, Developer, Edit Config), then quit and reopen Claude Desktop. Desktop does not read your shell's
`PATH`, so give the full path to `java` (`/opt/homebrew/opt/openjdk@21/bin/java` with Homebrew), the JAR and
the definition:

```json
{
  "mcpServers": {
    "adventureworks": {
      "command": "/opt/homebrew/opt/openjdk@21/bin/java",
      "args": ["-jar", "/path/to/EclipseLink-MCP/target/eclipselink-mcp-server-0.1.0-SNAPSHOT.jar",
               "--definition", "/path/to/EclipseLink-MCP/samples/adventureworks/definition"],
      "env": { "AW_DB_PASSWORD": "adventureworks" }
    }
  }
}
```

Add `"--writes", "none"` to `args` for a read-only server. **Claude Code**, from the repository root:

```sh
claude mcp add adventureworks -e AW_DB_PASSWORD=adventureworks -- \
  java -jar "$PWD/$JAR" --definition "$PWD/samples/adventureworks/definition"
```

Then ask, for example:

- *"Which territories sold the most in the year to June 2025?"* (`report_sales_by_territory`)
- *"What did the Sport-100 Helmet, Red cost in mid 2023?"* (`query_list_price_history`)
- *"Place an order for Bicycle Accessories and Kits: three Sport-100 helmets, red, shipped to their main
  office."* (with writes: finds the customer and its addresses, then `create_sales_order`)

### 7. Reset the data

After a demo that committed changes, or after the database tests (see [Tests](#tests)):

```sh
(cd samples/adventureworks && docker compose up -d --force-recreate)    # 3a: every new container starts clean
docker rm -f adventureworks && docker run -d --name adventureworks -p 5432:5432 \
    eclipselink-mcp/adventureworks-db:0.1.0                              # 3a without Compose
dropdb adventureworks && PGHOST=localhost samples/adventureworks/db/install.sh   # 3b
```

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

| Account | Password | Used by | May |
|---|---|---|---|
| `mcp_adventureworks` | `adventureworks` | the server, for every caller | read every table; insert and update sales orders and lines; update email addresses |
| `mcp_reader` | `reader` | design time: `init`, `edit` and `shape.sh` | read every table; its sessions are read-only |
| `postgres` | none | | owner; connects only from inside the container |

To use your own PostgreSQL instead of Docker, see [step 3b](#3b-the-database-on-your-own-postgresql).

## How the model was made

`selection.yaml` picks 20 of the 68 tables. [`shape.sh`](shape.sh) runs `init` over it, then the `edit`
commands that turn the generated model into the definition's: it removes row timestamps,
card details and reverse lists nobody navigates, renames entities and attributes back to AdventureWorks'
own names (PostgreSQL folds unquoted names to lower case), locks order lines through their order, and adds
descriptions. Each edit is checked against the database as it is made. The table and column comments in the
database become the descriptions agents see.

To change the model, change `shape.sh` and run it with the database up:
`samples/adventureworks/shape.sh samples/adventureworks/drivers`. It rewrites
`definition/model/eclipselink-orm.xml`.

## Tests

`AdventureWorksSampleTest` loads the definition and checks its write operations in every build. With the
database running, `AW_DB_URL=jdbc:postgresql://localhost:5432/adventureworks mvn test` also runs the reports
and queries as a read-only caller, then places an order and reproduces the conflict above as a caller allowed
to write. Those tests change data; reset afterwards.

## Not yet in this sample

- **Multitenancy:** a `tenant_id` on customers, orders and lines, one tenant per territory group (North
  America, Europe, Pacific), with the product catalogue shared. It comes with multitenancy in the runtime
  (roadmap K3).
- **History:** AdventureWorks' own effective-dated tables (list price, cost, pay and department history)
  are queryable now; an EclipseLink-maintained history of sales orders comes with history support.

## Licenses

The sample's files are under the repository's license. The data and the PostgreSQL port are under the MIT
License; see [db/NOTICE.md](db/NOTICE.md). AdventureWorks is fictitious, and so are the people in it.
