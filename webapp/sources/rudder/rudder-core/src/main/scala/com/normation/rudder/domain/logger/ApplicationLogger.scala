/*
*************************************************************************************
* Copyright 2012 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.domain.logger

import com.normation.NamedZioLogger
import org.slf4j.LoggerFactory
import net.liftweb.common.Logger

/**
 * Applicative log of interest for Rudder ops.
 */
object ApplicationLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("application")
}

object ApplicationLoggerPure extends NamedZioLogger {
  def loggerName = "application"
}

/**
 * A logger dedicated to "plugin" information, especially boot info.
 */
object PluginLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("application.plugin")
}

/**
 * A logger dedicated to scheduled jobs and batches
 */
object ScheduledJobLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("scheduled.job")
}

object ScheduledJobLoggerPure extends NamedZioLogger {
  parent =>
  def loggerName = "scheduled.job"

  object metrics extends NamedZioLogger {
    def loggerName: String = parent.loggerName + ".metrics"
  }
}

/**
 * A logger for new nodes informations
 */
object NodeLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("nodes")
  object PendingNode extends Logger {
    // the logger for information about pending nodes (accept/refuse)
    override protected def _logger = LoggerFactory.getLogger("nodes.pending")
    // the logger for info about what policies will be applied to the new node
    object Policies extends Logger {
      override protected def _logger = LoggerFactory.getLogger("nodes.pending.policies")
    }
  }
}
object NodeLoggerPure extends NamedZioLogger { parent =>
  def loggerName = "nodes"
  object Delete extends NamedZioLogger {
    def loggerName: String = parent.loggerName + ".delete"
  }

  object Cache extends NamedZioLogger {
    def loggerName: String = parent.loggerName + ".cache"
  }
}

/*
 * A logger to log information about the JS script eval for directives
 * parameter.s
 */
object JsDirectiveParamLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("jsDirectiveParam")
}

object JsDirectiveParamLoggerPure extends NamedZioLogger {
  def loggerName = "jsDirectiveParam"
}


object TechniqueReaderLoggerPure extends NamedZioLogger {
  def loggerName = "techniques.reader"
}

/**
 * Logger about change request and other workflow thing.
 */
object ChangeRequestLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("changeRequest")
}

/**
 * Logger used for historization of object names by `HistorizationService`
 */
object HistorizationLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("historization")
}

object GitArchiveLoggerPure extends NamedZioLogger {
  override def loggerName: String = "git-policy-archive"
}

object GitArchiveLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("git-policy-archive")
}

object ComplianceLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("compliance")
}

object ReportLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("report")

  object Cache extends Logger {
    override protected def _logger = LoggerFactory.getLogger("report.cache")
  }
}

object ReportLoggerPure extends NamedZioLogger {
  override def loggerName: String = "report"

  object Changes extends NamedZioLogger {
    override def loggerName: String = "report.changes"
  }
  object Cache extends NamedZioLogger {
    override def loggerName: String = "report.cache"
  }
}
