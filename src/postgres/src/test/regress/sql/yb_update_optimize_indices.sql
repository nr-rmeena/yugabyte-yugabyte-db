SET yb_fetch_row_limit TO 1024;
SET yb_explain_hide_non_deterministic_fields TO true;
SET yb_update_num_cols_to_compare TO 50;
SET yb_update_max_cols_size_to_compare TO 10240;

-- This test requires the t-server gflag 'ysql_skip_row_lock_for_update' to be set to false.

-- CREATE a table with a primary key and no secondary indexes
DROP TABLE IF EXISTS pkey_only_table;
CREATE TABLE pkey_only_table (h INT PRIMARY KEY, v1 INT, v2 INT);
INSERT INTO pkey_only_table (SELECT i, i, i FROM generate_series(1, 10240) AS i);
EXPLAIN (ANALYZE, DIST, COSTS OFF) SELECT * FROM pkey_only_table;

-- Updating non-index column should be done in a single op
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE pkey_only_table SET v1 = 1 WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE pkey_only_table SET v1 = 1, v2 = 1 WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE pkey_only_table SET v1 = 2 WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE pkey_only_table SET v1 = 3, v2 = 3 WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE pkey_only_table SET v1 = v1 + 1, v2 = v1 + 1 WHERE h = 1;

-- Setting index column in the update should trigger a read, but no update of the index
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE pkey_only_table SET h = 1 WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE pkey_only_table SET v1 = 1, h = 1 WHERE h = 1;
-- TODO: Fix bug here
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE pkey_only_table SET v1 = 1, h = h WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE pkey_only_table SET v1 = 1, h = v1 WHERE h = 1;

-- CREATE a table with a multi-column secondary index
DROP TABLE IF EXISTS secindex_only_table;
CREATE TABLE secindex_only_table (h INT, v1 INT, v2 INT, v3 INT);
CREATE INDEX NONCONCURRENTLY secindex_only_table_v1_v2 ON secindex_only_table((v1, v2) HASH);
INSERT INTO secindex_only_table (SELECT i, i, i, i FROM generate_series(1, 10240) AS i);

-- Setting the secondary index columns in the should trigger a read, but no update of the index
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET v1 = 1 WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET v1 = v1 WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET v1 = h WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET v1 = h, h = h WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET v1 = h, h = v1 WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET v1 = h, h = v1 + v2 - h WHERE h = 1;

-- Same cases as above, but not providing the primary key
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET v1 = 1 WHERE v1 = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET v1 = v1 WHERE v1 = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET v1 = h WHERE v1 = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET v1 = h, h = h WHERE v1 = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET v1 = h, h = v1 WHERE v1 = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET v1 = h, h = v1 + v2 - h WHERE v1 = 1;

-- Queries with non-leading secondary index
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE secindex_only_table SET h = h WHERE h = 1;
--

-- CREATE a table having secondary indices with NULL values
DROP TABLE IF EXISTS nullable_table;
CREATE TABLE nullable_table (h INT PRIMARY KEY, v1 INT, v2 INT, v3 INT);
CREATE INDEX NONCONCURRENTLY nullable_table_v1_v2 ON nullable_table ((v1, v2) HASH);
INSERT INTO nullable_table (SELECT i, NULL, NULL, NULL FROM generate_series(1, 100) AS i);

EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE nullable_table SET v1 = NULL WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE nullable_table SET v1 = NULL, v2 = NULL WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE nullable_table SET h = 1, v1 = NULL, v2 = NULL WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE nullable_table SET h = h, v1 = NULL, v2 = NULL WHERE h = 1;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE nullable_table SET h = h, v1 = NULL, v2 = NULL WHERE v1 = NULL;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE nullable_table SET h = h, v1 = NULL, v2 = NULL WHERE v1 = NULL OR v2 = NULL;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE nullable_table SET h = h, v1 = NULL, v2 = NULL WHERE v1 = NULL AND v2 = NULL;

-- CREATE a table having secondary indices in a colocated database.


