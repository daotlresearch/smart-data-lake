/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2021 ELCA Informatique SA (<https://www.elca.ch>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.smartdatalake.testutils.custom

import io.smartdatalake.workflow.action.customlogic.CustomDfCreator
import org.apache.spark.sql.{DataFrame, SparkSession}

class TestCustomDfCreator extends CustomDfCreator {
  /**
   * function to create a dataFrame
   *
   * @param session : the Spark Session
   * @param config  Input Config of action
   * @return Map outputID -> dataFrame
   */
  override def exec(session: SparkSession, config: Map[String, String]): DataFrame = {
      import session.implicits._
      Seq((Some(0),"Foo!"),(Some(1),"Bar!")).toDF("num","text")
  }

  override def equals(obj: Any): Boolean = getClass.equals(obj.getClass)
}
