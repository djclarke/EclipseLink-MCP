# order-desk (writes enabled)

The [sales-db](../sales-db) model with writes enabled for orders and customers. The descriptor's
`tools.writes` allows `Order: [create, update]` and `Customer: [create, update]`, so the operations in
`writes/` with those entities and actions become tools and the rest stay hidden. See [../README.md](../README.md#order-desk).

| Tool | Action | Entity | Lock | Exposed |
|---|---|---|---|---|
| `create_order` | create | Order, with its lines | starts at version 1; lines copy the product's price and name; total is summed | yes |
| `update_order` | update | Order (status only, while OPEN) | `version` | yes |
| `create_customer` | create | Customer | starts at version 1 | yes |
| `update_customer` | update | Customer | `version` | yes |
| `add_order_line` | create | OrderLine | `orderVersion` (checked and incremented) | no: lines change only when an order is created |
| `change_line_quantity` | update | OrderLine | `orderVersion` | no |
| `remove_order_line` | delete | OrderLine | `orderVersion` | no |
| `reprice_product` | update | Product | `version` | no: add `Product: [update]` to `tools.writes` |
