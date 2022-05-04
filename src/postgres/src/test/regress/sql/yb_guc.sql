-- Check transaction priority bounds.

set log_error_verbosity = default;

-- Values should be in interval [0,1] (inclusive).
-- Invalid values.
set yb_transaction_priority_upper_bound = 2;
set yb_transaction_priority_lower_bound = -1;

-- Valid values.
set yb_transaction_priority_upper_bound = 1;
set yb_transaction_priority_lower_bound = 0;
set yb_transaction_priority_lower_bound = 0.3;
set yb_transaction_priority_upper_bound = 0.7;

-- Lower bound should be less or equal to upper bound.
-- Invalid values.
set yb_transaction_priority_upper_bound = 0.2;
set yb_transaction_priority_lower_bound = 0.8;

-- Valid values.
set yb_transaction_priority_upper_bound = 0.3;
set yb_transaction_priority_upper_bound = 0.6;
set yb_transaction_priority_lower_bound = 0.4;
set yb_transaction_priority_lower_bound = 0.6;

-- Check enable_seqscan, enable_indexscan, enable_indexonlyscan for YB scans.
CREATE TABLE test_scan (i int, j int);
CREATE INDEX NONCONCURRENTLY ON test_scan (j);
-- Don't add (costs off) to EXPLAIN to be able to see when disable_cost=1.0e10
-- is added.
set enable_seqscan = on;
set enable_indexscan = on;
set enable_indexonlyscan = on;
EXPLAIN SELECT * FROM test_scan;
EXPLAIN SELECT * FROM test_scan WHERE j = 1;
EXPLAIN SELECT j FROM test_scan;
set enable_seqscan = on;
set enable_indexscan = off;
EXPLAIN SELECT * FROM test_scan;
EXPLAIN SELECT * FROM test_scan WHERE j = 1;
EXPLAIN SELECT j FROM test_scan;
set enable_seqscan = off;
set enable_indexscan = off;
EXPLAIN SELECT * FROM test_scan;
EXPLAIN SELECT * FROM test_scan WHERE j = 1;
EXPLAIN SELECT j FROM test_scan;
set enable_seqscan = off;
set enable_indexscan = on;
EXPLAIN SELECT * FROM test_scan;
EXPLAIN SELECT * FROM test_scan WHERE j = 1;
EXPLAIN SELECT j FROM test_scan;
set enable_indexonlyscan = off;
EXPLAIN SELECT j FROM test_scan;
