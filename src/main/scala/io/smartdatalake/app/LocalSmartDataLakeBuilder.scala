/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2020 ELCA Informatique SA (<https://www.elca.ch>)
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
package io.smartdatalake.app

import java.io.File

import io.smartdatalake.config.ConfigurationException

/**
 * Smart Data Lake Builder application for local mode.
 *
 * Sets master to local[*] and deployMode to client by default.
 */
object LocalSmartDataLakeBuilder extends SmartDataLakeBuilder {

  // optional master and deploy-mode settings to override defaults local[*] / client. Note that using something different than master=local is experimental.
  parser.opt[String]('m', "master")
    .action( (arg, config) => config.copy(master = Some(arg)))
    .text("The Spark master URL passed to SparkContext (default=local[*], yarn, spark://HOST:PORT, mesos://HOST:PORT, k8s://HOST:PORT).")
  parser.opt[String]('x', "deploy-mode")
    .action( (arg, config) => config.copy(deployMode = Some(arg)))
    .text("The Spark deploy mode passed to SparkContext (default=client, cluster).")

  // optional kerberos authentication parameters for local mode
  parser.opt[String]('d', "kerberos-domain")
    .action((arg, config) => config.copy(kerberosDomain = Some(arg)))
    .text("Kerberos-Domain for authentication (USERNAME@KERBEROS-DOMAIN) in local mode.")
  parser.opt[String]('u', "username")
    .action((arg, config) => config.copy(username = Some(arg)))
    .text("Kerberos username for authentication (USERNAME@KERBEROS-DOMAIN) in local mode.")
  parser.opt[File]('k', "keytab-path")
    .action((arg, config) => config.copy(keytabPath = Some(arg)))
    .text("Path to the Kerberos keytab file for authentication in local mode.")

  /**
   * Entry-Point of the application.
   *
   * @param args Command-line arguments.
   */
  def main(args: Array[String]): Unit = {
    logger.info(s"Starting Program $appType v$appVersion")

    // Set local defaults
    val config = initConfigFromEnvironment.copy(
      master = Some("local[*]"),
      deployMode = Some("client")
    )

    // Parse all command line arguments
    parseCommandLineArguments(args, config) match {
      case Some(config) =>

        // checking environment variables for local mode
        require( System.getenv("HADOOP_HOME")!=null, "Env variable HADOOP_HOME needs to be set in local mode!" )
        require( !config.master.contains("yarn") || System.getenv("SPARK_HOME")!=null, "Env variable SPARK_HOME needs to be set in local mode with master=yarn!" )

        // authenticate with kerberos if configured
        if (config.kerberosDomain.isDefined) {
          require(config.username.isDefined, "Parameter 'username' must be set for kerberos authentication!")
          val kp = config.keytabPath.map(_.getPath).orElse(Some(ClassLoader.getSystemClassLoader.getResource(s"${config.username.get}.keytab")).map(_.getPath))
            .getOrElse(throw new IllegalArgumentException(s"Couldn't find keytab file for kerberos authentication. Set parameter 'keytab-path' or make sure resource '${config.username.get}.keytab' exists!"))
          val principal = s"${config.username.get}@${config.kerberosDomain.get}"
          AppUtil.authenticate(kp, principal)
        }

        // start
        run(config)
        logger.info(s"$appType finished successfully.")
      case None =>
        logAndThrowException(s"Aborting ${appType} after error", new ConfigurationException("Couldn't set command line parameters correctly."))
    }
  }
}