-- CREATE a table having indexes of multiple data types
DROP TABLE IF EXISTS number_type_table;
CREATE TABLE number_type_table (h INT PRIMARY KEY, v1 INT2, v2 INT4, v3 INT8, v4 BIGINT, v5 FLOAT4, v6 FLOAT8, v7 SERIAL, v8 BIGSERIAL, v9 NUMERIC(10, 4), v10 NUMERIC(100, 40));
CREATE INDEX NONCONCURRENTLY number_type_table_v1_v2 ON number_type_table (v1 ASC, v2 DESC);
CREATE INDEX NONCONCURRENTLY number_type_table_v3 ON number_type_table (v3 HASH);
CREATE INDEX NONCONCURRENTLY number_type_table_v4_v5_v6 ON number_type_table ((v4, v5) HASH, v6 ASC);
CREATE INDEX NONCONCURRENTLY number_type_table_v7_v8 ON number_type_table (v7 HASH, v8 DESC);
CREATE INDEX NONCONCURRENTLY number_type_table_v9_v10 ON number_type_table (v9 HASH) INCLUDE (v10, v1);

INSERT INTO number_type_table(h, v1, v2, v3, v4, v5, v6, v9, v10) VALUES (0, 1, 2, 3, 4, 5.0, 6.000001, '-9.1234', '-10.123455789');
INSERT INTO number_type_table(h, v1, v2, v3, v4, v5, v6, v9, v10) VALUES (10, 11, 12, 13, 14, 15.01, 16.000002, '-19.1234', '-20.123455789');

EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE number_type_table SET h = 0, v1 = 1, v2 = 2, v3 = 3, v4 = 4, v5 = 5, v6 = 6.000001, v7 = 1, v8 = 1, v9 = '-9.12344', v10 = '-10.123455789' WHERE h = 0;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE number_type_table SET h = h + 1, v1 = 1, v2 = 2, v3 = 3, v4 = 4, v5 = 5, v6 = 6.000001, v7 = 1, v8 = 1, v9 = '-9.12344', v10 = '-10.123455789' WHERE h = 0;
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE number_type_table SET h = h + 1, v1 = 1, v2 = 2, v3 = 3, v4 = 4, v5 = 5, v6 = 6.000001, v7 = 1, v8 = 1, v9 = '-9.12344', v10 = '-10.123455789';

CREATE TYPE mood_type AS ENUM ('sad', 'ok', 'happy');
DROP TABLE IF EXISTS non_number_type_table;
CREATE TABLE non_number_type_table(tscol TIMESTAMP PRIMARY KEY, varcharcol VARCHAR(8), charcol CHAR(8), textcol TEXT, linecol LINE, ipcol CIDR, uuidcol UUID, enumcol mood_type);
CREATE INDEX NONCONCURRENTLY non_number_type_table_mixed ON non_number_type_table(tscol, varcharcol);
CREATE INDEX NONCONCURRENTLY non_number_type_table_text ON non_number_type_table((varcharcol, charcol) HASH, textcol ASC);
CREATE INDEX NONCONCURRENTLY non_number_type_table_text_hash ON non_number_type_table(textcol HASH);
-- This should fail as indexes on geometric and network types are not yet supported
CREATE INDEX NONCONCURRENTLY non_number_type_table_text_geom_ip ON non_number_type_table(linecol ASC, ipcol DESC);
CREATE INDEX NONCONCURRENTLY non_number_type_table_uuid_enum ON non_number_type_table(uuidcol ASC, enumcol DESC);

INSERT INTO non_number_type_table VALUES('1999-01-08 04:05:06 -8:00', 'varchar1', 'charpad', 'I am batman', '{1, 2, 3}'::line, '1.2.3.0/24'::cidr, 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid, 'happy'::mood_type);
EXPLAIN (ANALYZE, DIST, COSTS OFF) UPDATE non_number_type_table SET tscol = 'January 8 04:05:06 1999 PST', varcharcol = 'varchar1', charcol = 'charpad', textcol = 'I am not batman :(', linecol = '{1, 2, 3}'::line, ipcol = '1.2.3'::cidr, uuidcol = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid, enumcol = 'happy' WHERE tscol = 'January 8 07:05:06 1999 EST';
