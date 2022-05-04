// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

package org.yb.pgsql;

import static org.yb.AssertionWrappers.*;

import java.sql.ResultSet;
import java.sql.Statement;

import org.apache.commons.lang3.time.StopWatch;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.yb.util.YBTestRunnerNonTsanOnly;

@RunWith(value=YBTestRunnerNonTsanOnly.class)
public class TestYsqlMetrics extends BasePgSQLTest {
  private static final Logger LOG = LoggerFactory.getLogger(TestYsqlMetrics.class);

  @Test
  public void testMetrics() throws Exception {
    Statement statement = connection.createStatement();

    // DDL is non-txn.
    verifyStatementMetric(statement, "CREATE TABLE test (k int PRIMARY KEY, v int)",
                          OTHER_STMT_METRIC, 1, 0, 1, true);

    // Select uses txn.
    verifyStatementMetric(statement, "SELECT * FROM test",
                          SELECT_STMT_METRIC, 1, 1, 1, true);

    // Non-txn insert.
    verifyStatementMetric(statement, "INSERT INTO test VALUES (1, 1)",
                          INSERT_STMT_METRIC, 1, 0, 1, true);
    // Txn insert.
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "INSERT INTO test VALUES (2, 2)",
                          INSERT_STMT_METRIC, 1, 1, 0, true);
    verifyStatementMetric(statement, "END",
                          COMMIT_STMT_METRIC, 1, 0, 1, true);

    // Limit query on complex view (issue #3811).
    verifyStatementMetric(statement, "SELECT * FROM information_schema.key_column_usage LIMIT 1",
                          SELECT_STMT_METRIC, 1, 1, 1, true);

    // Non-txn update.
    verifyStatementMetric(statement, "UPDATE test SET v = 2 WHERE k = 1",
                          UPDATE_STMT_METRIC, 1, 0, 1, true);
    // Txn update.
    verifyStatementMetric(statement, "UPDATE test SET v = 3",
                          UPDATE_STMT_METRIC, 1, 1, 1, true);

    // Non-txn delete.
    verifyStatementMetric(statement, "DELETE FROM test WHERE k = 2",
                          DELETE_STMT_METRIC, 1, 0, 1, true);
    // Txn delete.
    verifyStatementMetric(statement, "DELETE FROM test",
                          DELETE_STMT_METRIC, 1, 1, 1, true);

    // Invalid statement should not update metrics.
    verifyStatementMetric(statement, "INSERT INTO invalid_table VALUES (1)",
                          INSERT_STMT_METRIC, 0, 0, 0, false);

