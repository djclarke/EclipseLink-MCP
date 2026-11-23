# Open questions

Decisions to make before or during the first prototype. Proposed answers are marked as such.

## Stack

1. **MCP library:** official MCP Java SDK (`io.modelcontextprotocol.sdk`), or a framework (Spring AI MCP, Quarkus MCP)? *Proposed: plain MCP Java SDK, to keep dependencies small and EclipseLink central.*
2. **Java and build:** *Proposed: Java 21, Maven.*
3. **EclipseLink version:** build on released 5.0.x, or track 6.0.0-SNAPSHOT? Does the prototype need anything only in 6.0?
4. **Transport for milestone 1:** stdio only, or streamable HTTP too? OAuth needs HTTP. *Proposed: stdio first with roles from config, HTTP + OAuth in milestone 2.*

## Model

5. **Sample database:** which schema? Options: a small HR/Orders schema written for this repo, Sakila (MySQL sample), Chinook, or Oracle HR. *Proposed: a small Orders/Customers/Products schema on H2 or Derby so tests are self-contained.*
6. **Model source:** introspection only, or also support existing persistence units with static entities from the start?
7. **Override format:** `eclipselink-orm.xml`, or a simpler YAML/JSON model config that the server turns into mappings?
8. **Naming:** how to derive entity/attribute names from table/column names (camel case, singularization)?

## Security

9. **Policy format and location:** single YAML file alongside the model config, or annotations/ORM XML?
10. **Row filters:** use `@AdditionalCriteria` generated onto dynamic descriptors, or apply criteria in the query tool layer? (Descriptor-level is stronger since it covers relationship traversal.)
11. **Caching:** isolated cache per caller, shared cache with additional criteria, or no shared cache for filtered entities?
12. **Audit sink:** log file, database table, or OpenTelemetry?

## Scope

13. **JPQL tool:** include in milestone 1 (behind a flag) or defer?
14. **XML output:** needed for MCP clients, or JSON only with XML as a later option?
15. **Writes:** which milestone, and do they need approval flows (e.g. MCP elicitation) before committing?
16. **Repo relationship:** standalone project, or eventually a module/incubator under EclipseLink?
