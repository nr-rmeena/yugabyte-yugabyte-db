CREATE USER su WITH SUPERUSER;

SET ROLE su;
CREATE EXTENSION pg_stat_monitor;

CREATE USER u1;
CREATE USER u2;

SET ROLE su;

SELECT pg_stat_monitor_reset();
SET ROLE u1;
CREATE TABLE t1 (a int);
SELECT * FROM t1;

SET ROLE su;
SELECT userid, query FROM pg_stat_monitor ORDER BY query COLLATE "C";
SELECT pg_stat_monitor_reset();

DROP TABLE t1;
DROP TABLE t2;

DROP USER u1;
DROP USER u2;

--
-- create / alter user
--
SELECT pg_stat_monitor_reset();
CREATE USER foo PASSWORD 'foo';
ALTER USER foo PASSWORD 'foo2';
CREATE ROLE bar PASSWORD 'bar';
ALTER ROLE bar PASSWORD 'bar2';

SELECT userid, query FROM pg_stat_monitor ORDER BY query COLLATE "C";

DROP USER foo;
DROP ROLE bar;

DROP EXTENSION pg_stat_monitor;
