# EclipseLink MCP

A factory for [MCP](https://modelcontextprotocol.io) servers built on EclipseLink, letting AI agents work with an existing relational database through a mapped object model rather than raw SQL.

Each deployment is a configuration: a database connection and account, a model (generated from the schema as dynamic entities, with no code), named reports and tool settings. The runtime turns that into an MCP server that exposes the model as JSON and XML through MOXy, with typed, relationship-aware tools such as list, describe, find and query.

It is delivered as a **generated, codeless standalone server**: point the runtime at a database and a generated server definition, and you get an MCP server with no code to write. Embedding in existing applications is not planned for now.

A role-based security model (row filters, field visibility and tools per caller) is planned for after the POC.

## Why EclipseLink

- **One model, two bindings.** JPA maps the database; MOXy maps the same entities to JSON/XML and JSON Schema for MCP tool inputs and outputs.
- **Safety by construction.** Agents use mapped entities and a constrained query surface instead of free-form SQL. Access is enforced by the persistence layer (`@AdditionalCriteria`, multitenancy, fetch groups), not by prompts.
- **Better grounding.** Relationships, keys and constraints give a model far more context than a list of tables.

## Status

Requirements phase. See:

- [docs/requirements.md](docs/requirements.md) for goals, scope and functional/security requirements
- [docs/server-definition.md](docs/server-definition.md) for the server definition: packaging, hosting and the full MCP surface (tools, resources, prompts)
- [docs/open-questions.md](docs/open-questions.md) for decisions still to make

## First milestone: POC

The standalone runtime, read-only, over a sample schema, with `list_entities`, `describe_entity`, `find_entity`, a safe `query_entities` tool and named report tools for multi-table projections. Access is whatever the one shared database account can read; the security model follows the POC.

## Planned stack

- MCP: the official [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) core module, no Spring (decided). Spring and Quarkus integrations may come later as adapters.
- Java 21 and Maven (proposed).
- EclipseLink 5.0.x or 6.0.0-SNAPSHOT, and the sample database: still open, see [open questions](docs/open-questions.md).

## License

Eclipse Public License 2.0. See [LICENSE](LICENSE).
