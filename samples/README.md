# Samples

Two server definitions over the same sample H2 database, a before and after of the
[configure-server-from-schema](../skills/configure-server-from-schema/SKILL.md) skill.
`mvn test` creates the database at `target/test-db/sales` (or `mvn -Psample-db process-test-resources` without the tests).

| Folder | What it is |
|---|---|
| [sales-db-generated](sales-db-generated) | The raw `init` output for every table, untouched. What you get first. |
| [sales-db](sales-db) | The shaped definition: the model simplified and described, plus named reports and a named query. |

## sales-db-generated

Made by this command, run from the repository root:

```sh
export SALES_DB_PASSWORD=reader   # the sample's read-only account
java -jar target/eclipselink-mcp-server-*.jar init \
  --url 'jdbc:h2:file:./target/test-db/sales;AUTO_SERVER=TRUE' \
  --user MCP_READER --password-env SALES_DB_PASSWORD \
  --name sales --include '*' --out samples/sales-db-generated
```

Every file in the folder, including its `README.md`, is `init` output. Do not edit it by hand:
`GeneratedSampleTest` regenerates it on every `mvn test` and fails on any difference. When a change to
`init` is intended, refresh the folder and commit the diff with that change:

```sh
mvn test -Dtest=GeneratedSampleTest -Dgolden.update=true
```

## sales-db

The shaped version of the same tables. Apart from element order and formatting, its model is what these
validated edits produce from the generated one:

```sh
java -jar $JAR edit --definition <dir> rename-entity Orders Order
java -jar $JAR edit --definition <dir> rename-attribute Order orderLines lines
java -jar $JAR edit --definition <dir> remove-attribute Product orderLines
java -jar $JAR edit --definition <dir> describe Customer "A customer account"
# ... and a describe for each other entity and attribute that carries a description
```

The descriptor was then given a title, agent instructions and limits; two reports and one query were
added under `reports/` and `queries/`. The tests run against this definition.
