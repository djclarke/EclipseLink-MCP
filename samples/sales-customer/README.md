# sales-customer (writes for Customer only)

The [init](../README.md#sales-customer) output for every table of the sample database, with two write
scenarios added through the configure-server-from-schema skill: creating a customer and correcting a customer's
email. The descriptor
allows `Customer: [create, update]` under `tools.writes`, so `Orders`, `OrderLine` and `Product` stay read-only.

| Tool | Action | Entity | Lock | Exposed |
|---|---|---|---|---|
| `create_customer` | create | Customer | starts at version 1; the identity column generates the id | yes |
| `update_customer` | update | Customer | `version` (the `VERSION` column `init` found) | yes |

Optimistic lock versions found by `init`: Customer.version, Orders.version, Product.version.

Environment: `SALES_DB_URL`, and `ORDER_DESK_DB_PASSWORD` for the sample's `MCP_ORDER_DESK` account
(`ORDER_DESK_DB_USER` overrides the user).