    // DML queries transaction block.
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "INSERT INTO test VALUES (3, 3)",
                          INSERT_STMT_METRIC, 1, 1, 0, true);
    verifyStatementMetric(statement, "INSERT INTO test VALUES (4, 4)",
                          INSERT_STMT_METRIC, 1, 1, 0, true);
    verifyStatementMetric(statement, "END",
                          COMMIT_STMT_METRIC, 1, 0, 1, true);

    // DDL queries transaction block.
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "CREATE TABLE test2 (a int)",
                          OTHER_STMT_METRIC, 1, 0, 1, true);
    verifyStatementMetric(statement, "DROP TABLE test2",
                          OTHER_STMT_METRIC, 1, 0, 1, true);
    verifyStatementMetric(statement, "END",
                          COMMIT_STMT_METRIC, 1, 0, 0, true);

    // Set session variable in transaction block.
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "SET yb_debug_report_error_stacktrace=true;",
                          OTHER_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "COMMIT",
                          COMMIT_STMT_METRIC, 1, 0, 1, true);

    // DML/DDL queries transaction block.
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "INSERT INTO test VALUES (5, 5)",
                          INSERT_STMT_METRIC, 1, 1, 0, true);
    verifyStatementMetric(statement, "CREATE TABLE test2 (a int)",
                          OTHER_STMT_METRIC, 1, 0, 1, true);
    verifyStatementMetric(statement, "INSERT INTO test VALUES (6, 6)",
                          INSERT_STMT_METRIC, 1, 1, 0, true);
    verifyStatementMetric(statement, "UPDATE test SET k = 600 WHERE k = 6",
                          UPDATE_STMT_METRIC, 1, 1, 0, true);
    verifyStatementMetric(statement, "ALTER TABLE test2 ADD COLUMN b INT",
                          OTHER_STMT_METRIC, 1, 0, 1, true);
    verifyStatementMetric(statement, "DROP TABLE test2",
                          OTHER_STMT_METRIC, 1, 0, 1, true);
    verifyStatementMetric(statement, "COMMIT",
                          COMMIT_STMT_METRIC, 1, 0, 1, true);

    // DML/DDL queries transaction block with rollback.
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "INSERT INTO test VALUES (7, 7)",
                          INSERT_STMT_METRIC, 1, 1, 0, true);
    verifyStatementMetric(statement, "CREATE TABLE test2 (a int)",
                          OTHER_STMT_METRIC, 1, 0, 1, true);
    verifyStatementMetric(statement, "INSERT INTO test VALUES (8, 8)",
                          INSERT_STMT_METRIC, 1, 1, 0, true);
    verifyStatementMetric(statement, "UPDATE test SET k = 800 WHERE k = 8",
                          UPDATE_STMT_METRIC, 1, 1, 0, true);
    verifyStatementMetric(statement, "ALTER TABLE test2 ADD COLUMN b INT",
                          OTHER_STMT_METRIC, 1, 0, 1, true);
    verifyStatementMetric(statement, "DROP TABLE test2",
                          OTHER_STMT_METRIC, 1, 0, 1, true);
    verifyStatementMetric(statement, "ROLLBACK",
                          ROLLBACK_STMT_METRIC, 1, 0, 0, true);

    // Nested transaction block.
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "CREATE TABLE test2 (a int)",
                          OTHER_STMT_METRIC, 1, 0, 1, true);
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "INSERT INTO test VALUES (9, 9)",
                          INSERT_STMT_METRIC, 1, 1, 0, true);
    verifyStatementMetric(statement, "END",
                          COMMIT_STMT_METRIC, 1, 0, 1, true);

    // Nested transaction block with empty inner transaction block.
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "DELETE FROM test WHERE k = 9;",
                          DELETE_STMT_METRIC, 1, 1, 0, true);
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "END",
                          COMMIT_STMT_METRIC, 1, 0, 1, true);

    // Invalid transaction block.
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "INSERT INTO invalid_table VALUES (1)",
                          INSERT_STMT_METRIC, 0, 0, 0, false);
    verifyStatementMetric(statement, "END",
                          COMMIT_STMT_METRIC, 1, 0, 0, true);

    // Empty transaction block.
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "END",
                          COMMIT_STMT_METRIC, 1, 0, 0, true);

    // Empty transaction block with DML execution prior to BEGIN.
    verifyStatementMetric(statement, "INSERT INTO test VALUES (10, 10)",
                          INSERT_STMT_METRIC, 1, 0, 1, true);
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "END",
                          COMMIT_STMT_METRIC, 1, 0, 0, true);

    // Empty nested transaction block.
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "END",
                          COMMIT_STMT_METRIC, 1, 0, 0, true);

    // Extra COMMIT statement.
    verifyStatementMetric(statement, "BEGIN",
                          BEGIN_STMT_METRIC, 1, 0, 0, true);
    verifyStatementMetric(statement, "INSERT INTO test VALUES (11, 11)",
                          INSERT_STMT_METRIC, 1, 1, 0, true);
    verifyStatementMetric(statement, "COMMIT",
                          COMMIT_STMT_METRIC, 1, 0, 1, true);
    verifyStatementMetric(statement, "COMMIT",
                          COMMIT_STMT_METRIC, 1, 0, 0, true);
  }

  @Test
  public void testMetricRows() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      verifyStatementMetricRows(
        stmt,"CREATE TABLE test (k INT PRIMARY KEY, v INT)",
        OTHER_STMT_METRIC, 1, 0);

      verifyStatementMetricRows(
        stmt, "INSERT INTO test VALUES (1, 1), (2, 2), (3, 3), (4, 4), (5, 5)",
        INSERT_STMT_METRIC, 1, 5);

      verifyStatementMetricRows(
        stmt, "UPDATE test SET v = v + 1 WHERE v % 2 = 0",
        UPDATE_STMT_METRIC, 1, 2);

      verifyStatementMetricRows(
        stmt, "SELECT count(k) FROM test",
        AGGREGATE_PUSHDOWNS_METRIC, 1, 1);

      verifyStatementMetricRows(
        stmt, "SELECT * FROM test",
        SELECT_STMT_METRIC, 1, 5);

      verifyStatementMetricRows(
        stmt, "INSERT INTO test VALUES (6, 6), (7, 7)",
        SINGLE_SHARD_TRANSACTIONS_METRIC, 1, 2);

      // Single row transaction.
      verifyStatementMetricRows(
        stmt, "INSERT INTO test VALUES (8, 8)",
        SINGLE_SHARD_TRANSACTIONS_METRIC, 0, 0);

      verifyStatementMetricRows(
        stmt, "DELETE FROM test",
        DELETE_STMT_METRIC, 1, 8);
    }
  }

  @Test
  public void testStatementStats() throws Exception {
    Statement statement = connection.createStatement();

    String stmt, statStmt;

    // DDL is non-txn.
    stmt     = "CREATE TABLE test (k int PRIMARY KEY, v int)";
    statStmt = "CREATE TABLE test (k int PRIMARY KEY, v int)";
    verifyStatementStat(statement, stmt, statStmt, 1, true);

    // Select uses txn.
    stmt = "SELECT * FROM test";
    statStmt = stmt;
    verifyStatementStat(statement, stmt, statStmt, 1, true);

    // Select uses txn - test reset.
    verifyStatementStatWithReset(statement, stmt, statStmt, 100, 200);

    // Non-txn insert.
    stmt     = "INSERT INTO test VALUES (1, 1)";
    statStmt = "INSERT INTO test VALUES ($1, $2)";
    verifyStatementStat(statement, stmt, statStmt, 1, true);

    // Multiple non-txn inserts to check statement fingerprinting.
    final int numNonTxnInserts = 10;
    final int offset = 100;
    for (int i = 0; i < numNonTxnInserts; i++) {
      int j = offset + i;
      stmt = "INSERT INTO test VALUES (" + j + ", " + j + ")";
      // Use same statStmt.
      verifyStatementStat(statement, stmt, statStmt, 1, true);
    }

    // Txn insert.
    statement.execute("BEGIN");
    stmt     = "INSERT INTO test VALUES (2, 2)";
    statStmt = "INSERT INTO test VALUES ($1, $2)";
    verifyStatementStat(statement, stmt, statStmt, 1, true);
    statement.execute("END");

    // Non-txn update.
    stmt     = "UPDATE test SET v = 2 WHERE k = 1";
    statStmt = "UPDATE test SET v = $1 WHERE k = $2";
    verifyStatementStat(statement, stmt, statStmt, 1, true);

    // Txn update.
    stmt     = "UPDATE test SET v = 3";
    statStmt = "UPDATE test SET v = $1";
    verifyStatementStat(statement, stmt, statStmt, 1, true);

    // Non-txn delete.
    stmt     = "DELETE FROM test WHERE k = 2";
    statStmt = "DELETE FROM test WHERE k = $1";
    verifyStatementStat(statement, stmt, statStmt, 1, true);

    // Txn delete.
    stmt     = "DELETE FROM test";
    statStmt = stmt;
    verifyStatementStat(statement, stmt, statStmt, 1, true);

    // Limit query on complex view (issue #3811).
    stmt     = "SELECT * FROM information_schema.key_column_usage LIMIT 1";
    statStmt = "SELECT * FROM information_schema.key_column_usage LIMIT $1";
    verifyStatementStat(statement, stmt, statStmt, 1, true);

    // Invalid statement should not update metrics.
    stmt     = "INSERT INTO invalid_table VALUES (1)";
    statStmt = "INSERT INTO invalid_table VALUES ($1)";
    verifyStatementStat(statement, stmt, statStmt, 0, false);
  }

  @Test
  public void testStatementTime() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE test(k INT, v VARCHAR)");
      String preparedStmtSql =
          "PREPARE foo(INT, VARCHAR, INT, VARCHAR, INT, VARCHAR, INT, VARCHAR, INT, VARCHAR) " +
          "AS INSERT INTO test VALUES($1, $2), ($3, $4), ($5, $6), ($7, $8), ($9, $10)";
      statement.execute(preparedStmtSql);
      statement.execute(
          "CREATE PROCEDURE proc(n INT) LANGUAGE PLPGSQL AS $$ DECLARE c INT := 0; BEGIN " +
          "WHILE c < n LOOP c := c + 1; INSERT INTO test VALUES(c, 'value'); END LOOP; END; $$");
      testStatement(statement,
          "INSERT INTO test VALUES(1, '1'), (2, '2'), (3, '3'), (4, '4'), (5, '5')",
          INSERT_STMT_METRIC,
          "INSERT INTO test VALUES($1, $2), ($3, $4), ($5, $6), ($7, $8), ($9, $10)");
      testStatement(statement,
                    "EXECUTE foo(1, '1', 2, '2', 3, '3', 4, '4', 5, '5')",
                    INSERT_STMT_METRIC,
                    preparedStmtSql);
      testStatement(statement, "CALL proc(40)", OTHER_STMT_METRIC);
      testStatement(statement, "DO $$ BEGIN CALL proc(40); END $$", OTHER_STMT_METRIC);
    }
  }

  @Test
  public void testExplainTime() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE test(k INT, v VARCHAR)");
      statement.execute(
          "CREATE OR REPLACE FUNCTION func(n INT) RETURNS INT AS $$ DECLARE c INT := 0; BEGIN " +
          "WHILE c < n LOOP c := c + 1; insert into test values(c, 'value'); END LOOP; " +
          "RETURN 0; END; $$ LANGUAGE PLPGSQL");
      final String query = "EXPLAIN(COSTS OFF, ANALYZE) SELECT func(500)";
      ResultSet result = statement.executeQuery(query);
      AggregatedValue stat = getStatementStat(query);
      assertEquals(1, stat.count);
      while(result.next()) {
        if(result.isLast()) {
          double query_time = Double.parseDouble(result.getString(1).replaceAll("[^\\d.]", ""));
          // As stat.total_time indicates total time of EXPLAIN query,
          // actual query total time is a little bit less.
          // It is expected that query time is not less than 90% of stat.total_time.
          assertQueryTime(query, query_time, 0.9 * stat.value);
        }
      }
    }
  }

  private void testStatement(Statement statement,
                             String query,
                             String metric_name) throws Exception {
    testStatement(statement, query, metric_name, query);
  }

  /**
   * Function executes query and compare time elapsed locally with query statistics.
   * <p>
   * Local time always will be greater due to client-server communication.
   * <p>
   * To reduce client-server communication delays query is executed multiple times as a batch.
   */
  private void testStatement(Statement statement,
                             String query,
                             String metricName,
                             String statName) throws Exception {
    final int count = 200;
    for (int i = 0; i < count; ++i) {
      statement.addBatch(query);
    }
    AggregatedValue metricBefore = getMetric(metricName);
    StopWatch sw = StopWatch.createStarted();
    statement.executeBatch();
    final long elapsedLocalTime = sw.getTime();
    LOG.info("Batch execution of {} queries took {} ms ({})",
        count, elapsedLocalTime, query);
    AggregatedValue metricAfter = getMetric(metricName);
    AggregatedValue stat = getStatementStat(statName);
    assertEquals(String.format("Calls count for query %s", query),
        count, stat.count);
    assertEquals(String.format("'%s' count for query %s", metricName, query),
        count, metricAfter.count - metricBefore.count);

    // Due to client-server communications delay local time
    // is always greater than actual query time.
    // But we expect communications delay is less than 20%.
    final double timeLowerBound = 0.8 * elapsedLocalTime;
    assertQueryTime(query, stat.value, timeLowerBound);
    final long metricValue = Math.round(metricAfter.value - metricBefore.value);
    // Make metric lower bound a little smaller than stat.value due to rounding
    final long metricLowerBound = Math.round(0.95 * stat.value * 1000);
    assertGreaterThanOrEqualTo(
        String.format("Expected '%s' %d to be >= %d for query '%s'",
                      metricName,
                      metricValue,
                      metricLowerBound,
                      query),
        metricValue,
        metricLowerBound);
  }

  private void assertQueryTime(String query, double queryTime, double timeLowerBound) {
    assertGreaterThanOrEqualTo(
        String.format("Expected total time %f to be >= %f for query '%s'",
                      queryTime,
                      timeLowerBound,
                      query),
        queryTime,
        timeLowerBound);
  }
}
