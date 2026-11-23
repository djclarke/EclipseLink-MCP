# EclipseLink MCP

A factory for [MCP](https://modelcontextprotocol.io) servers built on EclipseLink, letting AI agents work with an existing relational database through a mapped object model rather than raw SQL.

Each deployment is a configuration: a database connection and account, a model (introspected from the schema as dynamic entities with no code generation, or an existing persistence unit), named reports and tool settings. The runtime turns that into an MCP server that exposes the model as JSON and XML through MOXy, with typed, relationship-aware tools such as list, describe, find and query.

It comes in two forms:

- **Standalone runtime:** point it at a schema and a config file and get an MCP server, with no code. This is the POC.
- **Embeddable library:** an application with existing JPA entities adds a dependency and exposes its own model and reports over MCP.

A role-based security model (row filters, field visibility and tools per caller) is planned for after the POC.

## Why EclipseLink

- **One model, two bindings.** JPA maps the database; MOXy maps the same entities to JSON/XML and JSON Schema for MCP tool inputs and outputs.
- **Safety by construction.** Agents use mapped entities and a constrained query surface instead of free-form SQL. Access is enforced by the persistence layer (`@AdditionalCriteria`, multitenancy, fetch groups), not by prompts.
- **Better grounding.** Relationships, keys and constraints give a model far more context than a list of tables.

## Status

Requirements phase. See:

- [docs/requirements.md](docs/requirements.md) for goals, scope and functional/security requirements
- [docs/open-questions.md](docs/open-questions.md) for decisions still to make

## First milestone: POC

The standalone runtime, read-only, over a sample schema, with `list_entities`, `describe_entity`, `find_entity`, a safe `query_entities` tool and named report tools for multi-table projections. Access is whatever the one shared database account can read; the security model follows the POC.

## Planned stack (to be confirmed)

Java 21, Maven, EclipseLink 5.x/6.0, the MCP Java SDK, and an embedded sample database.

## License

Eclipse Public License 2.0. See [LICENSE](LICENSE).
