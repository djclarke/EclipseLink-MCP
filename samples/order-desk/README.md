# order-desk (writes enabled)

The [sales-db](../sales-db) model with writes enabled for two entities: the descriptor lists
`tools.writes: [Order, OrderLine]`, so the operations in `writes/` on those entities become tools and the rest
stay hidden. See [../README.md](../README.md#order-desk).

| Tool | Action | Entity | Lock | Exposed |
|---|---|---|---|---|
| `ship_order` | update | Order | `version` | yes |
| `cancel_order` | update | Order | `version` | yes |
| `add_order_line` | create | OrderLine | `orderVersion` (checked and incremented) | yes |
| `change_line_quantity` | update | OrderLine | `orderVersion` | yes |
| `remove_order_line` | delete | OrderLine | `orderVersion` | yes |
| `update_customer_email` | update | Customer | `version` | no: add `Customer` to `tools.writes` |
| `reprice_product` | update | Product | `version` | no: add `Product` to `tools.writes` |
