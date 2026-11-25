# Samples

Server definitions over the same sample H2 database, a before and after of the
[configure-server-from-schema](../skills/configure-server-from-schema/SKILL.md) skill.
`mvn test` creates the database at `target/test-db/sales` (or `mvn -Psample-db process-test-resources` without the tests).

| Folder | What it is |
|---|---|
| [sales-db-generated](sales-db-generated) | The raw `init` output for every table, untouched. What you get first. |
| [sales-db](sales-db) | The shaped definition: the model simplified and described, plus named reports and a named query. Read-only. |
| [order-desk](order-desk) | The same model with writes enabled for orders, order lines and customers (`Order: [create, update]`, `OrderLine: [create]`, `Customer: [create, update]` under `tools.writes`), for an order desk agent: create an order with its lines, add lines to an open order, change its status, create and update customers. It connects as its own account, which may change only what its operations change. |

## sales-db-generated

Made by this command, run from the repository root:

```sh
export SALES_DB_PASSWORD=reader   # the sample's read-only account
java -jar target/eclipselink-mcp-server-*.jar init \
  --url 'jdbc:h2:file:./target/test-db/sales;AUTO_SERVER=TRUE' \
  --user MCP_READER --password-env SALES_DB_PASSWORD \
  --name sales --include '*' --out samples/sales-db-generated
```

Every file in the folder, including its `README.md`, is `init` output. Do not edit it by hand:
`GeneratedSampleTest` regenerates it on every `mvn test` and fails on any difference. When a change to
`init` is intended, refresh the folder and commit the diff with that change:

```sh
mvn test -Dtest=GeneratedSampleTest -Dgolden.update=true
```

## sales-db

The shaped version of the same tables. Apart from element order and formatting, its model is what these
validated edits produce from the generated one:

```sh
java -jar $JAR edit --definition <dir> rename-entity Orders Order
java -jar $JAR edit --definition <dir> rename-attribute Order orderLines lines
java -jar $JAR edit --definition <dir> remove-attribute Product orderLines
java -jar $JAR edit --definition <dir> lock-through OrderLine order
java -jar $JAR edit --definition <dir> describe Customer "A customer account"
# ... and a describe for each other entity and attribute that carries a description
```

The descriptor was then given a title, agent instructions and limits; two reports and one query were
added under `reports/` and `queries/`. The tests run against this definition.

## order-desk

A second server over the same database, for a different trust boundary: it can create orders with their
lines, add lines to open orders, ship or cancel open orders, and create customers or change their details.
Existing lines cannot be changed or removed, and products are read-only. Each operation is one file in `writes/` (spec 2.7). Writes
are enabled per entity and action:

```yaml
tools:
  writes:
    Order: [create, update]
    OrderLine: [create]
    Customer: [create, update]
```

`create_order` takes the customer and a list of lines (product and quantity) and creates them all in one
transaction (`items:` in the operation). The database assigns the ids; the order starts OPEN at version 1,
dated today. Each line copies the product's current price and name (`copy:`), so repricing a product later
does not change existing orders, and the order's `total` is the sum of quantity × price (`sum:`).

`update_order` changes only the status of an OPEN order, to SHIPPED (with `shippedAt`) or CANCELLED. Every
update uses optimistic locking (spec 4.9): the caller sends back the `version` it read.

`add_order_line` adds one line to an OPEN order. A line has no version of its own: it is locked through its
order, so the caller passes the order's version as `orderVersion`, and the call checks and increments it. Two
people adding lines to the same order at once cannot both succeed: the second gets `CONFLICT`, reads the
order and all its lines again (the error's `details.refresh`), and decides whether its line is a duplicate of
one just added, conflicts with the new state, or still applies. The line copies the product's price and name,
and the order's `total` is recomputed over all of its lines (`recompute:`).

`change_line_quantity` and `remove_order_line` stay hidden: `OrderLine` allows only `create`, so existing
lines are immutable. `reprice_product` stays hidden because `Product` is not listed.

It connects as `MCP_ORDER_DESK` (password `orderdesk` in the sample), which may read every table and change
only `CUSTOMER` and `ORDERS` (insert, update), `PRODUCT` (update) and `ORDER_LINE` (insert, update, delete).
Its model is a copy of `sales-db`'s, which a test keeps identical.

```sh
export SALES_DB_URL='jdbc:h2:file:./target/test-db/sales;AUTO_SERVER=TRUE' ORDER_DESK_DB_PASSWORD=orderdesk
java -jar $JAR call --definition samples/order-desk create_order \
  '{"customer":1,"lines":[{"product":1,"quantity":2},{"product":2,"quantity":1}]}'  # dry run: rolled back
java -jar $JAR call --definition samples/order-desk update_order \
  '{"id":1002,"version":1,"status":"SHIPPED","shippedAt":"2026-11-23T10:30:00-05:00"}'
java -jar $JAR call --definition samples/order-desk add_order_line \
  '{"order":1002,"orderVersion":1,"product":1,"quantity":3}'                  # total recomputed over its lines
java -jar $JAR call --definition samples/order-desk add_order_line \
  '{"order":1002,"orderVersion":0,"product":1,"quantity":3}'                  # CONFLICT, with details.refresh
java -jar $JAR call --definition samples/order-desk update_customer \
  '{"id":3,"version":1,"email":"ap@initech.example"}'                      # changes only the fields passed
java -jar $JAR call --definition samples/order-desk create_customer \
  '{"name":"Stark Industries","region":"EAST","email":"orders@stark.example"}'  # id 100, version 1
java -jar $JAR call --definition samples/order-desk --commit update_order '...'      # really writes
```

A Desktop server running a sample from `target/eclipselink-mcp-server-*.jar` breaks when `mvn package` replaces
that JAR under it: restart it after rebuilding. `mvn test` alone is fine while it runs.
