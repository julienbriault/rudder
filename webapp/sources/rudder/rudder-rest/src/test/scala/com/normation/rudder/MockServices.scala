/*
*************************************************************************************
* Copyright 2020 Normation SAS
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

package com.normation.rudder

import com.normation.cfclerk.domain.{Version => _, _}
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.cfclerk.services.impl._
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.cfclerk.xmlparsers.VariableSpecParser
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.reports._
import com.normation.rudder.repository._
import com.normation.rudder.rule.category._
import com.normation.rudder.services.policies.SystemVariableServiceImpl
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.services.servers.RelaySynchronizationMethod.Classic
import com.normation.utils.StringUuidGeneratorImpl
import net.liftweb.common.Box
import net.liftweb.common.Full
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime

import java.io.File
import com.normation.rudder.services.policies.RudderServerRole
import zio.{Tag => _, _}
import zio.syntax._
import com.normation.errors._
import com.normation.inventory.domain.AgentType.CfeCommunity
import com.normation.zio._
import com.normation.rudder.domain.archives.RuleArchiveId
import com.normation.rudder.domain.queries.CriterionComposition
import com.normation.rudder.domain.queries.NodeInfoMatcher
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.repository.WoRuleRepository
import com.normation.rudder.services.nodes.LDAPNodeInfo
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.rudder.services.policies.ParameterForConfiguration
import com.normation.rudder.services.policies.Policy
import org.joda.time.format.ISODateTimeFormat

import scala.annotation.tailrec
import scala.collection.SortedMap
import com.normation.box._
import com.normation.inventory.ldap.core.LDAPFullInventoryRepository
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import com.normation.rudder.domain.archives.ParameterArchiveId
import com.normation.rudder.domain.nodes.GenericProperty.StringToConfigValue
import com.normation.rudder.domain.parameters.AddGlobalParameterDiff
import com.normation.rudder.domain.parameters.DeleteGlobalParameterDiff
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.parameters.ModifyGlobalParameterDiff
import com.normation.rudder.domain.queries._
import com.normation.rudder.services.queries._
import com.unboundid.ldif.LDIFChangeRecord

import scala.collection.immutable
import scala.util.control.NonFatal

/*
 * Mock services for test, especially reposositories, and provides
 * test data (nodes, directives, etc)
 */

object NoTags {
  def apply() = Tags(Set())
}

object MkTags {
  def apply(tags: (String, String)*) = {
    Tags(tags.map{ case (k, v) => Tag(TagName(k), TagValue(v))}.toSet)
  }
}

object Diff {

  class DiffBetween[A](old: A, current: A) {
    def apply[B](path: A => B): Option[SimpleDiff[B]] = {
      if(path(old) == path(current)) None
      else Some(SimpleDiff(path(old), path(current)))
    }
  }

  def apply[A](old: A, current: A) = new DiffBetween(old, current)
}

class MockGitConfigRepo(prefixTestResources: String = "") {

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // set up root node configuration
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  val abstractRoot = new File("/tmp/test-rudder-config-repo-" + DateTime.now.toString())
  abstractRoot.mkdirs()
  if(System.getProperty("tests.clean.tmp") != "false") {
    java.lang.Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      override def run(): Unit = FileUtils.deleteDirectory(abstractRoot)
    }))
  }

  // config-repo will also be the git root, as a normal rudder
  val configurationRepositoryRoot = new File(abstractRoot, "configuration-repository")
  //initialize config-repo content from our test/resources source

  FileUtils.copyDirectory( new File(prefixTestResources + "src/test/resources/configuration-repository") , configurationRepositoryRoot)

  val gitRepo = GitRepositoryProviderImpl.make(configurationRepositoryRoot.getAbsolutePath).runNow
}

object MockTechniques {
  def apply(mockGitConfigRepo: MockGitConfigRepo) = new MockTechniques(mockGitConfigRepo.configurationRepositoryRoot, mockGitConfigRepo.gitRepo)
}

class MockTechniques(configurationRepositoryRoot: File, gitRepo: GitRepositoryProvider) {
  val variableSpecParser = new VariableSpecParser
  val systemVariableServiceSpec = new SystemVariableSpecServiceImpl()
  val techniqueParser: TechniqueParser = new TechniqueParser(
      variableSpecParser
    , new SectionSpecParser(variableSpecParser)
    , systemVariableServiceSpec
  )
  val techniqueReader = new GitTechniqueReader(
      techniqueParser
    , new SimpleGitRevisionProvider("refs/heads/master", gitRepo)
    , gitRepo
    , "metadata.xml"
    , "category.xml"
    , Some("techniques")
    , "default-directive-names.conf"
  )
  val stringUuidGen = new StringUuidGeneratorImpl()

  val techniqueRepo = new TechniqueRepositoryImpl(techniqueReader, Seq(), stringUuidGen)

  ///////////////////////////  policyServer and systemVariables  ///////////////////////////


  val policyServerManagementService = new PolicyServerManagementService() {
    override def setAuthorizedNetworks(policyServerId:NodeId, networks:Seq[String], modId: ModificationId, actor:EventActor) = ???
    override def getAuthorizedNetworks(policyServerId:NodeId) : Box[Seq[String]] = Full(List("192.168.49.0/24"))
    override def deleteRelaySystemObjectsPure(policyServerId: NodeId): IOResult[Unit] = ???
    override def updateAuthorizedNetworks(policyServerId: NodeId, addNetworks: Seq[String], deleteNetwork: Seq[String], modId: ModificationId, actor: EventActor): Box[Seq[String]] = ???
  }

  val systemVariableService = new SystemVariableServiceImpl(
      systemVariableServiceSpec
    , policyServerManagementService
    , toolsFolder                     = "tools_folder"
    , policyDistribCfenginePort       = 5309
    , policyDistribHttpsPort          = 443
    , sharedFilesFolder               = "/var/rudder/configuration-repository/shared-files"
    , webdavUser                      = "rudder"
    , webdavPassword                  = "rudder"
    , reportsDbUri                    = "rudder"
    , reportsDbUser                   = "rudder"
    , syslogPort                      = 514
    , configurationRepository         = configurationRepositoryRoot.getAbsolutePath
    , serverRoles                     = Seq(
                                            RudderServerRole("rudder-ldap"                   , "rudder.server-roles.ldap")
                                          , RudderServerRole("rudder-inventory-endpoint"     , "rudder.server-roles.inventory-endpoint")
                                          , RudderServerRole("rudder-db"                     , "rudder.server-roles.db")
                                          , RudderServerRole("rudder-relay-top"              , "rudder.server-roles.relay-top")
                                          , RudderServerRole("rudder-web"                    , "rudder.server-roles.web")
                                          , RudderServerRole("rudder-relay-promises-only"    , "rudder.server-roles.relay-promises-only")
                                          , RudderServerRole("rudder-cfengine-mission-portal", "rudder.server-roles.cfengine-mission-portal")
                                        )
    , serverVersion                   = "7.0.0"
    //denybadclocks is runtime properties
    , getDenyBadClocks                = () => Full(true)
    , getSyncMethod                   = () => Full(Classic)
    , getSyncPromises                 = () => Full(false)
    , getSyncSharedFiles              = () => Full(false)
    // TTLs are runtime properties too
    , getModifiedFilesTtl             = () => Full(30)
    , getCfengineOutputsTtl           = () => Full(7)
    , getStoreAllCentralizedLogsInFile= () => Full(true)
    , getSendMetrics                  = () => Full(None)
    , getSyslogProtocol               = () => Full(SyslogUDP)
    , getSyslogProtocolDisabled       = () => Full(false)
    , getReportProtocolDefault        = () => Full(AgentReportingSyslog)
    , getRudderVerifyCertificates     = () => Full(false)
  )

  val globalAgentRun = AgentRunInterval(None, 5, 1, 0, 4)
  val globalComplianceMode = GlobalComplianceMode(FullCompliance, 15)

  val globalSystemVariables = systemVariableService.getGlobalSystemVariables(globalAgentRun).openOrThrowException("I should get global system variable in test!")
}

class MockDirectives(mockTechniques: MockTechniques) {

  object directives {

