# Open questions

Decisions to make before or during the first prototype. Proposed answers are marked as such.

## Stack

1. **MCP library:** official MCP Java SDK (`io.modelcontextprotocol.sdk`), or a framework (Spring AI MCP, Quarkus MCP)? *Decided (2026-11-23): the official MCP Java SDK ([modelcontextprotocol/java-sdk](https://github.com/modelcontextprotocol/java-sdk)) core module, without Spring, in the runtime. It is the reference implementation, tracks the spec (stdio, streamable HTTP, OAuth), and is plain Java. Spring AI MCP (e.g. a Spring Boot starter) and the Quarkus MCP server extension are possible later adapters, not the base. LangChain4j is mainly client-side and not used.*
2. **Java and build:** *Proposed: Java 21, Maven.*
3. **EclipseLink version:** build on released 5.0.x, or track 6.0.0-SNAPSHOT? Does the prototype need anything only in 6.0?
4. **Transport for milestone 1:** stdio only, or streamable HTTP too? OAuth needs HTTP. *Proposed: stdio first with roles from config, HTTP + OAuth in milestone 2.*
4a. **Module layout:** one Maven module, or a core module plus a launcher module? *Proposed: one module for the POC, now that there is no embeddable library (requirements 2); keep model building, binding and tools in separate packages so a split stays easy.*

## Model

5. **Sample database:** which schema? Options: a small HR/Orders schema written for this repo, Sakila (MySQL sample), Chinook, or Oracle HR. *Proposed: a small Orders/Customers/Products schema on H2 or Derby so tests are self-contained.*
6. **Model source:** introspection only, or also existing persistence units with static entities? *Decided: generated dynamic entities only (codeless).*
7. **Override format:** `eclipselink-orm.xml`, or a simpler YAML/JSON model config that the server turns into mappings?
8. **Naming:** how to derive entity/attribute names from table/column names (camel case, singularization)?

## Security

9. **Policy format and location:** single YAML file alongside the model config, or annotations/ORM XML?
10. **Row filters:** use `@AdditionalCriteria` generated onto dynamic descriptors, or apply criteria in the query tool layer? (Descriptor-level is stronger since it covers relationship traversal.)
11. **Caching:** isolated cache per caller, shared cache with additional criteria, or no shared cache for filtered entities?
12. **Audit sink:** log file, database table, or OpenTelemetry?
13. **Connection pooling:** EclipseLink's internal pool, or an external `DataSource` such as HikariCP? *Decided: internal pool for milestone 1, with a config switch to an external `DataSource` for the HTTP/multi-user milestone (see NFR-6).*

13a. **Multi-caller database identity:** when one server serves callers with different permissions, do we run one instance per permission set, map each caller to its own database identity (exclusive connections or Oracle proxy authentication), or enforce the in-app policy layer over one shared account? *Decided for now: one shared account, and any authenticated user can read whatever that account can access (requirements 5.0). Per-caller identity or policy is deferred.*

## Scope

14. **JPQL tool:** include in milestone 1 (behind a flag) or defer?
15. **Report queries in milestone 1:** ad hoc `report_query` tool, named reports only, or both? *Decided: named reports only, no ad hoc reports from MCP callers (see 4.5).*
16. **XML output:** needed for MCP clients, or JSON only with XML as a later option?
17. **Writes:** which milestone, and do they need approval flows (e.g. MCP elicitation) before committing?
18. **Repo relationship:** standalone project, or eventually a module/incubator under EclipseLink?
