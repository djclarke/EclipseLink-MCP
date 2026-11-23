-- Builds the sales-db sample database from scratch: schema, read-only account, seed data.
-- Used by both `mvn -Psample-db process-test-resources` and the test harness (TestDatabase).
DROP ALL OBJECTS;
RUNSCRIPT FROM 'classpath:/db/sales-schema.sql';
RUNSCRIPT FROM 'classpath:/db/sales-data.sql';
