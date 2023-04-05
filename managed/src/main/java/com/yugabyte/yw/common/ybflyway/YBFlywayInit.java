/*
 * Copyright 2013 Toshiyuki Takahashi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yugabyte.yw.common.ybflyway;

import com.typesafe.config.Config;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.flywaydb.play.FlywayWebCommand;
import org.flywaydb.play.Flyways;
import play.api.Configuration;
import play.api.Environment;
import play.core.WebCommands;
import scala.collection.JavaConverters;

@Singleton
public class YBFlywayInit {

  @Inject
  public YBFlywayInit(
      Config config, Environment environment, Flyways flyways, WebCommands webCommands) {
    FlywayWebCommand webCommand =
        new FlywayWebCommand(new Configuration(config), environment, flyways);
    webCommands.addHandler(webCommand);

    for (String dbName : JavaConverters.asJavaCollection(flyways.allDatabaseNames())) {
      String migrationsDisabledPath = "db." + dbName + ".migration.disabled";
      boolean disabled =
          config.hasPath(migrationsDisabledPath) && config.getBoolean(migrationsDisabledPath);
      if (disabled) {
        return;
      }
      if (flyways.config(dbName).auto()) {
        flyways.migrate(dbName);
      }
    }
  }
}
