# EclipseLink MCP

An [MCP](https://modelcontextprotocol.io) server built on EclipseLink that lets AI agents work with an existing relational database through a mapped object model rather than raw SQL.

The server builds a JPA model from an existing schema (dynamic entities, no code generation), exposes the same model as JSON and XML through MOXy, and offers agents typed, relationship-aware tools such as list, describe, find and query. Every request runs with the caller's roles applied, so row filters, field visibility and the set of available tools all follow from who is calling.

## Why EclipseLink

- **One model, two bindings.** JPA maps the database; MOXy maps the same entities to JSON/XML and JSON Schema for MCP tool inputs and outputs.
- **Safety by construction.** Agents use mapped entities and a constrained query surface instead of free-form SQL. Access is enforced by the persistence layer (`@AdditionalCriteria`, multitenancy, fetch groups), not by prompts.
- **Better grounding.** Relationships, keys and constraints give a model far more context than a list of tables.

## Status

Requirements phase. See:

- [docs/requirements.md](docs/requirements.md) for goals, scope and functional/security requirements
- [docs/open-questions.md](docs/open-questions.md) for decisions still to make

## First milestone (proposed)

A read-only MCP server over a sample schema with `list_entities`, `describe_entity`, `find_entity` and a safe `query` tool, with role-based row and field filtering from day one.

## Planned stack (to be confirmed)

Java 21, Maven, EclipseLink 5.x/6.0, the MCP Java SDK, and an embedded sample database.

## License

Eclipse Public License 2.0. See [LICENSE](LICENSE).
