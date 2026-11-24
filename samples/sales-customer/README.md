# sales-customer (writes for Customer only)

The [init](../README.md#sales-customer) output for every table of the sample database, with one write
scenario added through the configure-server-from-schema skill: correcting a customer's email. The descriptor
lists `tools.writes: [Customer]`, so `Orders`, `OrderLine` and `Product` stay read-only.

| Tool | Action | Entity | Lock | Exposed |
|---|---|---|---|---|
| `update_customer_email` | update | Customer | `version` (the `VERSION` column `init` found) | yes |

Optimistic lock versions found by `init`: Customer.version, Orders.version, Product.version.

Environment: `SALES_DB_URL`, and `ORDER_DESK_DB_PASSWORD` for the sample's `MCP_ORDER_DESK` account
(`ORDER_DESK_DB_USER` overrides the user).
