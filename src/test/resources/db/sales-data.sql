-- Seed data for the sales-db test database.
MERGE INTO CUSTOMER KEY(ID) VALUES
    (1, 'Acme Corp',        'EAST',  'orders@acme.example'),
    (2, 'Globex',           'WEST',  'buy@globex.example'),
    (3, 'Initech',          'EAST',  NULL),
    (4, 'Umbrella Traders', 'NORTH', 'sales@umbrella.example'),
    (5, 'Hooli',            'WEST',  'procurement@hooli.example');

MERGE INTO PRODUCT KEY(ID) VALUES
    (1, 'Widget',      'HARDWARE', 25.00),
    (2, 'Gadget',      'HARDWARE', 120.00),
    (3, 'Support Plan','SERVICES', 500.00),
    (4, 'Cable',       'HARDWARE', 5.50);

MERGE INTO ORDERS KEY(ID) VALUES
    (1001, 1, DATE '2026-07-03', 'SHIPPED',   1250.00),
    (1002, 1, DATE '2026-08-14', 'OPEN',       620.00),
    (1003, 2, DATE '2026-07-21', 'SHIPPED',   5500.00),
    (1004, 3, DATE '2026-09-02', 'CANCELLED',  240.00),
    (1005, 4, DATE '2026-09-15', 'OPEN',     12000.00),
    (1006, 5, DATE '2026-10-01', 'OPEN',       131.00),
    (1007, 2, DATE '2026-10-09', 'SHIPPED',    500.00);

MERGE INTO ORDER_LINE KEY(ID) VALUES
    (1, 1001, 1, 50,  25.00),
    (2, 1002, 2, 5,  120.00),
    (3, 1002, 3, 0,    0.00),
    (4, 1003, 3, 11, 500.00),
    (5, 1004, 2, 2,  120.00),
    (6, 1005, 3, 24, 500.00),
    (7, 1006, 2, 1,  120.00),
    (8, 1006, 4, 2,    5.50),
    (9, 1007, 3, 1,  500.00);
