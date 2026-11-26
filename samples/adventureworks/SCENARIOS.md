# Agentic scenarios: AdventureWorks

Five tasks an enterprise agent would do with this sample's server, each from the user's request to the result.
For each one: who asks and what they ask, the tools the agent calls in order, what it finds, and what the
scenario shows about EclipseLink MCP.

Every number below comes from running the calls against the sample database (see [Run them
yourself](#run-them-yourself)). The seeded open orders are dated from the day the database is built, so their
dates, and the order ids if the build differs, will not match yours exactly.

| # | Scenario | Caller | Shows |
|---|---|---|---|
| 1 | [Why did France dip?](#1-sales-analyst-why-did-france-dip) | Analyst, read-only | Named reports with grouping and path filters; an agent checking its own conclusion |
| 2 | [A customer's change request](#2-order-desk-a-customers-change-request) | Order desk, may write | Versions carried in the JSON; an order line locked through its order; recomputed totals |
| 3 | [Someone else changed the order first](#3-order-desk-someone-else-changed-the-order-first) | Order desk, may write | `CONFLICT` with a forced refresh, and the three ways to decide |
| 4 | [Pricing review](#4-pricing-review-did-the-store-pay-the-right-price) | Analyst, read-only | Effective-dated history; comparing order lines with the price at the time |
| 5 | [An analyst asks for a change](#5-an-analyst-asks-for-a-change) | Analyst, read-only | One deployment, write tools only for callers allowed to write |

## 1. Sales analyst: why did France dip?

**Prompt.** *"France looks weak in Q2 2025 compared with Q1. What happened?"*

| Step | Tool | Arguments | What comes back |
|---|---|---|---|
| 1 | `report_sales_by_territory` | Q1: `fromDate` 2025-01-01, `toDate` 2025-04-01 | France 1,336,933 |
| 2 | `report_sales_by_territory` | Q2: 2025-04-01 to 2025-07-01 | France 520,949, down 61%. Every territory fell, but France the most |
| 3 | `report_sales_by_channel` | Q1, then Q2, `territory` France | Stores: 30 orders, 843,406 then 9 orders, 134,319. Online: 493,527 then 386,630 |
| 4 | `report_store_sales` | Q1, then Q2, `territory` France | 30 stores ordered in Q1, 9 in Q2; 21 of the Q1 stores have no Q2 order. All of them are sales person 290's |
| 5 | `query_entities` | `SalesPersonQuotaHistory`, filter `salesPerson.territory.name` eq France, newest first | Quota 908,000 from 2025-02-28 |
| 6 | `report_sales_by_channel` | 2025-05-01 to 2025-07-01, no territory | Online orders only, in every territory: no store orders at all after April |
| 7 | `report_sales_by_channel` | April 2024, then April 2025, France | Stores 98,067 then 134,319 (up 37%); online 66,468 then 169,100 |

**Outcome.** Before blaming France, the agent checks whether the drop is France's. Step 6 shows that store
orders stop on 30 April 2025 in every territory, while online orders continue to 29 June, so Q2 holds one month
of store orders against three in Q1. Compared like for like (step 7), France grew. The agent answers: *"The Q2
drop is the end of the recorded store orders, not a French problem. On the months both quarters have, France is
up 37% in stores and 154% online. If store orders for May and June exist, they are not in this database yet."*
It lists the 21 stores with no Q2 order as the ones to check once the data is complete.

**What it shows.**

- **Named reports** (`sales_by_territory`, and `sales_by_channel` and `store_sales`, added for this scenario)
  answer an aggregate question in one call: grouping, sums and counts run in the database, and the agent never
  sees 31,000 orders. Each report's parameters are typed and described, so the agent knows to pass dates as
  `YYYY-MM-DDTHH:MM:SS`.
- **Paths through relationships** in filters and selections (`territory.name`, `customer.store.name`,
  `salesPerson.territory.name`) without the agent writing a join.
- **Read-only by default.** Nothing in this scenario can change data, and the analyst's server needs no write
  tools at all.
- **An agent that checks its conclusion.** The tools are cheap to call, so the agent can test the obvious story
  (step 6) before telling it.

## 2. Order desk: a customer's change request

**Prompt.** *"Two requests from this morning's inbox. Riders Company wants two red Sport-100 helmets added to
their open order. And Ben Adams says his email is now ben.adams@example.com."*

| Step | Tool | Arguments | What comes back |
|---|---|---|---|
| 1 | `query_entities` | `Customer`, filter `store.name` eq Riders Company | Two customers for the store, 579 and 29486 |
| 2 | `query_open_orders` | `customerId` 29486 | Order 75128, in process, `revisionNumber` 1, 2,505.44, a Road-250 and a pedal |
| 3 | `query_entities` | `Product`, filter `name` like `Sport-100%` | 707 *Sport-100 Helmet, Red*, list price 34.99 |
| 4 | | | The agent confirms: *"Add 2 × Sport-100 Helmet, Red at 34.99 to order 75128 (new total 2,575.42)?"* |
| 5 | `add_order_line` | `salesOrder` 75128, `salesOrderVersion` 1, `product` 707, `orderQty` 2 | The line at 34.99; `locked`: order 75128 now at revision 2 |
| 6 | `query_entities` | `Person`, firstName Ben, lastName Adams, select `emailAddresses.emailAddressId`, `.emailAddress`, `.version` | Person 1770, address 1066 `ben2@adventure-works.com`, version 1 |
| 7 | | | The agent confirms the change of address |
| 8 | `update_email_address` | `id` {businessEntityId 1770, emailAddressId 1066}, `version` 1, `emailAddress` ben.adams@example.com | The address at version 2 |

**Outcome.** Both changes are made, each after the user confirms it. The order's subtotal and total due are
recomputed over all its lines in the same transaction as the new line.

**What it shows.**

- **The version travels in the JSON.** The agent reads `revisionNumber` 1 and `version` 1 and passes them back.
  The server keeps no session between the calls, so any instance behind a load balancer can take the write.
- **Lock through the parent.** An order line has no version of its own. `add_order_line` checks and increments
  the order's revision, so adding a line and shipping the order can never both succeed against the same read.
  Existing lines cannot be edited at all, because the definition allows only `create` for `SalesOrderDetail`.
- **Server rules the agent cannot skip.** The line's price is copied from the product's list price, the
  discount is fixed at none, and a line can only be added while the order is in process. The agent supplies
  the product and the quantity, nothing else.
- **Composite keys** (`businessEntityId`, `emailAddressId`) are passed as an `id` object.

## 3. Order desk: someone else changed the order first

Same desk, same request as scenario 2, but a colleague got to the order between the agent's read and its write.
Two variants show the two ways a `CONFLICT` can turn out.

### 3a. The order shipped

A colleague ships order 75128 (`update_sales_order` to status 5): revision 2.

| Step | Tool | Arguments | What comes back |
|---|---|---|---|
| 1 | `add_order_line` | `salesOrder` 75128, `salesOrderVersion` 1, `product` 707, `orderQty` 2 | `CONFLICT`: version 2, you sent 1. `details.conditionHolds: false`: the order is no longer in process. *"This is a logical conflict, not a stale read: do not retry."* `details.refresh` is a `find_entity` call |
| 2 | `find_entity` | exactly `details.refresh.arguments` (`refresh: true`, the order with its lines) | Status 5, shipped today at 15:30, still two lines |
| 3 | | | The agent tells the user and asks what to do |

**Outcome.** *"Order 75128 shipped at 15:30 without the helmets, so I couldn't add them. Shall I place a new order
for Riders Company with the two helmets, shipped to the same address?"* If the user agrees, the agent calls
`query_customer_addresses` and `create_sales_order`. It never retries `add_order_line` with revision 2: the
condition would fail again, and the line belongs on a new order anyway.

### 3b. A colleague added the same line

A colleague handled the same email a minute earlier and added the helmets to order 75133. The agent, working from
its earlier read at revision 1, tries to add them too.

| Step | Tool | Arguments | What comes back |
|---|---|---|---|
| 1 | `add_order_line` | `salesOrder` 75133, `salesOrderVersion` 1, `product` 707, `orderQty` 2 | `CONFLICT`: version 2, you sent 1. The condition still holds, so the message lists three cases: duplicate, conflict, or still applies (retry with version 2) |
| 2 | `find_entity` | `details.refresh.arguments` | Status 1, revision 2, three lines: the third is 2 × Sport-100 Helmet, Red, added at 20:11 |
| 3 | | | The agent tells the user and asks |

**Outcome.** *"Someone already added 2 × Sport-100 Helmet, Red to this order at 20:11. That looks like the same
request. Should I leave it, or add two more?"* Only if the user says the customer wants four does the agent call
`add_order_line` again, with `salesOrderVersion` 2.

**What it shows.**

- **Optimistic locking that fails safe.** Nothing was changed by the rejected call. The server keeps no locks
  between calls, so a slow agent cannot block the warehouse.
- **A refresh that bypasses the cache.** `details.refresh` is ready to call: `refresh: true` makes EclipseLink
  read the order and all its lines from the database, not from its shared cache, so the agent decides on what
  is there now.
- **Guidance in the error.** `conditionHolds` separates a logical conflict (3a: don't retry) from a stale read
  (3b: decide whether it is a duplicate, a conflict, or still applies). In both, the agent decides with the user
  instead of retrying.

## 4. Pricing review: did the store pay the right price?

**Prompt.** *"How has the price of the red Sport-100 helmet changed, and has Riders Company been paying the right
price for it?"*

| Step | Tool | Arguments | What comes back |
|---|---|---|---|
| 1 | `query_entities` | `Product`, `name` like `Sport-100%` | 707 *Sport-100 Helmet, Red*, list price 34.99 |
| 2 | `query_list_price_history` | `productId` 707 | 33.6442 from 2022-05-30, 33.6442 from 2023-05-30, 34.99 from 2024-05-29 (current) |
| 3 | `query_entities` | `SalesOrderDetail`, filter `salesOrder.customer.customerId` eq 29486 and `product.productId` eq 707, select `salesOrder.orderDate`, `orderQty`, `unitPrice`, `unitPriceDiscount` | Oct 2022 and Apr 2023: 20.1865; Jan 2025: 20.994. No discount on any line |
| 4 | `query_open_orders` | `customerId` 29486 | Today's order 75128 with the helmets at 34.99 (after scenario 2) |

**Outcome.** The agent matches each line with the list price in force on its order date: 20.1865 is 60% of
33.6442, and 20.994 is 60% of 34.99. *"The list price rose 4% in May 2024, to 34.99. Riders Company has always
paid 60% of list, the reseller price, with no discount recorded on the line. Today's order line is at the full
34.99, 67% more than they paid in January. Worth checking with the account manager before it ships."* (Every
historical store order line in AdventureWorks is at 60% of list. The sample's write operations copy the full
list price, so this is a real difference the review finds.)

**What it shows.**

- **Effective-dated history the database already keeps.** `ProductListPriceHistory` has a start and end date
  per price; a named query returns it in order. History maintained by EclipseLink, and `asOf` reads of any
  entity, are on the roadmap.
- **Snapshots on write.** `add_order_line` copies the list price into the line, so a later price change never
  alters an order. The review can compare what was charged with what the price was.
- **Deep paths for one-off questions.** `query_entities` filters on `salesOrder.customer.customerId` and selects
  through `salesOrder` without a named query for it.

## 5. An analyst asks for a change

**Prompt**, from an analyst whose token has no write scope (on stdio: a server started with `--writes none`):
*"Cancel order 75138, the customer called to say they don't need it."*

| Step | Tool | Arguments | What comes back |
|---|---|---|---|
| 1 | (`tools/list`) | | Read tools only: `list_entities`, `describe_entity`, `find_entity`, `query_entities`, the four reports and three queries. No `update_sales_order` |
| 2 | `find_entity` | `SalesOrderHeader` 75138, its status and customer | In process (status 1), for Top of the Line Bikes |
| 3 | | | The agent explains it can't cancel orders and who can |

**Outcome.** *"I can read orders but not change them. Order 75138 for Top of the Line Bikes is still in process, so it can be
cancelled: please pass it to the order desk."* A client that calls `update_sales_order` anyway gets `Unknown
tool`, before the database is touched.

**What it shows.**

- **One deployment, access per caller.** The analyst and the order desk use the same server and the same
  definition. Over HTTP the write tools appear only for a token with `adventureworks:write` (roadmap H2 and
  S6); on stdio, `--writes none` does the same for the whole process.
- **Read-only by default.** Write tools are opt-in, named, and listed per entity in `tools.writes`; there is no
  generic update tool for an agent to misuse.
- **Defence in depth.** The database account can only insert and update orders and lines and update email
  addresses, so even a misconfigured server cannot change anything else.

## Run them yourself

With the database up and the server built (README steps 1 to 4), every step above is one `call`:

```sh
call() { java -jar "$JAR" call --definition samples/adventureworks/definition "$@"; }

# 1. Why did France dip?
call report_sales_by_territory '{"fromDate":"2025-04-01T00:00:00","toDate":"2025-07-01T00:00:00"}'
call report_sales_by_channel   '{"fromDate":"2025-04-01T00:00:00","toDate":"2025-07-01T00:00:00","territory":"France"}'
call report_store_sales        '{"fromDate":"2025-01-01T00:00:00","toDate":"2025-04-01T00:00:00","territory":"France"}'
call report_sales_by_channel   '{"fromDate":"2025-05-01T00:00:00","toDate":"2025-07-01T00:00:00"}'

# 2. Change request (dry runs: add --commit to keep them)
call query_open_orders '{"customerId":29486}'
call add_order_line '{"salesOrder":75128,"salesOrderVersion":1,"product":707,"orderQty":2}'
call query_entities '{"entity":"Person","filter":{"and":[{"path":"firstName","op":"eq","value":"Ben"},
  {"path":"lastName","op":"eq","value":"Adams"}]},"select":["businessEntityId","emailAddresses.emailAddressId",
  "emailAddresses.emailAddress","emailAddresses.version"]}'
call update_email_address '{"id":{"businessEntityId":1770,"emailAddressId":1066},"version":1,
  "emailAddress":"ben.adams@example.com"}'

# 3a. The order shipped: a colleague ships it, then the agent adds a line with the revision it read
call --commit update_sales_order '{"id":75128,"revisionNumber":1,"status":5,"shipDate":"'"$(date +%Y-%m-%dT%H:%M:%S)"'"}'
call add_order_line '{"salesOrder":75128,"salesOrderVersion":1,"product":707,"orderQty":2}'

# 3b. A colleague added the same line first
call --commit add_order_line '{"salesOrder":75133,"salesOrderVersion":1,"product":707,"orderQty":2}'
call add_order_line '{"salesOrder":75133,"salesOrderVersion":1,"product":707,"orderQty":2}'
call find_entity '{"entity":"SalesOrderHeader","id":75133,"select":["revisionNumber","status","lines"],"refresh":true}'

# 4. Pricing review
call query_list_price_history '{"productId":707}'
call query_entities '{"entity":"SalesOrderDetail","filter":{"and":[{"path":"salesOrder.customer.customerId","op":"eq",
  "value":29486},{"path":"product.productId","op":"eq","value":707}]},"select":["salesOrder.orderDate","orderQty",
  "unitPrice","unitPriceDiscount"],"orderBy":[{"by":"salesOrder.orderDate","dir":"asc"}]}'
```

Scenario 3 commits; reset the data afterwards (README step 7). Run `query_open_orders` first if your order ids
differ: the seeded orders are numbered from the end of the historical ones, two per territory.

To try them with an agent, connect the server to Claude (README step 6) and give it the prompts. For scenario 5,
start it with `--writes none`.
