# order-desk (writes enabled)

The [sales-db](../sales-db) model with writes enabled for three entities. The descriptor's `tools.writes`
allows `Order: [update]`, `OrderLine: [create, update, delete]` and `Customer: [create, update]`, so the
operations in `writes/` with those entities and actions become tools and the rest stay hidden. See [../README.md](../README.md#order-desk).

| Tool | Action | Entity | Lock | Exposed |
|---|---|---|---|---|
| `ship_order` | update | Order | `version` | yes |
| `cancel_order` | update | Order | `version` | yes |
| `add_order_line` | create | OrderLine | `orderVersion` (checked and incremented); copies the product's price and name | yes |
| `change_line_quantity` | update | OrderLine | `orderVersion` | yes |
| `remove_order_line` | delete | OrderLine | `orderVersion` | yes |
| `create_customer` | create | Customer | starts at version 1 | yes |
| `update_customer_email` | update | Customer | `version` | yes |
| `reprice_product` | update | Product | `version` | no: add `Product: [update]` to `tools.writes` |