    val commonTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("common"), TechniqueVersion("1.0")))
    def commonVariables(nodeId: NodeId, allNodeInfos: Map[NodeId, NodeInfo]) = {
       val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
       Seq(
         spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
       , spec("OWNER").toVariable(Seq(allNodeInfos(nodeId).localAdministratorAccountName))
       , spec("UUID").toVariable(Seq(nodeId.value))
       , spec("POLICYSERVER_ID").toVariable(Seq(allNodeInfos(nodeId).policyServerId.value))
       , spec("POLICYSERVER").toVariable(Seq(allNodeInfos(allNodeInfos(nodeId).policyServerId).hostname))
       , spec("POLICYSERVER_ADMIN").toVariable(Seq(allNodeInfos(allNodeInfos(nodeId).policyServerId).localAdministratorAccountName))
       ).map(v => (v.spec.name, v)).toMap
    }
    val commonDirective = Directive(
        DirectiveId("common-root")
      , TechniqueVersion("1.0")
      , Map(
          ("ALLOWEDNETWORK", Seq("192.168.0.0/16"))
        , ("OWNER", Seq("${rudder.node.admin}"))
        , ("UUID", Seq("${rudder.node.id}"))
        , ("POLICYSERVER_ID", Seq("${rudder.node.policyserver.id}"))
        , ("POLICYSERVER", Seq("${rudder.node.policyserver.hostname}"))
        , ("POLICYSERVER_ADMIN", Seq("${rudder.node.policyserver.admin}"))
        )
      , "common-root"
      , "", None, "", 5, true, true // short desc / policyMode / long desc / prio / enabled / system
    )

    val rolesTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("server-roles"), TechniqueVersion("1.0")))
    val rolesDirective = Directive(
        DirectiveId("Server Roles")
      , TechniqueVersion("1.0")
      , Map(
          ("ALLOWEDNETWORK", Seq("192.168.0.0/16"))
        )
      , "Server Roles"
      , "", None, "", 5, true, true // short desc / policyMode / long desc / prio / enabled / system
    )

    val distributeTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("distributePolicy"), TechniqueVersion("1.0")))
    val distributeDirective = Directive(
        DirectiveId("Distribute Policy")
      , TechniqueVersion("1.0")
      , Map(
          ("ALLOWEDNETWORK", Seq("192.168.0.0/16"))
        )
      , "Distribute Policy"
      , "", None, "", 5, true, true // short desc / policyMode / long desc / prio / enabled / system
    )

    val inventoryTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("inventory"), TechniqueVersion("1.0")))
    val inventoryDirective = Directive(
        DirectiveId("inventory-all")
      , TechniqueVersion("1.0")
      , Map(
         ("ALLOWEDNETWORK", Seq("192.168.0.0/16"))
       )
      , "Inventory"
      , "", None, "", 5, true, true // short desc / policyMode / long desc / prio / enabled / system
    )

    //
    // 4 user directives: clock management, rpm, package, a multi-policiy: fileTemplate, and a ncf one: Create_file
    //
    val clockTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("clockConfiguration"), TechniqueVersion("3.0")))
    val clockDirective = Directive(
        DirectiveId("directive1")
      , TechniqueVersion("3.0")
      , Map(
           ("CLOCK_FQDNNTP"      , Seq("true"))
         , ("CLOCK_HWSYNC_ENABLE", Seq("true"))
         , ("CLOCK_NTPSERVERS"   , Seq("${rudder.param.ntpserver}"))
         , ("CLOCK_SYNCSCHED"    , Seq("240"))
         , ("CLOCK_TIMEZONE"     , Seq("dontchange"))
        )
      , "10. Clock Configuration"
      , "", None, "", 5, true, false // short desc / policyMode / long desc / prio / enabled / system
    )

    /*
     * A RPM Policy, which comes from 2 directives.
     * The second one contributes two packages.
     * It had a different value for the CHECK_INTERVAL, but
     * that variable is unique, so it get the first draft value all along.
     */
    val rpmTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("rpmPackageInstallation"), TechniqueVersion("7.0")))
    val rpmDirective = Directive(
        DirectiveId("directive2")
      , TechniqueVersion("7.0")
      , Map(
            ("RPM_PACKAGE_CHECK_INTERVAL", Seq("5"))
          , ("RPM_PACKAGE_POST_HOOK_COMMAND", Seq(""))
          , ("RPM_PACKAGE_POST_HOOK_RUN", Seq("false"))
          , ("RPM_PACKAGE_REDACTION", Seq("add"))
          , ("RPM_PACKAGE_REDLIST", Seq("vim"))
          , ("RPM_PACKAGE_VERSION", Seq(""))
          , ("RPM_PACKAGE_VERSION_CRITERION", Seq("=="))
          , ("RPM_PACKAGE_VERSION_DEFINITION", Seq("default"))
        )
      , "directive2", "", None, ""
    )

    // a directive with two iterations
    val pkgTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("packageManagement"), TechniqueVersion("1.0")))
    val pkgDirective = Directive(
        DirectiveId("16617aa8-1f02-4e4a-87b6-d0bcdfb4019f")
      , TechniqueVersion("1.0")
      , Map(
            ("PACKAGE_LIST", Seq("htop", "jq"))
          , ("PACKAGE_STATE", Seq("present", "present"))
          , ("PACKAGE_VERSION", Seq("latest", "latest"))
          , ("PACKAGE_VERSION_SPECIFIC", Seq("", ""))
          , ("PACKAGE_ARCHITECTURE", Seq("default", "default"))
          , ("PACKAGE_ARCHITECTURE_SPECIFIC", Seq("", ""))
          , ("PACKAGE_MANAGER", Seq("default", "default"))
          , ("PACKAGE_POST_HOOK_COMMAND", Seq("", ""))
        )
      , "directive 16617aa8-1f02-4e4a-87b6-d0bcdfb4019f", "", None, ""
    )


    val fileTemplateTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("fileTemplate"), TechniqueVersion("1.0")))
    val fileTemplateDirecive1 = Directive(
        DirectiveId("e9a1a909-2490-4fc9-95c3-9d0aa01717c9")
      , TechniqueVersion("1.0")
      , Map(
           ("FILE_TEMPLATE_RAW_OR_NOT", Seq("Raw"))
         , ("FILE_TEMPLATE_TEMPLATE", Seq(""))
         , ("FILE_TEMPLATE_RAW_TEMPLATE", Seq("some content"))
         , ("FILE_TEMPLATE_AGENT_DESTINATION_PATH", Seq("/tmp/destination.txt"))
         , ("FILE_TEMPLATE_TEMPLATE_TYPE", Seq("mustache"))
         , ("FILE_TEMPLATE_OWNER", Seq("root"))
         , ("FILE_TEMPLATE_GROUP_OWNER", Seq("root"))
         , ("FILE_TEMPLATE_PERMISSIONS", Seq("700"))
         , ("FILE_TEMPLATE_PERSISTENT_POST_HOOK", Seq("false"))
         , ("FILE_TEMPLATE_TEMPLATE_POST_HOOK_COMMAND", Seq(""))
       )
      , "directive e9a1a909-2490-4fc9-95c3-9d0aa01717c9", "", None, ""
    )
    val fileTemplateVariables2 = Directive(
        DirectiveId("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f")
      , TechniqueVersion("1.0")
      , Map(
           ("FILE_TEMPLATE_RAW_OR_NOT", Seq("Raw"))
         , ("FILE_TEMPLATE_TEMPLATE", Seq(""))
         , ("FILE_TEMPLATE_RAW_TEMPLATE", Seq("some content"))
         , ("FILE_TEMPLATE_AGENT_DESTINATION_PATH", Seq("/tmp/other-destination.txt"))
         , ("FILE_TEMPLATE_TEMPLATE_TYPE", Seq("mustache"))
         , ("FILE_TEMPLATE_OWNER", Seq("root"))
         , ("FILE_TEMPLATE_GROUP_OWNER", Seq("root"))
         , ("FILE_TEMPLATE_PERMISSIONS", Seq("777"))
         , ("FILE_TEMPLATE_PERSISTENT_POST_HOOK", Seq("true"))
         , ("FILE_TEMPLATE_TEMPLATE_POST_HOOK_COMMAND", Seq("/bin/true"))
       )
      , "directive ff44fb97-b65e-43c4-b8c2-0df8d5e8549f", "", None, ""
    )

    val ncf1Technique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("Create_file"), TechniqueVersion("1.0")))
    val ncf1Directive = Directive(
        DirectiveId("16d86a56-93ef-49aa-86b7-0d10102e4ea9")
      , TechniqueVersion("1.0")
      , Map(
           ("expectedReportKey Directory create", Seq("directory_create_/tmp/foo"))
         , ("expectedReportKey File create", Seq("file_create_/tmp/foo/bar"))
         , ("1AAACD71-C2D5-482C-BCFF-5EEE6F8DA9C2", Seq("\"foo"))
        )
      , "directive 16d86a56-93ef-49aa-86b7-0d10102e4ea9", "", None, ""
    )

    /**
      * test for multiple generation
      */
    val DIRECTIVE_NAME_COPY_GIT_FILE="directive-copyGitFile"
    val copyGitFileTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("copyGitFile"), TechniqueVersion("2.3")))
    val copyGitFileDirective = Directive(
        DirectiveId("directive-copyGitFile")
      , TechniqueVersion("2.3")
      , Map(
            ("COPYFILE_NAME", Seq("file_name_0.json"))
          , ("COPYFILE_EXCLUDE_INCLUDE_OPTION", Seq("none"))
          , ("COPYFILE_EXCLUDE_INCLUDE", Seq(""))
          , ("COPYFILE_DESTINATION", Seq("/tmp/destination_0.json"))
          , ("COPYFILE_RECURSION", Seq("0"))
          , ("COPYFILE_PURGE", Seq("false"))
          , ("COPYFILE_COMPARE_METHOD", Seq("mtime"))
          , ("COPYFILE_OWNER", Seq("root"))
          , ("COPYFILE_GROUP", Seq("root"))
          , ("COPYFILE_PERM", Seq("644"))
          , ("COPYFILE_SUID", Seq("false"))
          , ("COPYFILE_SGID", Seq("false"))
          , ("COPYFILE_STICKY_FOLDER", Seq("false"))
          , ("COPYFILE_POST_HOOK_RUN", Seq("true"))
          , ("COPYFILE_POST_HOOK_COMMAND", Seq("/bin/echo Value_0.json"))
        )
      , "directive-copyGitFile", "", None, ""
    )



    /*
     * Test override order of generic-variable-definition.
     * We want to have to directive, directive1 and directive2.
     * directive1 is the default value and must be overriden by value in directive 2, which means that directive2 value must be
     * define after directive 1 value in generated "genericVariableDefinition.cf".
     * The semantic to achieve that is to use Priority: directive 1 has a higher (ie smaller int number) priority than directive 2.
     *
     * To be sure that we don't use rule/directive name order, we will make directive 2 sort name come before directive 1 sort name.
     *
     * BUT added subtilities: the final bundle name order that will be used is the most prioritary one, so that we keep the
     * global sorting logic between rules / directives.
     *
     * In summary: sorting directives that are merged into one is a different problem than sorting directives for the bundle sequence.
     */
    val gvdTechnique  = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("genericVariableDefinition"), TechniqueVersion("2.0")))
    val gvdDirective1 = Directive(
        DirectiveId("gvd-directive1")
      , TechniqueVersion("2.0")
      , Map(
           ("GENERIC_VARIABLE_NAME", Seq("var1"))
         , ("GENERIC_VARIABLE_CONTENT", Seq("value from gvd #1 should be first")) // the one to override
       )
      , "99. Generic Variable Def #1", "", None, "", 0
    )
    val gvdDirective2 = Directive(
        DirectiveId("gvd-directive2")
      , TechniqueVersion("2.0")
      , Map(
           ("GENERIC_VARIABLE_NAME", Seq("var2"))
         , ("GENERIC_VARIABLE_CONTENT", Seq("value from gvd #2 should be second")) // the one to override
       )
      , "00. Generic Variable Def #2", "", None, "", 10
    )

    val all = Map(
        (clockTechnique, clockDirective :: Nil)
      , (commonTechnique, commonDirective :: Nil)
      , (copyGitFileTechnique, copyGitFileDirective :: Nil)
      , (distributeTechnique, distributeDirective :: Nil)
      , (fileTemplateTechnique, fileTemplateDirecive1 :: fileTemplateVariables2 :: Nil)
      , (gvdTechnique, gvdDirective1 :: gvdDirective2 :: Nil)
      , (inventoryTechnique, inventoryDirective :: Nil)
      , (ncf1Technique, ncf1Directive :: Nil)
      , (pkgTechnique, pkgDirective :: Nil)
      , (rolesTechnique, rolesDirective :: Nil)
      , (rpmTechnique, rpmDirective :: Nil)
    )
  }

  val techniqueRepos = mockTechniques.techniqueRepo
  implicit class UnsafeGet(repo: TechniqueRepositoryImpl) {
    def unsafeGet(id: TechniqueId) = repo.get(id).getOrElse(throw new RuntimeException(s"Bad init for test: technique '${id.toString()}' not found"))
  }

  val rootActiveTechniqueCategory = RefM.make(FullActiveTechniqueCategory(
      id = ActiveTechniqueCategoryId("Active Techniques")
    , name = "Active Techniques"
    , description = "This is the root category for active techniques. It contains subcategories, actives techniques and directives"
    , subCategories = Nil
    , activeTechniques = Nil
    , isSystem = true
  )).runNow

  def displayFullActiveTechniqueCategory(fatc: FullActiveTechniqueCategory, indent: String = ""): String = {
    val indent2 = indent+"  "

    def displayTech(t: FullActiveTechnique, indent3: String): String = {
      s"""${indent3}+ ${t.id.value}${t.directives.map(d => s"\n${indent3}  * ${d.id.value}").mkString("")}""".stripMargin
    }

    s"""${indent}- ${fatc.id.value}
       |${fatc.activeTechniques.sortBy(_.id.value).map(t => displayTech(t, indent2)).mkString("", "\n", "")}
       |${fatc.subCategories.sortBy(_.id.value).map(displayFullActiveTechniqueCategory(_, indent2)).mkString("", "\n", "")}""".stripMargin
  }

  object directiveRepo extends RoDirectiveRepository with WoDirectiveRepository {
    override def getFullDirectiveLibrary(): IOResult[FullActiveTechniqueCategory] = rootActiveTechniqueCategory.get

    override def getDirective(directiveId: DirectiveId): IOResult[Option[Directive]] = {
      rootActiveTechniqueCategory.get.map(_.allDirectives.get(directiveId).map(_._2))
    }

    override def getDirectiveWithContext(directiveId: DirectiveId): IOResult[Option[(Technique, ActiveTechnique, Directive)]] = {
      rootActiveTechniqueCategory.get.map(_.allDirectives.get(directiveId).map { case (fat, d) =>
        (fat.techniques(d.techniqueVersion), fat.toActiveTechnique(), d)
      })
    }

    override def getActiveTechniqueAndDirective(id: DirectiveId): IOResult[Option[(ActiveTechnique, Directive)]] = {
      getDirectiveWithContext(id).map(_.map { case (t, at, d) => (at, d) })
    }

    override def getDirectives(activeTechniqueId: ActiveTechniqueId, includeSystem: Boolean): IOResult[Seq[Directive]] = {
      val predicate = (d:Directive) => if(includeSystem) true else !d.isSystem
      rootActiveTechniqueCategory.get.map(_.allDirectives.collect { case(_, (_, d)) if(predicate(d)) => d }.toSeq )
    }

    override def getActiveTechniqueByCategory(includeSystem: Boolean): IOResult[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]] = {
      implicit val ordering = ActiveTechniqueCategoryOrdering
      rootActiveTechniqueCategory.get.map(_.fullIndex.map { case (path, fat) =>
        (path, CategoryWithActiveTechniques(fat.toActiveTechniqueCategory(), fat.activeTechniques.map(_.toActiveTechnique()).toSet))
      })
    }

    override def getActiveTechniqueByActiveTechnique(id: ActiveTechniqueId): IOResult[Option[ActiveTechnique]] = {
      rootActiveTechniqueCategory.get.map(_.allActiveTechniques.get(id).map(_.toActiveTechnique()))
    }

    override def getActiveTechnique(techniqueName: TechniqueName): IOResult[Option[ActiveTechnique]] = {
      rootActiveTechniqueCategory.get.map(_.allActiveTechniques.valuesIterator.find(_.techniqueName == techniqueName).map(_.toActiveTechnique()))
    }

    override def activeTechniqueBreadCrump(id: ActiveTechniqueId): IOResult[List[ActiveTechniqueCategory]] = {
      import cats.implicits._

      rootActiveTechniqueCategory.get.map(root => root.fullIndex.find { case (path, fat) =>
        fat.activeTechniques.exists(_.id == id)
      } match {
        case Some((path, _)) => path.traverse { id =>
          root.allCategories.get(id)
        } match {
          case None    => Nil
          case Some(l) => l.map(_.toActiveTechniqueCategory())
        }
        case None            => Nil
      })
    }

    override def getActiveTechniqueLibrary: IOResult[ActiveTechniqueCategory] = {
      rootActiveTechniqueCategory.get.map(_.toActiveTechniqueCategory())
    }

    override def getAllActiveTechniqueCategories(includeSystem: Boolean): IOResult[Seq[ActiveTechniqueCategory]] = {
      val predicate = (fat: FullActiveTechniqueCategory) => if(includeSystem) true else !fat.isSystem
      rootActiveTechniqueCategory.get.map { _.allCategories.values.collect { case c if(predicate(c)) =>
        c.toActiveTechniqueCategory()
      }.toSeq }
    }

    override def getActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[Option[ActiveTechniqueCategory]] = {
      rootActiveTechniqueCategory.get.map(_.allCategories.get(id).map(_.toActiveTechniqueCategory()))
    }

    override def getParentActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[ActiveTechniqueCategory] = {
      rootActiveTechniqueCategory.get.flatMap(root => root.fullIndex.find(_._1.lastOption == Some(id)) match {
        case None            =>
          Inconsistency(s"Category not found: ${id.value}").fail
        case Some((path, _)) =>
          path.dropRight(1).lastOption.flatMap(root.allCategories.get(_).map(_.toActiveTechniqueCategory())).notOptional(s"Parent category of ${id.value} not found")
      })
    }

    override def getParentsForActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[List[ActiveTechniqueCategory]] = ???

    override def getParentsForActiveTechnique(id: ActiveTechniqueId): IOResult[ActiveTechniqueCategory] = ???

    override def containsDirective(id: ActiveTechniqueCategoryId): UIO[Boolean] = ???

    def buildDirectiveDiff(old: Option[(SectionSpec, TechniqueName, Directive)], current: Directive): Option[ModifyDirectiveDiff] = {
      (old, current) match {
        case (None, _)                    => None
        case (Some(t), c2) if(t._3 == c2) => None
        case (Some((spec, tn, c1)), c2)   =>
          val diff = Diff(c1, c2)
          Some(ModifyDirectiveDiff(tn, c2.id, c2.name
            , diff(_.name)
            , diff(_.techniqueVersion)
            , diff(d => SectionVal.directiveValToSectionVal(spec, d.parameters))
            , diff(_.shortDescription)
            , diff(_.longDescription)
            , diff(_.priority)
            , diff(_.isEnabled)
            , diff(_.isSystem)
            , diff(_.policyMode)
            , diff(_.tags.tags)
          ))
      }
    }
    def saveGen(inActiveTechniqueId: ActiveTechniqueId, directive: Directive) = {
      rootActiveTechniqueCategory.modify(r =>
        r.saveDirective(inActiveTechniqueId, directive).toIO.map(c =>
          // TODO: this is false, we should get root section spec for each directive/technique version
          (r.allDirectives.get(directive.id).map(p => (p._1.techniques.head._2.rootSection,  p._1.techniqueName,  p._2)), c)
        )
      ).map(buildDirectiveDiff(_, directive))
    }
    override def saveDirective(inActiveTechniqueId: ActiveTechniqueId, directive: Directive, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Option[DirectiveSaveDiff]] = {
      if(directive.isSystem) Inconsistency(s"Can not modify system directive '${directive.id}' here").fail
      else saveGen(inActiveTechniqueId, directive)
    }

    override def saveSystemDirective(inActiveTechniqueId: ActiveTechniqueId, directive: Directive, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Option[DirectiveSaveDiff]] = {
      if(!directive.isSystem) Inconsistency(s"Can not modify non system directive '${directive.id}' here").fail
      else saveGen(inActiveTechniqueId, directive)
    }

    def deleteGen(id: DirectiveId) = {
      // TODO: we should check if directive is system
      rootActiveTechniqueCategory.modify(r =>
        (r.allDirectives.get(id), r.deleteDirective(id)).succeed
      ).map(_.map(p => DeleteDirectiveDiff(p._1.techniqueName, p._2)))
    }
    override def delete(id: DirectiveId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Option[DeleteDirectiveDiff]] = {
      deleteGen(id)
    }

    override def deleteSystemDirective(id: DirectiveId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Option[DeleteDirectiveDiff]] = {
      deleteGen(id)
    }

    override def addTechniqueInUserLibrary(categoryId: ActiveTechniqueCategoryId, techniqueName: TechniqueName, versions: Seq[TechniqueVersion], modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[ActiveTechnique] = {
      val techs = techniqueRepos.getByName(techniqueName)
      for {
        all <- ZIO.foreach(versions)(v => techs.get(v).notOptional(s"Missing version '${v}' for technique '${techniqueName.value}'"))
        res <- rootActiveTechniqueCategory.modify { r =>
                 val root = r.addActiveTechnique(categoryId, techniqueName, all)
                 root.allCategories.get(categoryId).flatMap(_.activeTechniques.find(_.id.value == techniqueName.value)).notOptional(s"bug: active tech should be here").map(at =>
                   (at, root)
                 )
               }
      } yield {
        res.toActiveTechnique()
      }
    }

    override def move(id: ActiveTechniqueId, newCategoryId: ActiveTechniqueCategoryId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[ActiveTechniqueId] = ???

    override def changeStatus(id: ActiveTechniqueId, status: Boolean, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[ActiveTechniqueId] = ???

    override def setAcceptationDatetimes(id: ActiveTechniqueId, datetimes: Map[TechniqueVersion, DateTime], modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[ActiveTechniqueId] = ???

    override def deleteActiveTechnique(id: ActiveTechniqueId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[ActiveTechniqueId] = ???

    override def addActiveTechniqueCategory(that: ActiveTechniqueCategory, into: ActiveTechniqueCategoryId, modificationId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[ActiveTechniqueCategory] = {
      rootActiveTechniqueCategory.updateAndGet { root =>
         val full = FullActiveTechniqueCategory(
            that.id
          , that.name
          , that.description
          , that.children.flatMap(root.allCategories.get(_))
          , that.items.flatMap(root.allActiveTechniques.get(_))
        )
        root.addActiveTechniqueCategory(into, full).succeed
      }.map(_.toActiveTechniqueCategory())
    }

    override def saveActiveTechniqueCategory(category: ActiveTechniqueCategory, modificationId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[ActiveTechniqueCategory] = ???

    override def deleteCategory(id: ActiveTechniqueCategoryId, modificationId: ModificationId, actor: EventActor, reason: Option[String], checkEmpty: Boolean): IOResult[ActiveTechniqueCategoryId] = ???

    override def move(categoryId: ActiveTechniqueCategoryId, intoParent: ActiveTechniqueCategoryId, optionNewName: Option[ActiveTechniqueCategoryId], modificationId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[ActiveTechniqueCategoryId] = ???
  }

  val initDirectivesTree = new InitDirectivesTree(mockTechniques.techniqueRepo, directiveRepo, directiveRepo, new StringUuidGeneratorImpl())

  initDirectivesTree.copyReferenceLib(includeSystem = true)

  {
    val modId = ModificationId(s"init directives in lib")
    val actor = EventActor("test user")
    ZIO.foreach_(directives.all) { case (t, list) =>
      val at = ActiveTechniqueId(t.id.name.value)
      ZIO.foreach_(list) { d =>
        if(d.isSystem) {
          directiveRepo.saveSystemDirective(at, d, modId, actor, None)
        } else {
          directiveRepo.saveDirective(at, d, modId, actor, None)
        }
      }
    }.runNow
  }
}


class MockRules() {
  val t1 = System.currentTimeMillis()

  val rootRuleCategory = RuleCategory(
      RuleCategoryId("rootRuleCategory")
    , "Rules"
    , "This is the main category of Rules"
    , RuleCategory(RuleCategoryId("category1"), "Category 1", "description of category 1", Nil) :: Nil
    , true
  )

  object ruleCategoryRepo extends RoRuleCategoryRepository with WoRuleCategoryRepository {

    import com.softwaremill.quicklens._

    // returns (parents, rule) if found
    def recGet(root: RuleCategory, id: RuleCategoryId) : Option[(List[RuleCategory], RuleCategory)] = {
      if(root.id == id) Some((Nil, root))
      else root.childs.foldLeft(Option.empty[(List[RuleCategory], RuleCategory)]) { case (found, cat) =>
        found match {
          case Some(x) => Some(x)
          case None    => recGet(cat, id).map { case (p, r) => (root :: p, r) }
        }
      }
    }

    // apply modification for each rule
    @tailrec
    def recUpdate(toAdd: RuleCategory, parents: List[RuleCategory]): RuleCategory = {
      parents match {
        case Nil     => toAdd
        case p :: pp =>
          recUpdate(
              p.modify(_.childs).using(children => toAdd :: children.filterNot(_.id == toAdd.id))
            , pp
          )
      }
    }

    def inDelete(root: RuleCategory, id: RuleCategoryId): RuleCategory = {
      recGet(root, id) match {
        case Some((p :: pp, x)) => recUpdate(p.modify(_.childs).using(_.filterNot(_.id == id)), pp.reverse)
        case _                  => root
      }
    }

    val categories = RefM.make(rootRuleCategory).runNow

    override def get(id: RuleCategoryId): IOResult[RuleCategory] = {
      categories.get.flatMap(c => recGet(c, id).map(_._2).notOptional(s"category with id '${id.value}' not found"))
    }
    override def getRootCategory(): IOResult[RuleCategory] = categories.get

    override def create(that: RuleCategory, into: RuleCategoryId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[RuleCategory] = {
      categories.update(cats => recGet(cats, into) match {
        case None                    => Inconsistency(s"Error: missing parent category '${into.value}'").fail
        case Some((parents, parent)) => recGet(cats, that.id) match {
          case Some((pp, p)) => Inconsistency(s"Error: category already exists '${(p :: pp.reverse).map(_.id.value)}'").fail
          // create in parent
          case None          => recUpdate(that, parent :: parents.reverse).succeed
        }
      }).map(_ => that)
    }

    override def updateAndMove(that: RuleCategory, into: RuleCategoryId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[RuleCategory] = {
      categories.update(cats =>
        recGet(cats, that.id) match {
          case None => Inconsistency(s"Category '${that.id.value}' not found, can't move it").fail
          case Some(_) => // ok, move it
            recGet(cats, into) match {
              case None => Inconsistency(s"Parent category '${into.value}' not found, can't move ${that.id.value} into it").fail
              case Some((pp, p)) =>
                val pp2 = pp.map(inDelete(_, that.id))
                val p2 = inDelete(p, that.id)
                recUpdate(p2.modify(_.childs).using(children => that :: children.filterNot(_.id == that.id)), pp2.reverse).succeed
            }
        }
      ).map(_ => that)
    }

    override def delete(category: RuleCategoryId, modId: ModificationId, actor: EventActor, reason: Option[String], checkEmpty: Boolean): IOResult[RuleCategoryId] = {
      categories.update(cats =>
        inDelete(cats, category).succeed
      ).map(_ => category)
    }
  }

  object rules {


    val commmonRule = Rule(
        RuleId("hasPolicyServer-root")
      , "Rudder system policy: basic setup (common)"
      , rootRuleCategory.id
      , Set(AllTarget)
      , Set(DirectiveId("common-root"))
      , "common-root rule"
      , "", true, true, NoTags() //long desc / enabled / system / tags
    )

    val serverRoleRule = Rule(
        RuleId("server-roles")
      , "Rudder system policy: Server roles"
      , rootRuleCategory.id
      , Set(AllTarget)
      , Set(DirectiveId("Server Roles"))
      , "Server Roles rule"
      , "", true, true, NoTags() //long desc / enabled / system / tags
    )


    val distributeRule = Rule(
        RuleId("root-DP")
      , "distributePolicy"
      , rootRuleCategory.id
      , Set(AllTarget)
      , Set(DirectiveId("Distribute Policy"))
      , "Distribute Policy rule"
      , "", true, true, NoTags() //long desc / enabled / system / tags
    )

    val inventoryAllRule = Rule(
        RuleId("inventory-all")
      , "Rudder system policy: daily inventory"
      , rootRuleCategory.id
      , Set(AllTarget)
      , Set(DirectiveId("inventory-all"))
      , "Inventory all rule"
      , "", true, true, NoTags() //long desc / enabled / system / tags
    )

    val clockRule = Rule(
        RuleId("rule1")
      , "10. Global configuration for all nodes"
      , rootRuleCategory.id
      , Set(AllTarget)
      , Set(DirectiveId("directive1"))
      , "global config for all nodes"
      , "", true, false, NoTags() //long desc / enabled / system / tags
    )


    val rpmRule = Rule(
        RuleId("rule2")
      , "50. Deploy PLOP STACK"
      , rootRuleCategory.id
      , Set(AllTarget)
      , Set(DirectiveId("directive2"))
      , "global config for all nodes"
      , "", true, false, NoTags() //long desc / enabled / system / tags
    )

    val defaultRule = Rule(
      RuleId("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f")
      , "60-rule-technique-std-lib"
      , rootRuleCategory.id
      , Set(AllTarget)
      , Set(
          DirectiveId("16617aa8-1f02-4e4a-87b6-d0bcdfb4019f") // pkg
        , DirectiveId("e9a1a909-2490-4fc9-95c3-9d0aa01717c9") // fileTemplate1
        , DirectiveId("99f4ef91-537b-4e03-97bc-e65b447514cc") // fileTemplate2
      )
      , "default rule"
      , "", true, false, NoTags() //long desc / enabled / system / tags
    )

    val copyDefaultRule = Rule(
      RuleId("ff44fb97-b65e-43c4-b8c2-000000000000")
      , "99-rule-technique-std-lib"
      , rootRuleCategory.id
      , Set(AllTarget)
      , Set(
          DirectiveId("99f4ef91-537b-4e03-97bc-e65b447514cc") // fileTemplate2
      )
      , "updated copy of default rule"
      , "", true, false, NoTags() //long desc / enabled / system / tags
    )

    val ncfTechniqueRule = Rule(
        RuleId("208716db-2675-43b9-ab57-bfbab84346aa")
      , "50-rule-technique-ncf"
      , rootRuleCategory.id
      , Set(TargetExclusion(TargetUnion(Set(AllTarget)), TargetUnion(Set(PolicyServerTarget(NodeId("root"))))))
      , Set(DirectiveId("16d86a56-93ef-49aa-86b7-0d10102e4ea9"))
      , "ncf technique rule"
      , "", true, false  //long desc / enabled / system
      , MkTags(("datacenter","Paris"),("serverType","webserver"))
    )

    val copyGitFileRule = Rule(
        RuleId("rulecopyGitFile")
      , "90-copy-git-file"
      , rootRuleCategory.id
      , Set(AllTarget)
      , Set(DirectiveId("directive-copyGitFile"))
      , "ncf technique rule"
      , "", true, false, NoTags() //long desc / enabled / system / tags
    )

    val gvd1Rule = Rule(
        RuleId("gvd-rule1")
        , "10. Test gvd ordering"
      , rootRuleCategory.id
      , Set(AllTarget)
      , Set(DirectiveId("gvd-directive1"),DirectiveId("gvd-directive2"))
      , "test gvd ordering rule"
      , "", true, false, NoTags() //long desc / enabled / system / tags
    )

    val all = List(commmonRule, serverRoleRule, distributeRule,
      inventoryAllRule, clockRule, rpmRule, defaultRule, copyDefaultRule,
      ncfTechniqueRule, copyGitFileRule
    )
  }


  object ruleRepo  extends RoRuleRepository with WoRuleRepository {

    val rulesMap = RefM.make(rules.all.map(r => (r.id,r)).toMap).runNow

    val predicate = (includeSytem: Boolean) => (r: Rule) => if(includeSytem) true else r.isSystem == false

    override def getOpt(ruleId: RuleId): IOResult[Option[Rule]] =
      rulesMap.get.map(_.get(ruleId))

    override def getAll(includeSytem: Boolean): IOResult[Seq[Rule]] = {
      rulesMap.get.map(_.valuesIterator.filter(predicate(includeSytem)).toSeq)
    }

    override def getIds(includeSytem: Boolean): IOResult[Set[RuleId]] = {
      rulesMap.get.map(_.valuesIterator.collect { case r if (predicate(includeSytem)(r)) => r.id }.toSet )
    }

    override def create(rule: Rule, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[AddRuleDiff] = {
      rulesMap.update(rules => rules.get(rule.id) match {
        case Some(_) =>
          Inconsistency(s"rule already exists: ${rule.id.value}").fail
        case None    =>
          (rules + (rule.id -> rule)).succeed
      }).map(_ => AddRuleDiff(rule))
    }

    def buildRuleDiff(old: Rule, current: Rule): Option[ModifyRuleDiff] = {
      if(old == current) None
      else {
        val diff = Diff(old, current)
        Some(ModifyRuleDiff(current.id, current.name
          , diff(_.name)
          , None
          , diff(_.targets)
          , diff(_.directiveIds)
          , diff(_.shortDescription)
          , diff(_.longDescription)
          , None // mod reasons ?
          , diff(_.isEnabledStatus)
          , diff(_.isSystem)
          , diff(_.categoryId)
          , diff(_.tags.tags)
        ))
      }
    }

    override def update(rule: Rule, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Option[ModifyRuleDiff]] = {
      rulesMap.modify(rules => rules.get(rule.id) match {
        case Some(r) if(r.isSystem) =>
          Inconsistency(s"rule is system (can't be updated here): ${rule.id.value}").fail
        case Some(r)                =>
          (r, (rules + (rule.id -> rule))).succeed
        case None                   =>
          Inconsistency(s"rule does not exist: ${rule.id.value}").fail
        }
      ).map(buildRuleDiff(_, rule))
    }

    override def updateSystem(rule: Rule, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Option[ModifyRuleDiff]] = {
      rulesMap.modify(rules => rules.get(rule.id) match {
        case Some(r) if(r.isSystem) =>
          (r, (rules + (rule.id -> rule))).succeed
        case Some(r)                =>
          Inconsistency(s"rule is not system (can't be updated here): ${rule.id.value}").fail
        case None                   =>
          Inconsistency(s"rule does not exist: ${rule.id.value}").fail
        }
      ).map(buildRuleDiff(_, rule))
    }

    override def delete(id: RuleId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[DeleteRuleDiff] = {
      rulesMap.modify(rules => rules.get(id) match {
        case Some(r) if(r.isSystem) =>
          Inconsistency(s"rule is system (can't be deleted here): ${id.value}").fail
        case Some(r)                =>
          val m = (rules - id)
          (r, m).succeed
        case None                   =>
          Inconsistency(s"rule does not exist: ${id.value}").fail
        }
      ).map(DeleteRuleDiff(_))
    }

    override def deleteSystemRule(id: RuleId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[DeleteRuleDiff] =  {
      rulesMap.modify(rules => rules.get(id) match {
        case Some(r) if(r.isSystem) =>
          val m = (rules - id)
          (r, m).succeed
        case Some(r)                =>
          Inconsistency(s"rule is not system (can't be deleted here): ${id.value}").fail
        case None                   =>
          Inconsistency(s"rule does not exist: ${id.value}").fail
        }
      ).map(DeleteRuleDiff(_))
    }

    override def swapRules(newRules: Seq[Rule]): IOResult[RuleArchiveId] = {
      // we need to keep system rules in old map, and filter out them in new one
      rulesMap.update { rules =>
        val systems = rules.valuesIterator.filter(_.isSystem)
        val newRulesUpdated = newRules.filterNot(_.isSystem) ++ systems
        newRulesUpdated.map(r => (r.id, r)).toMap.succeed
      }.map(_ => RuleArchiveId(s"swap at ${DateTime.now().toString(ISODateTimeFormat.dateTime())}"))
    }

    override def deleteSavedRuleArchiveId(saveId: RuleArchiveId): IOResult[Unit] = UIO.unit
  }
}

class MockGlobalParam() {
  import com.normation.rudder.domain.nodes.GenericProperty._

  val mode = {
    import InheritMode._
    InheritMode(ObjectMode.Override, ArrayMode.Prepend, StringMode.Append)
  }

  val stringParam = GlobalParameter("stringParam", "some string".toConfigValue, None, "a simple string param", None)
  // json: the key will be sorted alpha-num by Config lib; array value order is kept.
  val jsonParam = GlobalParameter.parse("jsonParam", """{ "string":"a string", "array": [1, 3, 2], "json": { "var2":"val2", "var1":"val1"} }""", None, "a simple string param", None).getOrElse(throw new RuntimeException("error in mock jsonParam"))
  val modeParam = GlobalParameter("modeParam", "some string".toConfigValue, Some(mode), "a simple string param", None)
  val systemParam = GlobalParameter("systemParam", "some string".toConfigValue, None, "a simple string param", Some(PropertyProvider.systemPropertyProvider))
  val all = List(stringParam, jsonParam, modeParam, systemParam).map(p => (p.name, p)).toMap

  val paramsRepo = new RoParameterRepository with WoParameterRepository {

    val paramsMap = RefM.make[Map[String, GlobalParameter]](all).runNow


    override def getGlobalParameter(parameterName: String): IOResult[Option[GlobalParameter]] = {
      paramsMap.get.map(_.get(parameterName))
    }

    override def getAllGlobalParameters(): IOResult[Seq[GlobalParameter]] = {
      paramsMap.get.map(_.valuesIterator.toSeq)
    }

    override def saveParameter(parameter: GlobalParameter, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[AddGlobalParameterDiff] = {
      paramsMap.update(params => params.get(parameter.name) match {
        case Some(_) =>
          Inconsistency(s"parameter already exists: ${parameter.name}").fail
        case None    =>
          (params + (parameter.name -> parameter)).succeed
      }).map(_ => AddGlobalParameterDiff(parameter))
    }

    override def updateParameter(parameter: GlobalParameter, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Option[ModifyGlobalParameterDiff]] = {
      paramsMap.modify(params => params.get(parameter.name) match {
        case Some(old)              =>
          (old, (params + (parameter.name -> parameter))).succeed
        case None                   =>
          Inconsistency(s"param does not exist: ${parameter.name}").fail
        }
      ).map { old =>
        val diff = Diff(old, parameter)
        Some(ModifyGlobalParameterDiff(parameter.name
          , diff(_.value)
          , diff(_.description)
          , diff(_.provider)
          , diff(_.inheritMode)
        ))
      }
    }

    override def delete(parameterName: String, provider: Option[PropertyProvider], modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Option[DeleteGlobalParameterDiff]] = {
      paramsMap.modify(params => params.get(parameterName) match {
        case Some(r)                =>
          val m = (params - parameterName)
          (Some(DeleteGlobalParameterDiff(r)), m).succeed
        case None                   =>
          (None, params).succeed
        }
      )
    }

    override def swapParameters(newParameters: Seq[GlobalParameter]): IOResult[ParameterArchiveId] = {
      paramsMap.set(newParameters.map(p => (p.name, p)).toMap) *> ParameterArchiveId("mock repo implementation").succeed
    }

    override def deleteSavedParametersArchiveId(saveId: ParameterArchiveId): IOResult[Unit] = {
      UIO.unit
    }

  }
}


// internal storage format for node repository
final case class NodeDetails(info: NodeInfo, nInv: NodeInventory, mInv: Option[MachineInventory])
final case class NodeBase(
    pending : Map[NodeId, NodeDetails]
  , accepted: Map[NodeId, NodeDetails]
  , deleted : Map[NodeId, NodeDetails]
)

class MockNodes() {
  val t2 = System.currentTimeMillis()

  val softwares = List(
    Software(SoftwareUuid("s00"), name = Some("s00"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s01"), name = Some("s01"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s02"), name = Some("s02"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s03"), name = Some("s03"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s04"), name = Some("s04"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s05"), name = Some("s05"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s06"), name = Some("s06"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s07"), name = Some("s07"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s08"), name = Some("s08"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s09"), name = Some("s09"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s10"), name = Some("s10"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s11"), name = Some("s11"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s12"), name = Some("s12"), version = Some(new Version("1.0")))
  , Software(SoftwareUuid("s13"), name = Some("s13"), version = Some(new Version("1.0")))
  )


  //a valid, not used pub key
  //cfengine key hash is: 081cf3aac62624ebbc83be7e23cb104d
  val PUBKEY =
"""-----BEGIN RSA PUBLIC KEY-----
MIIBCAKCAQEAlntroa72gD50MehPoyp6mRS5fzZpsZEHu42vq9KKxbqSsjfUmxnT
Rsi8CDvBt7DApIc7W1g0eJ6AsOfV7CEh3ooiyL/fC9SGATyDg5TjYPJZn3MPUktg
YBzTd1MMyZL6zcLmIpQBH6XHkH7Do/RxFRtaSyicLxiO3H3wapH20TnkUvEpV5Qh
zUkNM8vHZuu3m1FgLrK5NCN7BtoGWgeyVJvBMbWww5hS15IkCRuBkAOK/+h8xe2f
hMQjrt9gW2qJpxZyFoPuMsWFIaX4wrN7Y8ZiN37U2q1G11tv2oQlJTQeiYaUnTX4
z5VEb9yx2KikbWyChM1Akp82AV5BzqE80QIBIw==
-----END RSA PUBLIC KEY-----"""

  val emptyNodeReportingConfiguration = ReportingConfiguration(None,None, None)

  val id1 = NodeId("node1")
  val hostname1 = "node1.localhost"
  val admin1 = "root"
  val id2 = NodeId("node2")
  val hostname2 = "node2.localhost"
  val rootId = NodeId("root")
  val rootHostname = "server.rudder.local"
  val rootAdmin = "root"

  val rootNode = Node (
      rootId
    , "root"
    , ""
    , NodeState.Enabled
    , false
    , true
    , DateTime.parse("2021-01-30T01:20+01:00")
    , emptyNodeReportingConfiguration
    , Nil
    , Some(PolicyMode.Enforce)
  )
  val root = NodeInfo (
      rootNode
    , rootHostname
    , Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None))
    , Linux(Debian, "Stretch", new Version("9.4"), None, new Version("4.5"))
    , List("127.0.0.1", "192.168.0.100")
    , DateTime.parse("2021-01-30T01:20+01:00")
    , UndefinedKey
    , Seq(AgentInfo(CfeCommunity, Some(AgentVersion("7.0.0")), PublicKey(PUBKEY), Set()))
    , rootId
    , rootAdmin
    , Set( //by default server roles for root
          "rudder-db"
        , "rudder-inventory-endpoint"
        , "rudder-inventory-ldap"
        , "rudder-jetty"
        , "rudder-ldap"
        , "rudder-reports"
        , "rudder-server-root"
        , "rudder-webapp"
      ).map(ServerRole(_))
    , None
    , None
    , Some(NodeTimezone("UTC", "+00"))
  )

  val rootInventory: NodeInventory = NodeInventory(
      NodeSummary(
          root.id
        , AcceptedInventory
        , root.localAdministratorAccountName
        , root.hostname
        , root.osDetails
        , root.id
        , root.keyStatus
      )
    , name                 = None
    , description          = None
    , ram                  = None
    , swap                 = None
    , inventoryDate        = None
    , receiveDate          = None
    , archDescription      = None
    , lastLoggedUser       = None
    , lastLoggedUserTime   = None
    , agents               = Seq()
    , serverIps            = Seq()
    , machineId            = None //if we want several ids, we would have to ass an "alternate machine" field
    , softwareIds          = softwares.take(7).map(_.id)
    , accounts             = Seq()
    , environmentVariables = Seq(EnvironmentVariable("THE_VAR", Some("THE_VAR value!")))
    , processes            = Seq()
    , vms                  = Seq()
    , networks             = Seq()
    , fileSystems          = Seq()
    , serverRoles          = Set()
  )

  val node1Node = Node (
      id1
    , "node1"
    , ""
    , NodeState.Enabled
    , false
    , false
    , DateTime.parse("2021-01-30T01:20+01:00")
    , emptyNodeReportingConfiguration
    , Nil
    , None
  )

  val node1 = NodeInfo (
      node1Node
    , hostname1
    , Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None))
    , Linux(Debian, "Buster", new Version("10.6"), None, new Version("4.19"))
    , List("192.168.0.10")
    , DateTime.parse("2021-01-30T01:20+01:00")
    , UndefinedKey
    , Seq(AgentInfo(CfeCommunity, Some(AgentVersion("6.2.0")), PublicKey(PUBKEY), Set()))
    , rootId
    , admin1
    , Set()
    , None
    , Some(MemorySize(1460132))
    , None
  )

  val nodeInventory1: NodeInventory = NodeInventory(
      NodeSummary(
          node1.id
        , AcceptedInventory
        , node1.localAdministratorAccountName
        , node1.hostname
        , node1.osDetails
        , root.id
        , node1.keyStatus
      )
    , name                 = None
    , description          = None
    , ram                  = None
    , swap                 = None
    , inventoryDate        = None
    , receiveDate          = None
    , archDescription      = None
    , lastLoggedUser       = None
    , lastLoggedUserTime   = None
    , agents               = Seq()
    , serverIps            = Seq()
    , machineId            = None //if we want several ids, we would have to ass an "alternate machine" field
    , softwareIds          = softwares.drop(5).take(10).map(_.id)
    , accounts             = Seq()
    , environmentVariables = Seq(EnvironmentVariable("THE_VAR", Some("THE_VAR value!")))
    , processes            = Seq()
    , vms                  = Seq()
    , networks             = Seq()
    , fileSystems          = Seq()
    , serverRoles          = Set()
  )

  //node1 us a relay
  val node2Node = node1Node.copy(id = id2, name = id2.value)
  val node2 = node1.copy(node = node2Node, hostname = hostname2, policyServerId = root.id )
  val nodeInventory2: NodeInventory = {
    import com.softwaremill.quicklens._
    nodeInventory1.copy().modify(_.main).setTo(
      NodeSummary(
        node2.id
        , AcceptedInventory
        , node2.localAdministratorAccountName
        , node2.hostname
        , node2.osDetails
        , root.id
        , node2.keyStatus
      )
    )
  }

  val dscNode1Node = Node (
      NodeId("node-dsc")
    , "node-dsc"
    , ""
    , NodeState.Enabled
    , false
    , true //is draft server
    , DateTime.parse("2021-01-30T01:20+01:00")
    , emptyNodeReportingConfiguration
    , Nil
    , None
  )

  val dscNode1 = NodeInfo (
      dscNode1Node
    , "node-dsc.localhost"
    , Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None))
    , Windows(Windows2012, "Windows 2012 youpla boom", new Version("2012"), Some("sp1"), new Version("win-kernel-2012"))
    , List("192.168.0.5")
    , DateTime.parse("2021-01-30T01:20+01:00")
    , UndefinedKey
    , Seq(AgentInfo(AgentType.Dsc, Some(AgentVersion("7.0.0")), Certificate("windows-node-dsc-certificate"), Set()))
    , rootId
    , admin1
    , Set()
    , None
    , Some(MemorySize(1460132))
    , None
  )

  val dscInventory1: NodeInventory = NodeInventory(
      NodeSummary(
          dscNode1.id
        , AcceptedInventory
        , dscNode1.localAdministratorAccountName
        , dscNode1.hostname
        , dscNode1.osDetails
        , dscNode1.policyServerId
        , dscNode1.keyStatus
      )
    , name                 = None
    , description          = None
    , ram                  = None
    , swap                 = None
    , inventoryDate        = None
    , receiveDate          = None
    , archDescription      = None
    , lastLoggedUser       = None
    , lastLoggedUserTime   = None
    , agents               = Seq()
    , serverIps            = Seq()
    , machineId            = None //if we want several ids, we would have to ass an "alternate machine" field
    , softwareIds          = softwares.drop(5).take(7).map(_.id)
    , accounts             = Seq()
    , environmentVariables = Seq(EnvironmentVariable("THE_VAR", Some("THE_VAR value!")))
    , processes            = Seq()
    , vms                  = Seq()
    , networks             = Seq()
    , fileSystems          = Seq()
    , serverRoles          = Set()
  )


  val allNodesInfo = Map( rootId -> root, node1.id -> node1, node2.id -> node2)
  // both nodeInfoService and repo, since we deal with the same underlying node objects
  object nodeInfoService extends NodeInfoService with LDAPFullInventoryRepository {

    // node status is in inventory.main.
    val nodeBase = RefM.make(Map(
        (rootId     , NodeDetails(root    , rootInventory , None))
      , (node1.id   , NodeDetails(node1   , nodeInventory1, None))
      , (node2.id   , NodeDetails(node2   , nodeInventory2, None))
      , (dscNode1.id, NodeDetails(dscNode1, dscInventory1 , None))
    )).runNow

    def getGenericOne[A](id: NodeId, status: InventoryStatus, f:NodeDetails => Option[A]): IOResult[Option[A]] = {
      nodeBase.get.map(_.collectFirst { case(id, n) if (id == id && n.nInv.main.status == status && f(n).isDefined) => f(n).get })
    }
    def getGenericAll[A](status: InventoryStatus, f:NodeDetails => Option[A]): IOResult[Map[NodeId, A]] = {
      nodeBase.get.map(_.collect { case(id, n) if(n.nInv.main.status == status && f(n).isDefined) => (id, f(n).get) })
    }
    def _info(node: NodeDetails) = Option(node.info)
    def _fullInventory(node: NodeDetails) = Option(FullInventory(node.nInv, node.mInv))

    override def getNodeInfoPure(nodeId: NodeId): IOResult[Option[NodeInfo]] = getGenericOne(nodeId, AcceptedInventory, _info)
    override def getNodeInfo(nodeId: NodeId): Box[Option[NodeInfo]] = getNodeInfoPure(nodeId).toBox

    override def getAll(): Box[Map[NodeId, NodeInfo]] = getGenericAll(AcceptedInventory, _info).toBox

    override def getAllNodes(): Box[Map[NodeId, Node]] = getAll().map(_.map(kv => (kv._1, kv._2.node)))
    override def getAllSystemNodeIds(): Box[Seq[NodeId]] = {
      nodeBase.get.map(_.collect { case (id, n)  if(n.info.isSystem) => id }.toSeq ).toBox
    }

    override def getPendingNodeInfoPure(nodeId: NodeId): IOResult[Option[NodeInfo]] = getGenericOne(nodeId, PendingInventory, _info)
    override def getPendingNodeInfos(): Box[Map[NodeId, NodeInfo]] = getGenericAll(PendingInventory, _info).toBox

    override def getDeletedNodeInfoPure(nodeId: NodeId): IOResult[Option[NodeInfo]] = getGenericOne(nodeId, RemovedInventory, _info)
    override def getDeletedNodeInfos(): Box[Map[NodeId, NodeInfo]] = getGenericAll(RemovedInventory, _info).toBox

    override def get(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Option[FullInventory]] = getGenericOne(id, inventoryStatus, _fullInventory)
    override def get(id: NodeId): IOResult[Option[FullInventory]] = {
      nodeBase.get.map(_.collectFirst { case(id, n) if (id == id) => FullInventory(n.nInv, n.mInv) })
    }

    override def getMachineId(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Option[(MachineUuid, InventoryStatus)]] = {
      getGenericOne(id, inventoryStatus, n => n.mInv.map(x => (x.id, x.status)))
    }

    override def getAllInventories(inventoryStatus: InventoryStatus): IOResult[Map[NodeId, FullInventory]] = getGenericAll(inventoryStatus, _fullInventory)
    override def getAllNodeInventories(inventoryStatus: InventoryStatus): IOResult[Map[NodeId, NodeInventory]] = getGenericAll(inventoryStatus, _fullInventory(_).map(_.node))

    // not implemented yet
    override def getLDAPNodeInfo(nodeIds: Set[NodeId], predicates: Seq[NodeInfoMatcher], composition: CriterionComposition): Box[Set[LDAPNodeInfo]] = ???
    override def getNumberOfManagedNodes: Int = ???
    override def save(serverAndMachine: FullInventory): IOResult[Seq[LDIFChangeRecord]] = ???
    override def delete(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Seq[LDIFChangeRecord]] = ???
    override def move(id: NodeId, from: InventoryStatus, into: InventoryStatus): IOResult[Seq[LDIFChangeRecord]] = ???
    override def moveNode(id: NodeId, from: InventoryStatus, into: InventoryStatus): IOResult[Seq[LDIFChangeRecord]] = ???
  }

  val defaultModesConfig = NodeModeConfig(
      globalComplianceMode = GlobalComplianceMode(FullCompliance, 30)
    , nodeHeartbeatPeriod  = None
    , globalAgentRun       = AgentRunInterval(None, 5, 0, 0, 0)
    , nodeAgentRun         = None
    , globalPolicyMode     = GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always)
    , nodePolicyMode       = None
  )

  val rootNodeConfig = NodeConfiguration(
      nodeInfo    = root
    , modesConfig = defaultModesConfig
    , runHooks    = List()
    , policies    = List[Policy]()
    , nodeContext = Map[String, Variable]()
    , parameters  = Set[ParameterForConfiguration]()
    , isRootServer= true
  )

  val node1NodeConfig = NodeConfiguration(
      nodeInfo    = node1
    , modesConfig = defaultModesConfig
    , runHooks    = List()
    , policies    = List[Policy]()
    , nodeContext = Map[String, Variable]()
    , parameters  = Set[ParameterForConfiguration]()
    , isRootServer= false
  )

  val node2NodeConfig = NodeConfiguration(
      nodeInfo    = node2
    , modesConfig = defaultModesConfig
    , runHooks    = List()
    , policies    = List[Policy]()
    , nodeContext = Map[String, Variable]()
    , parameters  = Set[ParameterForConfiguration]()
    , isRootServer= false
  )

  /**
   * Some more nodes
   */
  val nodeIds = (for {
    i <- 0 to 10
  } yield {
    NodeId(s"${i}")
  }).toSet

  def newNode(id : NodeId) = Node(id,"" ,"", NodeState.Enabled, false, false, DateTime.now, ReportingConfiguration(None,None, None), Nil, None)

  val nodes = (Set(root, node1, node2) ++ nodeIds.map {
    id =>
      NodeInfo (
            newNode(id)
          , s"Node-${id}"
          , None
          , Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2"))
          , Nil, DateTime.now
          , UndefinedKey, Seq(AgentInfo(CfeCommunity, None, PublicKey("rsa public key"), Set())), NodeId("root")
          , "" , Set(), None, None, None
    )
  }).map(n => (n.id, n)).toMap


  object softwareDao extends ReadOnlySoftwareDAO {
    val softRef = RefM.make(softwares.map(s => (s.id, s)).toMap).runNow

    override def getSoftware(ids: Seq[SoftwareUuid]): IOResult[Seq[Software]] = {
      softRef.get.map(_.map(_._2).toList)
    }

    override def getSoftwareByNode(nodeIds: Set[NodeId], status: InventoryStatus): IOResult[Map[NodeId, Seq[Software]]] = {
      for {
        inventories <- nodeInfoService.getAllInventories(status)
        softwares   <- softRef.get
      } yield {
        inventories.collect {
          case (id,inv) if(nodeIds.contains(id)) =>
            (
              id , softwares.collect {
                    case (k,s) if(inv.node.softwareIds.contains(k)) => s
                  }.toList
            )
        }
      }
    }

    override def getAllSoftwareIds(): IOResult[Set[SoftwareUuid]] = softRef.get.map(_.keySet)

    override def getSoftwaresForAllNodes(): IOResult[Set[SoftwareUuid]] = {
      nodeInfoService.getAllInventories(AcceptedInventory).map( _.flatMap(_._2.node.softwareIds).toSet)
    }

    def getNodesbySofwareName(softName: String): IOResult[List[(NodeId, Software)]] ={
      for {
        inventories <- nodeInfoService.getAllInventories(AcceptedInventory)
        softwares   <- softRef.get
      } yield {

        inventories.toList.flatMap {
          case (id, inv) =>
            softwares.collect {
              case (k, s) if (s.name.exists(_ == softName) && inv.node.softwareIds.contains(k)) => (id,s)
            }

        }
      }
    }
  }

  object queryProcessor extends QueryProcessor {
    import com.normation.inventory.ldap.core.LDAPConstants._
    import com.normation.rudder.domain.RudderLDAPConstants._
    import cats.implicits._

    //return the value to corresponding to the given object/attribute
    def buildValues(objectName: String, attribute: String): PureResult[NodeDetails => List[String]] = {
      objectName match {
        case OC_NODE =>
          attribute match {
            case "OS"                 => Right((n: NodeDetails) => List(n.info.osDetails.os.name))
            case A_NODE_UUID          => Right((n: NodeDetails) => List(n.info.id.value))
            case A_HOSTNAME           => Right((n: NodeDetails) => List(n.info.hostname))
            case A_OS_NAME            => Right((n: NodeDetails) => List(n.info.osDetails.os.name))
            case A_OS_FULL_NAME       => Right((n: NodeDetails) => List(n.info.osDetails.fullName))
            case A_OS_VERSION         => Right((n: NodeDetails) => List(n.info.osDetails.version.value))
            case A_OS_SERVICE_PACK    => Right((n: NodeDetails) =>      n.info.osDetails.servicePack.toList)
            case A_OS_KERNEL_VERSION  => Right((n: NodeDetails) => List(n.info.osDetails.kernelVersion.value))
            case A_ARCH               => Right((n: NodeDetails) =>      n.info.archDescription.toList)
            case A_SERVER_ROLE        => Right((n: NodeDetails) =>      n.info.serverRoles.map(_.value).toList)
            case A_STATE              => Right((n: NodeDetails) => List(n.info.state.name))
            case A_OS_RAM             => Right((n: NodeDetails) =>      n.info.ram.map(_.size.toString).toList)
            case A_OS_SWAP            => Right((n: NodeDetails) =>      n.nInv.swap.map(_.size.toString).toList)
            case A_AGENTS_NAME        => Right((n: NodeDetails) =>      n.info.agentsName.map(_.agentType.id).toList)
            case A_ACCOUNT            => Right((n: NodeDetails) =>      n.nInv.accounts.toList)
            case A_LIST_OF_IP         => Right((n: NodeDetails) =>      n.info.ips)
            case A_ROOT_USER          => Right((n: NodeDetails) => List(n.info.localAdministratorAccountName))
            case A_INVENTORY_DATE     => Right((n: NodeDetails) => List(n.info.inventoryDate.toString(ISODateTimeFormat.dateTimeNoMillis())))
            case A_POLICY_SERVER_UUID => Right((n: NodeDetails) => List(n.info.policyServerId.value))
            case _ => Left(Inconsistency(s"object '${objectName}' doesn't have attribute '${attribute}'"))
         }
        case x => Left(Unexpected(s"Case value '${x}' for query processor not yet implemented in test, see `MockServices.scala`"))
      }
    }

    def compare(nodeValues: List[String], comparator: CriterionComparator, expectedValue: String): PureResult[Boolean] = {
      comparator match {
        case Exists    => Right(nodeValues.nonEmpty)
        case NotExists => Right(nodeValues.isEmpty)
        case Equals    => Right(nodeValues.exists(_ == expectedValue))
        case NotEquals => Right(nodeValues.nonEmpty && nodeValues.forall(_ != expectedValue))
        case Regex     =>
          try {
            val p = expectedValue.r.pattern
            Right(nodeValues.exists(p.matcher(_).matches()))
          } catch {
            case NonFatal(ex) => Left(Unexpected(s"Error in regex comparator in test: ${ex.getMessage}"))
          }
        case x         => Left(Unexpected(s"Comparator '${x} / ${expectedValue}' not yet implemented in test, see `MockServices.scala`"))
      }
    }


    def filterForLine(line: CriterionLine, nodes: List[NodeDetails]): PureResult[List[NodeDetails]] = {
      for {
        values   <- buildValues(line.objectType.objectType, line.attribute.name)
        matching <- nodes.traverse(n => compare(values(n), line.comparator, line.value).map(if(_) Some(n) else None))
      } yield {
        matching.flatten
      }
    }

    def filterForLines(lines: List[CriterionLine], combine: CriterionComposition, nodes: List[NodeDetails]): PureResult[List[NodeDetails]] = {
      for {
        byLine <- lines.traverse(filterForLine(_, nodes))
      } yield {
        combine match {
          case And =>
            (nodes :: byLine).reduce((a,b) => a.intersect(b))
          case Or  =>
            (Nil :: byLine).reduce((a,b) => a ++ b)
        }
      }
    }

    override def process(query: QueryTrait): Box[Seq[NodeInfo]] = {
      for {
        nodes    <- nodeInfoService.nodeBase.get
        matching <- filterForLines(query.criteria, query.composition, nodes.map(_._2).toList).toIO
      } yield {
        matching.map(_.info)
      }
    }.toBox

    override def processOnlyId(query: QueryTrait): Box[Seq[NodeId]] = process(query).map(_.map(_.id))
  }
}

class MockNodeGroups(nodesRepo: MockNodes) {

  val g0props = List(
    GroupProperty(
        "stringParam"  // inherited from global param
      , "string".toConfigValue
      , Some(InheritMode.parseString("map").getOrElse(null))
      , Some(PropertyProvider("datasources"))
    )
  , GroupProperty.parse("jsonParam"
      , """{ "group":"string", "array": [5,6], "json": { "g1":"g1"} }"""
      , None
      , None
    ).getOrElse(null) // for test
  )

  val g0 = NodeGroup (NodeGroupId("0000f5d3-8c61-4d20-88a7-bb947705ba8a"), "Real nodes"           , "", g0props, None, false, Set(nodesRepo.rootId, nodesRepo.node1.id, nodesRepo.node2.id), true)
  val g1 = NodeGroup (NodeGroupId("1111f5d3-8c61-4d20-88a7-bb947705ba8a"), "Empty group"          , "", Nil    , None, false, Set(), true)
  val g2 = NodeGroup (NodeGroupId("2222f5d3-8c61-4d20-88a7-bb947705ba8a"), "only root"            , "", Nil    , None, false, Set(NodeId("root")), true)
  val g3 = NodeGroup (NodeGroupId("3333f5d3-8c61-4d20-88a7-bb947705ba8a"), "Even nodes"           , "", Nil    , None, false, nodesRepo.nodeIds.filter(_.value.toInt%2 == 0), true)
  val g4 = NodeGroup (NodeGroupId("4444f5d3-8c61-4d20-88a7-bb947705ba8a"), "Odd nodes"            , "", Nil    , None, false, nodesRepo.nodeIds.filter(_.value.toInt%2 != 0), true)
  val g5 = NodeGroup (NodeGroupId("5555f5d3-8c61-4d20-88a7-bb947705ba8a"), "Nodes id divided by 3", "", Nil    , None, false, nodesRepo.nodeIds.filter(_.value.toInt%3 == 0), true)
  val g6 = NodeGroup (NodeGroupId("6666f5d3-8c61-4d20-88a7-bb947705ba8a"), "Nodes id divided by 5", "", Nil    , None, false, nodesRepo.nodeIds.filter(_.value.toInt%5 == 0), true)
  val groups = Set(g0, g1, g2, g3, g4, g5, g6).map(g => (g.id, g))

  val groupsTargets = groups.map{ case (id, g) => (GroupTarget(g.id), g) }

  val groupsTargetInfos = (groupsTargets.map(gt =>
    ( gt._1.groupId
    , FullRuleTargetInfo(
          FullGroupTarget(gt._1,gt._2)
        , ""
        , ""
        , true
        , false
      )
    )
  )).toMap

  val groupLib = FullNodeGroupCategory(
      NodeGroupCategoryId("GroupRoot")
    , "GroupRoot"
    , "root of group categories"
    , List(
        FullNodeGroupCategory(
            NodeGroupCategoryId("category1")
          , "category 1"
          , "the first category"
          , Nil
          , Nil
          , false
        )
      )
    , List(
          FullRuleTargetInfo(
              FullGroupTarget(
                  GroupTarget(NodeGroupId("a-group-for-root-only"))
                , NodeGroup(NodeGroupId("a-group-for-root-only")
                    , "Serveurs [€ðŋ] cassés"
                    , "Liste de l'ensemble de serveurs cassés à réparer"
                    , Nil
                    , None
                    , true
                    , Set(NodeId("root"))
                    , true
                    , false
                  )
              )
              , "Serveurs [€ðŋ] cassés"
              , "Liste de l'ensemble de serveurs cassés à réparer"
              , true
              , false
            )
        , FullRuleTargetInfo(
              FullOtherTarget(PolicyServerTarget(NodeId("root")))
            , "special:policyServer_root"
            , "The root policy server"
            , true
            , true
          )
        , FullRuleTargetInfo(
            FullOtherTarget(AllTargetExceptPolicyServers)
            , "special:all_exceptPolicyServers"
            , "All groups without policy servers"
            , true
            , true
          )
        , FullRuleTargetInfo(
            FullOtherTarget(AllTarget)
            , "special:all"
            , "All nodes"
            , true
            , true
          )
      ) ++ groupsTargetInfos.valuesIterator
    , true
  )

  object groupsRepo extends RoNodeGroupRepository with WoNodeGroupRepository {

    implicit val ordering = com.normation.rudder.repository.NodeGroupCategoryOrdering

    val categories = RefM.make(groupLib).runNow

    override def getFullGroupLibrary(): IOResult[FullNodeGroupCategory] = categories.get

    override def getNodeGroupOpt(id: NodeGroupId): IOResult[Option[(NodeGroup, NodeGroupCategoryId)]] = {
      categories.get.map(root =>
        ((root.allGroups.get(id), root.categoryByGroupId.get(id)) match {
          case (Some(g), Some(c)) => Some((g.nodeGroup,c))
          case _                  => None
        })
      )
    }
    override def getNodeGroupCategory(id: NodeGroupId): IOResult[NodeGroupCategory] = {
      for {
        root <- categories.get
        cid  <- root.categoryByGroupId.get(id).notOptional(s"Category for group '${id.value}' not found")
        cat  <- root.allCategories.get(cid).map(_.toNodeGroupCategory).notOptional(s"Category '${cid.value}' not found")
      } yield cat
    }
    override def getAll(): IOResult[Seq[NodeGroup]] = categories.get.map(_.allGroups.values.map(_.nodeGroup).toSeq)

    override def getGroupsByCategory(includeSystem: Boolean): IOResult[immutable.SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]] = {
      def getChildren(parents: List[NodeGroupCategoryId], root: FullNodeGroupCategory) : immutable.SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup] = {
        val c = immutable.SortedMap((root.id :: parents, CategoryAndNodeGroup(root.toNodeGroupCategory, root.ownGroups.values.map(_.nodeGroup).toSet)))
        root.subCategories.foldLeft(c) { case (current, n) => current ++ getChildren(root.id :: parents, n) }
      }
      val c = categories.get.map { root =>
        val all = getChildren(Nil, root)
        if (includeSystem) all else {
          all.filterNot(_._2.category.isSystem)
        }
      }
      c
    }

    override def getCategoryHierarchy: IOResult[immutable.SortedMap[List[NodeGroupCategoryId], NodeGroupCategory]] = getGroupsByCategory(true).map(
      _.map { case (k,v) => (k, v.category) }
    )

    override def findGroupWithAnyMember(nodeIds: Seq[NodeId]): IOResult[Seq[NodeGroupId]] = {
      categories.get.map { root =>
        root.allGroups.collect { case(_, c) if(c.nodeGroup.serverList.exists(s => nodeIds.contains(s))) => c.nodeGroup.id }.toSeq
      }
    }

    override def findGroupWithAllMember(nodeIds: Seq[NodeId]): IOResult[Seq[NodeGroupId]] = {
      categories.get.map { root =>
        root.allGroups.collect { case(_, c) if(nodeIds.forall(s => c.nodeGroup.serverList.contains(s))) => c.nodeGroup.id }.toSeq
      }
    }

    override def getRootCategoryPure(): IOResult[NodeGroupCategory] = categories.get.map(_.toNodeGroupCategory)
    override def getRootCategory(): NodeGroupCategory = getRootCategoryPure().runNow

    override def getAllGroupCategories(includeSystem: Boolean): IOResult[Seq[NodeGroupCategory]] = {
      categories.get.map(_.allCategories.values.collect {
        case c if(!c.isSystem || c.isSystem && includeSystem) => c.toNodeGroupCategory
      }).map(_.toSeq)
    }

    override def getGroupCategory(id: NodeGroupCategoryId): IOResult[NodeGroupCategory] = {
      categories.get.flatMap(_.allCategories.get(id).notOptional(s"Category '${id.value}' not found").map(_.toNodeGroupCategory))
    }

    override def getParentGroupCategory(id: NodeGroupCategoryId): IOResult[NodeGroupCategory] = {
      categories.get.flatMap { root =>
        root.parentCategories.get(id).notOptional(s"Parent of category '${id.value}' not found").map(_.toNodeGroupCategory)
      }
    }


    // get parents from nearest to root
    def recGetParent(root: FullNodeGroupCategory, id: NodeGroupCategoryId): List[FullNodeGroupCategory] = {
      root.parentCategories.get(id) match {
        case Some(cat) => cat :: recGetParent(root, cat.id)
        case None => Nil
      }
    }

    override def getParents_NodeGroupCategory(id: NodeGroupCategoryId): IOResult[List[NodeGroupCategory]] = {
      categories.get.map(recGetParent(_, id).map(_.toNodeGroupCategory))
    }

    override def getAllNonSystemCategories(): IOResult[Seq[NodeGroupCategory]] = getAllGroupCategories(false)

    // returns (parents, group) if found
    def recGetCat(root: FullNodeGroupCategory, id: NodeGroupCategoryId) : Option[(List[FullNodeGroupCategory], FullNodeGroupCategory)] = {
      if(root.id == id) Some((Nil, root))
      else root.subCategories.foldLeft(Option.empty[(List[FullNodeGroupCategory], FullNodeGroupCategory)]) { case (found, cat) =>
        found match {
          case Some(x) => Some(x)
          case None    => recGetCat(cat, id).map { case (p, r) => (root :: p, r) }
        }
      }
    }

    // Add `toAdd` at the bottom of the list (which goes from direct parent to root),
    // and modify children category along the way.
    // So, if there is a non-empty list, head is direct parent: replace toAdd in children, then recurse
    @tailrec
    def recUpdateCat(toAdd: FullNodeGroupCategory, parents: List[FullNodeGroupCategory]): FullNodeGroupCategory = {
      import com.softwaremill.quicklens._
      parents match {
        case Nil     => toAdd
        case p :: pp =>
          recUpdateCat(
              p.modify(_.subCategories).using(children => toAdd :: children.filterNot(_.id == toAdd.id))
            , pp
          )
      }
    }

    def inDeleteCat(root: FullNodeGroupCategory, id: NodeGroupCategoryId): FullNodeGroupCategory = {
      import com.softwaremill.quicklens._
      recGetCat(root, id) match {
        case Some((p :: pp, x)) => recUpdateCat(p.modify(_.subCategories).using(_.filterNot(_.id == id)), pp.reverse)
        case _                  => root
      }
    }

    // only used in relay plugin
    override def createPolicyServerTarget(target: PolicyServerTarget, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[LDIFChangeRecord] = ???

    // create group into Some(cat) (group must not exists) or update group (into=None, group must exists)
    def createOrUpdate(group: NodeGroup, into: Option[NodeGroupCategoryId]): IOResult[Option[NodeGroup]] = {
      categories.modify(root =>
        for {
          catId <- into match {
                     case Some(catId) => root.allGroups.get(group.id) match {
                       case Some(n) => Inconsistency(s"Group with id '${n.nodeGroup.id.value}' already exists'").fail
                       case None    => catId.succeed
                     }
                     case None => root.categoryByGroupId.get(group.id) match {
                       case None     => Inconsistency(s"Group '${group.id.value}' not found").fail
                       case Some(id) => id.succeed
                     }
                   }
          cat   <- root.allCategories.get(catId) match {
                     case None      => Inconsistency(s"Category '${catId.value}' not found").fail
                     case Some(cat) => cat.succeed
                   }
          // previous group, for diff
          old   = cat.ownGroups.get(group.id).map(_.nodeGroup)
        } yield {
          val t = FullRuleTargetInfo(FullGroupTarget(GroupTarget(group.id), group), group.name, group.description, group.isEnabled, group.isSystem)
          import com.softwaremill.quicklens._
          val c = cat.modify(_.targetInfos).using(children => t :: children.filterNot(_.target.target.target == t.target.target.target))
          val parents = recGetParent(root, c.id)
          (old, recUpdateCat(c, parents))
        }
      )
    }

    override def create(group: NodeGroup, into: NodeGroupCategoryId, modId: ModificationId, actor: EventActor, why: Option[String]): IOResult[AddNodeGroupDiff] = {
      createOrUpdate(group, Some(into)).flatMap {
        case None    => AddNodeGroupDiff(group).succeed
        case Some(_) => Inconsistency(s"Group '${group.id.value}' was present'").fail
      }
    }

    override def update(group: NodeGroup, modId: ModificationId, actor: EventActor, whyDescription: Option[String]): IOResult[Option[ModifyNodeGroupDiff]] = {
      createOrUpdate(group, None).flatMap {
        case None      => Inconsistency(s"Group '${group.id.value}' was missing").fail
        case Some(old) =>
          val diff = Diff(old, group)
          Some(ModifyNodeGroupDiff(group.id, group.name
          , diff(_.name)
          , diff(_.description)
          , diff(_.properties)
          , diff(_.query)
          , diff(_.isDynamic)
          , diff(_.serverList)
          , diff(_.isEnabled)
          , diff(_.isSystem)
        )).succeed
      }
    }

    override def delete(id: NodeGroupId, modId: ModificationId, actor: EventActor, whyDescription: Option[String]): IOResult[DeleteNodeGroupDiff] = {
       def recDelete(id: NodeGroupId, current: FullNodeGroupCategory): FullNodeGroupCategory = {
         current.copy(
             targetInfos = current.targetInfos.filterNot(_.toTargetInfo.target.target == s"group:${id.value}")
           , subCategories = current.subCategories.map(recDelete(id, _))
         )
       }
      categories.modify(root =>
        root.allGroups.get(id) match {
          case None    => Inconsistency(s"Group already deleted").fail
          case Some(g) => (g, recDelete(id, root)).succeed
        }
      ).map(g => DeleteNodeGroupDiff(g.nodeGroup))
    }
    override def delete(id: NodeGroupCategoryId, modificationId: ModificationId, actor: EventActor, reason: Option[String], checkEmpty: Boolean): IOResult[NodeGroupCategoryId] = {
       def recDelete(id: NodeGroupCategoryId, current: FullNodeGroupCategory): FullNodeGroupCategory = {
         current.copy(subCategories = current.subCategories.filterNot(_.id == id).map(c => recDelete(id, c)))
       }
      categories.update(root =>
        root.allCategories.get(id) match {
          case None      => root.succeed
          case Some(cat) => recDelete(id, root).succeed
        }
      ).map(_ => id)
    }

    override def updateDiffNodes(group: NodeGroupId, add: List[NodeId], delete: List[NodeId], modId: ModificationId, actor: EventActor, whyDescription: Option[String]): IOResult[Option[ModifyNodeGroupDiff]] = ???

    override def updateSystemGroup(group: NodeGroup, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Option[ModifyNodeGroupDiff]] = ???

    override def updateDynGroupNodes(group: NodeGroup, modId: ModificationId, actor: EventActor, whyDescription: Option[String]): IOResult[Option[ModifyNodeGroupDiff]] = ???

    override def move(group: NodeGroupId, containerId: NodeGroupCategoryId, modId: ModificationId, actor: EventActor, whyDescription: Option[String]): IOResult[Option[ModifyNodeGroupDiff]] = ???

    override def deletePolicyServerTarget(policyServer: PolicyServerTarget): IOResult[PolicyServerTarget] = ???


    def updateCategory(t: FullNodeGroupCategory, into: FullNodeGroupCategory, root: FullNodeGroupCategory): FullNodeGroupCategory = {
      import com.softwaremill.quicklens._
      val c = into.modify(_.subCategories).using(children => t :: children.filterNot(_.id == t.id))
      val parents = recGetParent(root, c.id)
      recUpdateCat(c, parents)
    }

    override def addGroupCategorytoCategory(that: NodeGroupCategory, into: NodeGroupCategoryId, modificationId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[NodeGroupCategory] = {
      categories.update(root =>
        for {
          cat <- root.allCategories.get(into).notOptional(s"Missing target parent category '${into.value}'")
        } yield {
          val t = FullNodeGroupCategory(that.id, that.name, that.description, Nil, Nil, that.isSystem)
          updateCategory(t, cat, root)
        }
      ).map(_ => that)
    }

    override def saveGroupCategory(category: NodeGroupCategory, modificationId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[NodeGroupCategory] = {
      categories.update(root =>
        for {
          cat <- root.parentCategories.get(category.id).notOptional(s"Missing target parent category of '${category.id.value}'")
        } yield {
          val t = FullNodeGroupCategory(category.id, category.name, category.description, Nil, Nil, category.isSystem)
          updateCategory(t, cat, root)
        }
      ).map(_ => category)
    }


    override def saveGroupCategory(category: NodeGroupCategory, containerId: NodeGroupCategoryId, modificationId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[NodeGroupCategory] = {
      categories.update(root =>
        for {
          cat <- root.allCategories.get(containerId).notOptional(s"Missing target parent category '${containerId.value}'")
        } yield {
          val t = FullNodeGroupCategory(category.id, category.name, category.description, Nil, Nil, category.isSystem)
          updateCategory(t, cat, root)
        }
      ).map(_ => category)
    }
  }
}


object TEST {
  import com.normation.zio._

  val mock = new MockNodeGroups(new MockNodes)
  val repo = mock.groupsRepo

  val prog = for {
    pair      <- repo.getNodeGroup(NodeGroupId("1111f5d3-8c61-4d20-88a7-bb947705ba8a"))
    (g,catId) =  pair
    nodes     =  Set(NodeId("node1"))
    g1        =  g.copy(serverList = nodes)
    _         <- repo.update(g1, ModificationId("plop"), EventActor("plop"), None)
    pair2     <- repo.getNodeGroup(NodeGroupId("1111f5d3-8c61-4d20-88a7-bb947705ba8a"))
    res       =  if(pair2._1.serverList == nodes) "ok" else s"oups, list=${pair2._1.serverList}"
    _         <- effectUioUnit(println(s"**** " + res))
  } yield (res)

  def main(args: Array[String]): Unit = prog.runNow


}
