-- Seed data for the sales-db test database.
MERGE INTO CUSTOMER KEY(ID) VALUES
    (1, 'Acme Corp', 'EAST', 'orders@acme.example', 1234567890123456.7891, 1),
    (2, 'Globex', 'WEST', 'buy@globex.example', 50000.0000, 1),
    (3, 'Initech', 'EAST', NULL, NULL, 1),
    (4, 'Umbrella Traders', 'NORTH', 'sales@umbrella.example', 250000.5000, 1),
    (5, 'Hooli', 'WEST', 'procurement@hooli.example', 0.0001, 1);

MERGE INTO PRODUCT KEY(ID) VALUES
    (1, 'Widget', 'HARDWARE', 25.00, 0.25, 1),
    (2, 'Gadget', 'HARDWARE', 120.00, 1.5, 1),
    (3, 'Support Plan', 'SERVICES', 500.00, NULL, 1),
    (4, 'Cable', 'HARDWARE', 5.50, 0.1, 1);

MERGE INTO ORDERS KEY(ID) VALUES
    (1001, 1, DATE '2026-07-03', 'SHIPPED', 1250.00, TIMESTAMP '2026-07-03 09:15:00', TIMESTAMP WITH TIME ZONE '2026-07-05 14:00:00-04:00', 1),
    (1002, 1, DATE '2026-08-14', 'OPEN', 620.00, TIMESTAMP '2026-08-14 16:42:07.250', NULL, 1),
    (1003, 2, DATE '2026-07-21', 'SHIPPED', 5500.00, TIMESTAMP '2026-07-21 08:00:00', TIMESTAMP WITH TIME ZONE '2026-07-22 10:30:00+00:00', 1),
    (1004, 3, DATE '2026-09-02', 'CANCELLED', 240.00, TIMESTAMP '2026-09-02 11:05:30', NULL, 1),
    (1005, 4, DATE '2026-09-15', 'OPEN', 12000.00, TIMESTAMP '2026-09-15 23:59:59', NULL, 1),
    (1006, 5, DATE '2026-10-01', 'OPEN', 131.00, TIMESTAMP '2026-10-01 00:00:00', NULL, 1),
    (1007, 2, DATE '2026-10-09', 'SHIPPED', 500.00, TIMESTAMP '2026-10-09 12:30:00', TIMESTAMP WITH TIME ZONE '2026-10-10 09:00:00+05:30', 1);

MERGE INTO ORDER_LINE KEY(ID) VALUES
    (1, 1001, 1, 50,  25.00, 'Widget'),
    (2, 1002, 2, 5,  120.00, 'Gadget'),
    (3, 1002, 3, 0,    0.00, 'Support Plan'),
    (4, 1003, 3, 11, 500.00, 'Support Plan'),
    (5, 1004, 2, 2,  120.00, 'Gadget'),
    (6, 1005, 3, 24, 500.00, 'Support Plan'),
    (7, 1006, 2, 1,  120.00, 'Gadget'),
    (8, 1006, 4, 2,    5.50, 'Cable'),
    (9, 1007, 3, 1,  500.00, 'Support Plan');
