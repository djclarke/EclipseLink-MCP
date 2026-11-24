# Samples

Server definitions over the same sample H2 database, a before and after of the
[configure-server-from-schema](../skills/configure-server-from-schema/SKILL.md) skill.
`mvn test` creates the database at `target/test-db/sales` (or `mvn -Psample-db process-test-resources` without the tests).

| Folder | What it is |
|---|---|
| [sales-db-generated](sales-db-generated) | The raw `init` output for every table, untouched. What you get first. |
| [sales-db](sales-db) | The shaped definition: the model simplified and described, plus named reports and a named query. Read-only. |
| [order-desk](order-desk) | The same model with writes enabled for `Order`, `OrderLine` and `Customer` (`Order: [update]`, `OrderLine: [create, update, delete]`, `Customer: [create, update]` under `tools.writes`), for an order desk agent. It connects as its own account, which may change only what its operations change. |
| [sales-customer](sales-customer) | Fresh `init` output with writes enabled for `Customer` only (`tools.writes: {Customer: [create, update]}`): `create_customer` and `update_customer`, locked by the `VERSION` column `init` mapped. |

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

A second server over the same database, for a different trust boundary: it can ship and cancel orders, add,
change or remove lines on open orders, and create customers or correct their email. Each is one file in
`writes/` (spec 2.7). Writes are enabled per entity and action:

```yaml
tools:
  writes:
    Order: [update]
    OrderLine: [create, update, delete]
    Customer: [create, update]
```

so `reprice_product` is defined but not exposed until `Product` is added, and customers cannot be deleted. Every write uses optimistic locking (spec 4.9): `Customer`, `Order` and `Product` carry
a `version`, and `OrderLine` is locked through its order, so any change to a line checks and increments the
order's version.

`add_order_line` takes only the order, the product and the quantity. The server copies the product's current
price and name onto the line (`copy:` in the operation, spec 2.7), so repricing or renaming a product later
does not change existing orders.

It connects as `MCP_ORDER_DESK` (password `orderdesk` in the sample), which may read every table and change
only `CUSTOMER` (insert, update), `PRODUCT` and `ORDERS` (update) and `ORDER_LINE` (insert, update, delete). Its model is a copy of
`sales-db`'s, which a test keeps identical.

```sh
export SALES_DB_URL='jdbc:h2:file:./target/test-db/sales;AUTO_SERVER=TRUE' ORDER_DESK_DB_PASSWORD=orderdesk
java -jar $JAR call --definition samples/order-desk ship_order \
  '{"id":1002,"version":1,"shippedAt":"2026-11-23T10:30:00-05:00"}'           # dry run: rolled back
java -jar $JAR call --definition samples/order-desk --commit ship_order '...'  # really writes
```

The sample does not recalculate an order's `total` when its lines change; a real schema would do that in the
database or in a dedicated operation.

## sales-customer

The smallest write setup, made with the [configure-server-from-schema](../skills/configure-server-from-schema/SKILL.md)
skill: `init` for every table, two `describe` edits, two files in `writes/`, and `Customer: [create, update]` under `tools.writes`.
`init` maps each `VERSION` column as an optimistic lock, so no locking edit was needed.

```sh
export SALES_DB_PASSWORD=reader
java -jar $JAR init --url 'jdbc:h2:file:./target/test-db/sales;AUTO_SERVER=TRUE' \
  --user MCP_READER --password-env SALES_DB_PASSWORD --name sales-customer --include '*' --out samples/sales-customer
java -jar $JAR edit --definition samples/sales-customer describe Customer "A customer account"
java -jar $JAR edit --definition samples/sales-customer describe Customer.email "Contact email address, or null when unknown"
```

It connects as `MCP_ORDER_DESK`, which may insert and update `CUSTOMER`. A new customer's id comes from the
identity column and its version starts at 1. An agent reads a customer, then sends its `version`
back; a stale version gets `CONFLICT`:

```sh
export SALES_DB_URL='jdbc:h2:file:./target/test-db/sales;AUTO_SERVER=TRUE' ORDER_DESK_DB_PASSWORD=orderdesk
java -jar $JAR call --definition samples/sales-customer find_entity '{"entity":"Customer","id":3}'
java -jar $JAR call --definition samples/sales-customer update_customer \
  '{"id":3,"version":1,"email":"ap@initech.example"}'                      # dry run: rolled back
java -jar $JAR call --definition samples/sales-customer update_customer \
  '{"id":3,"version":0,"email":"ap@initech.example"}'                      # CONFLICT
java -jar $JAR call --definition samples/sales-customer create_customer \
  '{"name":"Stark Industries","region":"EAST","email":"orders@stark.example"}'  # dry run: id 100, version 1
```

A Desktop server running a sample from `target/eclipselink-mcp-server-*.jar` breaks when `mvn package` replaces
that JAR under it: restart it after rebuilding. `mvn test` alone is fine while it runs.
