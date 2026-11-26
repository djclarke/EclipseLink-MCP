# Agentic scenarios: Chinook music store

Five tasks an agent would do for a digital music store with the [music-store](music-store) server, each from
the user's request to the result. For each one: who asks and what they ask, the tools the agent calls in order,
what it finds, and what the scenario shows about EclipseLink MCP.

Every number below comes from running the calls against the PostgreSQL copy of Chinook this sample builds (see
[Run them yourself](#run-them-yourself)). Ids the database assigns (the new playlist and invoices) can differ on
yours.

| # | Scenario | Who asks | Shows |
|---|---|---|---|
| 1 | [Curate a playlist](#1-curate-a-road-trip-playlist) | Store editor | Reports, filtered queries with paging, many small locked writes |
| 2 | [Two editors, one playlist](#2-two-editors-one-playlist) | Store editor | `CONFLICT` through the parent's version, refresh, decide with the user |
| 3 | [A customer buys the rest of an album](#3-a-customer-buys-the-rest-of-an-album) | Customer support | Checking what the customer owns; an invoice and its lines in one transaction |
| 4 | ["I was charged twice"](#4-customer-support-i-was-charged-twice) | Customer support | Read paths across invoices, lines, tracks and albums; data the agent must not change |
| 5 | [Support team review](#5-sales-manager-support-team-review) | Sales manager | Reports across a self-referencing relationship |

## 1. Curate a road trip playlist

**Prompt.** *"Make a 'Road Trip' playlist: 20 rock tracks under five minutes from our best-selling rock artists,
a few from each."*

| Step | Tool | Arguments | What comes back |
|---|---|---|---|
| 1 | `report_best_selling_artists` | 2024-01-01 to 2026-01-01, `genre` Rock, `pageSize` 6 | U2 49 copies, Led Zeppelin 39, Iron Maiden 24, Queen 20, The Who 14, Pearl Jam 12 |
| 2 | `query_entities` | `Track`, filter `genre.name` eq Rock, `milliseconds` lt 300000, `album.artist.name` in [U2, Led Zeppelin, Iron Maiden, Queen]; select name, length, album title and artist; `pageSize` 200 | 200 of the 209 matches and a `nextCursor` for the rest: plenty to choose from, so the agent stops there |
| 3 | | | The agent proposes five per artist (the Achtung Baby openers, Led Zeppelin's BBC Sessions, Iron Maiden from Brave New World and Fear Of The Dark, Queen's Greatest Hits II) and the user agrees |
| 4 | `create_playlist` | `name` Road Trip | Playlist 19 at version 1 |
| 5 to 24 | `add_playlist_track` | `playlistId` 19, `trackId`, `playlistVersion` 1, then 2, 3 ... 20 | Each returns `locked`: the playlist's next version, which the agent passes to the next call |
| 25 | `query_playlist_tracks` | `name` Road Trip | 20 tracks, version 21 |

**Outcome.** A 20-track playlist from the four best-selling rock artists, every entry stamped with when it was
added. Had the agent used `report_top_artists` instead, it would have picked Iron Maiden, U2, Led Zeppelin and
Metallica, the artists with the most tracks in the catalogue, not the ones customers buy.

**What it shows.**

- **A report for the question asked.** `report_top_artists` counts tracks in the catalogue (Iron Maiden has the
  most); `report_best_selling_artists`, added for this scenario, counts what customers bought. The descriptions
  tell the agent which is which.
- **Ad hoc filters with `in`, `lt` and paths**, sorted and paged with an opaque cursor, so a large result
  never arrives in one response.
- **A child locked through its parent.** A playlist entry has no version: each add checks and increments the
  playlist's, so the agent always holds the current version from the last response.
- **Server-set values.** `addedAt` is the server's clock, not something the agent supplies.

## 2. Two editors, one playlist

While the agent was building the playlist, a colleague renamed it to *Road Trip 2026* (`update_playlist`,
version 22). The user, unaware, asks: *"Take out 'Communication Breakdown(2)', it's a repeat."*

| Step | Tool | Arguments | What comes back |
|---|---|---|---|
| 1 | `remove_playlist_track` | `id` {playlistId 19, trackId 343}, `playlistVersion` 21 | `CONFLICT`: version 22, you sent 21. Nothing was changed. Decide whether it is a duplicate, a conflict, or still applies; `details.refresh` is a `find_entity` call |
| 2 | `find_entity` | `details.refresh.arguments`: playlist 19 with its tracks, `refresh: true` | Name *Road Trip 2026*, version 22, the same 20 tracks |
| 3 | | | *"Someone renamed the playlist to 'Road Trip 2026'. The tracks haven't changed, so removing the repeat still makes sense. Go ahead?"* |
| 4 | `remove_playlist_track` | the same, with `playlistVersion` 22 | Removed; the playlist is at version 23 |

**Outcome.** The repeat is gone and the colleague's rename is kept. Had the colleague removed the track already,
the refresh would have shown it, and the agent would have reported a duplicate instead of calling again.

**What it shows.**

- **Lost updates are impossible.** The rename and the removal both changed the playlist; the second writer is
  told, and nothing is overwritten.
- **Refresh past the cache.** `refresh: true` reads the playlist and its entries from the database, so the
  decision is made on the current state.
- **The user decides.** The server's message gives the three cases; the agent explains what changed and asks
  before it retries with the new version.

## 3. A customer buys the rest of an album

**Prompt**, from a support agent with a customer on chat: *"Luís Gonçalves (luisg@embraer.com.br) wants the rest of
U2's Rattle And Hum. Sell it to him."*

| Step | Tool | Arguments | What comes back |
|---|---|---|---|
| 1 | `query_entities` | `Customer`, filter `email` eq luisg@embraer.com.br | Customer 1, Brazil, support rep Jane Peacock |
| 2 | `query_album_tracks` | `artist` U2 | Ten albums; Rattle And Hum has 17 tracks at 0.99 |
| 3 | `query_customer_invoices` | `customer` 1 | Seven invoices; one line, in May 2023, is *All Along The Watchtower* from Rattle And Hum |
| 4 | | | *"Luís already owns All Along The Watchtower. The other 16 tracks come to 15.84. Go ahead?"* |
| 5 | `create_invoice` | `customer` 1, `lines`: the 16 track ids, `quantity` 1 each | Invoice 413, dated now, billed to his address in São José dos Campos, total 15.84 |

**Outcome.** One invoice with 16 lines, created in one transaction. The customer is not charged twice for the
track he has.

**What it shows.**

- **A parent with its children in one call.** `create_invoice` creates the invoice and every line together;
  the database assigns all the ids. If any line fails, nothing is created.
- **Rules in the definition, not the prompt.** The billing address is copied from the customer, each line's
  price from the track, and the total is the sum of the lines. The agent cannot set any of them.
- **Reading before writing.** `query_customer_invoices` follows invoice, lines, track and album in one call,
  which is how the agent notices the track he already owns.

## 4. Customer support: "I was charged twice"

Set-up: the store's checkout sent Luís's purchase twice (run step 5 of scenario 3 twice).

**Prompt.** *"Luís Gonçalves says he was charged twice for Rattle And Hum today. Can you sort it out?"*

| Step | Tool | Arguments | What comes back |
|---|---|---|---|
| 1 | `query_customer_invoices` | `customer` 1, `pageSize` 3 | Invoices 414 and 413, 1.5 seconds apart, each 15.84, each the same 16 Rattle And Hum tracks |
| 2 | | | The agent compares the two: same customer, same tracks, same prices, same billing address |

**Outcome.** *"He's right: invoices 413 and 414 are identical and were created 1.5 seconds apart, so 414 is a
duplicate. I can't change or cancel an invoice here. Here is the case for billing: customer 1, Luís Gonçalves;
duplicate invoice 414, 15.84, 16 lines; original 413 to keep."* The agent writes up the case instead of trying
a workaround, and does not offer to "fix" it with another write.

**What it shows.**

- **Some data cannot be changed by an agent, by design.** `Invoice` allows only `create` in `tools.writes`,
  and `InvoiceLine` is not listed, so there is no update or delete tool for them, and the database account has
  no grant for it either. Refunds stay with people and the billing system.
- **One call for the whole picture.** The named query returns each invoice with its lines, tracks and albums,
  so the agent can prove the duplicate rather than guess.

## 5. Sales manager: support team review

**Prompt**, from Nancy Edwards, the sales manager: *"How did my support team do in 2025 compared with 2024, and
which of their customers didn't buy anything last year?"*

| Step | Tool | Arguments | What comes back |
|---|---|---|---|
| 1 | `query_org_chart` | `title` `Sales Support%` | Jane Peacock (21 customers), Margaret Park (20), Steve Johnson (18), all reporting to Nancy Edwards |
| 2 | `report_sales_by_support_rep` | 2025-01-01 to 2026-01-01 | Margaret 26 invoices, 168.30; Jane 31, 156.43; Steve 23, 125.85 |
| 3 | `report_sales_by_support_rep` | 2024-01-01 to 2025-01-01 | Margaret 29, 197.20; Jane 28, 146.60; Steve 26, 133.73 |
| 4 | `query_entities` | `Invoice`, 2025 dates, select `customer.id` (all pages) | The customers who bought in 2025 |
| 5 | | | Customers from step 1 not in step 4 |

**Outcome.** *"Jane is the only one up on 2024 (+7%); Margaret is down 15% but still the largest; Steve is down
6%. Thirteen customers bought nothing in 2025: four of Jane's, four of Margaret's and five of Steve's,"* followed
by the names, grouped by rep, as a call list.

**What it shows.**

- **A self-referencing relationship.** `Employee.reportsTo` and `directReports` come from one foreign key; the
  query follows both directions and each employee's customers in one call.
- **Reports over a path.** `sales_by_support_rep` groups invoices by `customer.supportRep`, two relationships
  away, without the agent writing a join.
- **Combining tools.** Where no report answers a question exactly (customers with no invoice), the agent
  combines a query and a report, still read-only.

## Run them yourself

With the database set up and the server built (see the [README](README.md#run-it)), every step above is one
`call`. Write tools are a dry run unless you add `--commit`.

```sh
call() { java -jar "$JAR" call --definition samples/chinook/music-store --drivers target/chinook/drivers "$@"; }

# 1. Curate a playlist
call report_best_selling_artists '{"fromDate":"2024-01-01T00:00:00","toDate":"2026-01-01T00:00:00","genre":"Rock","pageSize":6}'
call query_entities '{"entity":"Track","filter":{"and":[{"path":"genre.name","op":"eq","value":"Rock"},
  {"path":"milliseconds","op":"lt","value":300000},
  {"path":"album.artist.name","op":"in","value":["U2","Led Zeppelin","Iron Maiden","Queen"]}]},
  "select":["id","name","milliseconds","album.title","album.artist.name"],"pageSize":200}'
call --commit create_playlist '{"name":"Road Trip"}'                                    # note the id: 19 here
v=1; for t in 339 338 342 343 341 1201 1235 1239 1241 1256 419 420 422 423 425 2926 2927 2928 2929 2932; do
  call --commit add_playlist_track '{"playlistId":19,"trackId":'$t',"playlistVersion":'$v'}' >/dev/null; v=$((v+1))
done
call query_playlist_tracks '{"name":"Road Trip"}'

# 2. Two editors: the colleague renames it, then the agent removes a track with the version it read
call --commit update_playlist '{"id":19,"version":21,"name":"Road Trip 2026"}'
call remove_playlist_track '{"id":{"playlistId":19,"trackId":343},"playlistVersion":21}'     # CONFLICT
call find_entity '{"entity":"Playlist","id":19,"select":["id","name","version","tracks"],"refresh":true}'

# 3 and 4. Buy the rest of Rattle And Hum; run the create twice for scenario 4
call query_customer_invoices '{"customer":1}'
call --commit create_invoice '{"customer":1,"lines":[{"track":2987,"quantity":1},{"track":2988,"quantity":1},
  {"track":2989,"quantity":1},{"track":2990,"quantity":1},{"track":2992,"quantity":1},{"track":2993,"quantity":1},
  {"track":2994,"quantity":1},{"track":2995,"quantity":1},{"track":2996,"quantity":1},{"track":2997,"quantity":1},
  {"track":2998,"quantity":1},{"track":2999,"quantity":1},{"track":3000,"quantity":1},{"track":3001,"quantity":1},
  {"track":3002,"quantity":1},{"track":3003,"quantity":1}]}'

# 5. Support team review
call query_org_chart '{"title":"Sales Support%"}'
call report_sales_by_support_rep '{"fromDate":"2025-01-01T00:00:00","toDate":"2026-01-01T00:00:00"}'
```

The committed calls change the database: run `db/setup-postgresql.sh` again to start from the published data.
To try the scenarios with an agent, connect the server to Claude and give it the prompts.
