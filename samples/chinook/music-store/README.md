# music-store (Chinook, read-write)

The finished server definition of the [Chinook sample](../README.md): the model `init` generated in
[../chinook-generated](../chinook-generated), shaped by [../shape-music-store.sh](../shape-music-store.sh), with five
named reports (`reports/`), four named queries (`queries/`) and fourteen write operations (`writes/`), enabled per
entity under `tools.writes` in `eclipselink-mcp.yaml`.

It connects as `mcp_store`, the account [../db/chinook-mcp-postgresql.sql](../db/chinook-mcp-postgresql.sql)
creates with only the grants these writes need. [../README.md](../README.md) has the setup, the tools, and a
lesson-by-lesson map of the SQLite tutorial.
