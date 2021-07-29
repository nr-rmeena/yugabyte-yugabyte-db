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

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.client.TestUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Build a ProcessBuilder for pg_regress.  Also, set up the output directory.
 */
public class PgRegressBuilder {
  protected static final Logger LOG = LoggerFactory.getLogger(PgRegressBuilder.class);

  protected static File pgBinDir = new File(TestUtils.getBuildRootDir(), "postgres/bin");
  protected static File pgRegressDir = new File(TestUtils.getBuildRootDir(),
                                                "postgres_build/src/test/regress");
  protected static File pgRegressExecutable = new File(pgRegressDir, "pg_regress");

  protected List<String> args;
  protected Map<String, String> extraEnvVars;

  protected File inputDir;
  protected File outputDir;

  public static File getPgBinDir() {
    return pgBinDir;
  }

  public static File getPgRegressDir() {
    return pgRegressDir;
  }

  protected PgRegressBuilder() {
    this.args = new ArrayList<>(Arrays.asList(
        pgRegressExecutable.toString(),
        "--bindir=" + pgBinDir,
        "--dlpath=" + pgRegressDir,
        "--use-existing"));
  }

  public PgRegressBuilder setDirs(File inputDir, File outputDir) throws RuntimeException {
    this.inputDir = inputDir;
    this.outputDir = outputDir;

    // Create output dir.
    if (!outputDir.mkdirs()) {
      throw new RuntimeException("Failed to create directory " + outputDir);
    }

    // Copy files needed by pg_regress.  "input" and "ouput" don't need to be copied since they can
    // be read from inputDir.  Their purpose is to generate files into "expected" and "sql" in
    // outputDir (implying "expected" and "sql" should be copied).  "data" doesn't need to be copied
    // since it's only read from (by convention), which can be done from inputDir.
    try {
      for (String name : new String[]{"expected", "sql"}) {
        FileUtils.copyDirectory(new File(inputDir, name), new File(outputDir, name));
      }
    } catch (IOException ex) {
      LOG.error("Failed to copy a directory from " + inputDir + " to " + outputDir);
      throw new RuntimeException(ex);
    }

    // TODO(dmitry): Workaround for #1721, remove after fix.
    try {
      for (File f : (new File(outputDir, "sql")).listFiles()) {
        try (FileWriter fr = new FileWriter(f, true)) {
          fr.write("\n-- YB_DATA_END\nROLLBACK;DISCARD TEMP;");
        }
      }
    } catch (IOException ex) {
      LOG.error("Failed to write YB_DATA_END footer to sql file");
      throw new RuntimeException(ex);
    }

    args.add("--inputdir=" + inputDir);
    args.add("--outputdir=" + outputDir);
    return this;
  }

  public PgRegressBuilder setSchedule(String schedule) {
    if (inputDir == null) {
      throw new RuntimeException("inputDir should not be null");
    }
    if (outputDir == null) {
      throw new RuntimeException("outputDir should not be null");
    }

    File scheduleInputFile = new File(inputDir, schedule);
    File scheduleOutputFile = new File(outputDir, schedule);

    // Copy the schedule file, replacing some lines based on the operating system.
    try (BufferedReader scheduleReader = new BufferedReader(new FileReader(scheduleInputFile));
         PrintWriter scheduleWriter = new PrintWriter(new FileWriter(scheduleOutputFile))) {
      String line;
      while ((line = scheduleReader.readLine()) != null) {
        line = line.trim();
        if (line.equals("test: yb_pg_inet") && !TestUtils.IS_LINUX) {
          // We only support IPv6-specific tests in yb_pg_inet.sql on Linux, not on macOS.
          line = "test: yb_pg_inet_ipv4only";
        }
        LOG.info("Schedule output line: " + line);
        scheduleWriter.println(line);
      }
    } catch (IOException ex) {
      LOG.error("Failed to write schedule to " + outputDir);
      throw new RuntimeException(ex);
    }

    args.add("--schedule=" + new File(outputDir, schedule));
    return this;
  }

  public PgRegressBuilder setHost(String host) {
    args.add("--host=" + host);
    return this;
  }

  public PgRegressBuilder setPort(int port) {
    args.add("--port=" + port);
    return this;
  }

  public PgRegressBuilder setUser(String user) {
    args.add("--user=" + user);
    return this;
  }

  public PgRegressBuilder setDatabase(String database) {
    args.add("--dbname=" + database);
    return this;
  }

  public PgRegressBuilder setEnvVars(Map<String, String> envVars) throws IOException {
    extraEnvVars = envVars;
    addPostProcessEnvVar();
    return this;
  }

  private void addPostProcessEnvVar() throws IOException {
    File postprocessScript = new File(
        TestUtils.findYbRootDir() + "/build-support/pg_regress_postprocess_output.py");

    if (!postprocessScript.exists()) {
      throw new IOException("File does not exist: " + postprocessScript);
    }
    if (!postprocessScript.canExecute()) {
      throw new IOException("Not executable: " + postprocessScript);
    }

    // Ask pg_regress to run a post-processing script on the output to remove some sanitizer
    // suppressions before running the diff command, and also to remove trailing whitespace.
    extraEnvVars.put("YB_PG_REGRESS_RESULTSFILE_POSTPROCESS_CMD",
        postprocessScript.toString());
  }

  public ProcessBuilder getProcessBuilder() {
    ProcessBuilder procBuilder = new ProcessBuilder(args);
    procBuilder.directory(inputDir);
    procBuilder.environment().putAll(extraEnvVars);

    return procBuilder;
  }
}
