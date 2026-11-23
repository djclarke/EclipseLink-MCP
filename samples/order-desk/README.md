# order-desk (writes enabled)

The [sales-db](../sales-db) model with write operations switched on: one file per operation in `writes/`,
exposed because the descriptor sets `tools.writes: true`. See [../README.md](../README.md#order-desk).

| Tool | Action | Entity | Lock |
|---|---|---|---|
| `ship_order` | update | Order | `version` |
| `cancel_order` | update | Order | `version` |
| `update_customer_email` | update | Customer | `version` |
| `reprice_product` | update | Product | `version` |
| `add_order_line` | create | OrderLine | `orderVersion` (checked and incremented) |
| `change_line_quantity` | update | OrderLine | `orderVersion` |
| `remove_order_line` | delete | OrderLine | `orderVersion` |
