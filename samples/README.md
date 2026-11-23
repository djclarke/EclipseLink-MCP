# Samples

Two server definitions over the same sample H2 database, a before and after of the
[configure-server-from-schema](../skills/configure-server-from-schema/SKILL.md) skill.
`mvn test` creates the database at `target/test-db/sales` (or `mvn -Psample-db process-test-resources` without the tests).

| Folder | What it is |
|---|---|
| [sales-db-generated](sales-db-generated) | The raw `init` output for every table, untouched. What you get first. |
| [sales-db](sales-db) | The shaped definition: the model simplified and described, plus named reports and a named query. Read-only. |
| [order-desk](order-desk) | The same model with write operations switched on (`tools.writes: true`), for an order desk agent. It connects as its own account, which may change only what its operations change. |

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

A second server over the same database, for a different trust boundary: it can ship and cancel orders,
correct a customer's email, reprice a product, and add, change or remove lines on open orders. Each is one file
in `writes/` (spec 2.7). Every write uses optimistic locking (spec 4.9): `Customer`, `Order` and `Product`
carry a `version`, and `OrderLine` is locked through its order, so any change to a line checks and increments
the order's version.

It connects as `MCP_ORDER_DESK` (password `orderdesk` in the sample), which may read every table and change
only `CUSTOMER`, `PRODUCT`, `ORDERS` (update) and `ORDER_LINE` (insert, update, delete). Its model is a copy of
`sales-db`'s, which a test keeps identical.

```sh
export SALES_DB_URL='jdbc:h2:file:./target/test-db/sales;AUTO_SERVER=TRUE' ORDER_DESK_DB_PASSWORD=orderdesk
java -jar $JAR call --definition samples/order-desk ship_order \
  '{"id":1002,"version":1,"shippedAt":"2026-11-23T10:30:00-05:00"}'           # dry run: rolled back
java -jar $JAR call --definition samples/order-desk --commit ship_order '...'  # really writes
```

The sample does not recalculate an order's `total` when its lines change; a real schema would do that in the
database or in a dedicated operation.
