/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.repository.ldap

import com.unboundid.ldap.sdk.DN
import com.normation.utils.Utils
import com.normation.utils.Control._
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.LDAPConstants
import LDAPConstants._
import com.normation.ldap.sdk._
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services._
import com.normation.rudder.domain.Constants._
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.{NodeDit,RudderDit}
import com.normation.rudder.domain.servers._
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.JsonSerialisation._
import com.normation.rudder.domain.queries._
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.services.queries._
import org.joda.time.Duration
import org.joda.time.DateTime
import net.liftweb.common._
import Box._
import net.liftweb.util.Helpers._
import scala.xml.{Text,NodeSeq}
import com.normation.exceptions.{BusinessException,TechnicalException}
import net.liftweb.json.JsonAST.JObject
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.api.ApiAccountId
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.api.ApiToken
import com.normation.rudder.domain.parameters._
import com.normation.rudder.api.ApiAccountName
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.appconfig.RudderWebPropertyName
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import net.liftweb.json._
import JsonDSL._
import com.normation.rudder.reports._
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.repository.json.DataExtractor.CompleteJson

/**
 * Map objects from/to LDAPEntries
 *
 */
class LDAPEntityMapper(
    rudderDit      : RudderDit
  , nodeDit        : NodeDit
  , inventoryDit   : InventoryDit
  , cmdbQueryParser: CmdbQueryParser
  , inventoryMapper: InventoryMapper
) extends Loggable {

    //////////////////////////////    Node    //////////////////////////////

  def nodeToEntry(node:Node) : LDAPEntry = {
    val entry =
      if(node.isPolicyServer) {
        nodeDit.NODES.NODE.policyServerNodeModel(node.id)
      } else {
        nodeDit.NODES.NODE.nodeModel(node.id)
      }
    entry +=! (A_NAME, node.name)
    entry +=! (A_DESCRIPTION, node.description)
    entry +=! (A_IS_BROKEN, node.isBroken.toLDAPString)
    entry +=! (A_IS_SYSTEM, node.isSystem.toLDAPString)

    node.nodeReportingConfiguration.agentRunInterval match {
      case Some(interval) => entry +=! (A_SERIALIZED_AGENT_RUN_INTERVAL, Printer.compact(JsonAST.render(serializeAgentRunInterval(interval))))
      case _ =>
    }

    entry +=! (A_NODE_PROPERTY, node.properties.map(x => Printer.compact(JsonAST.render(x.toLdapJson))):_* )

    node.nodeReportingConfiguration.heartbeatConfiguration match {
      case Some(heatbeatConfiguration) =>
        val json = {
          import net.liftweb.json.JsonDSL._
          ( "overrides"  , heatbeatConfiguration.overrides ) ~
          ( "heartbeatPeriod" , heatbeatConfiguration.heartbeatPeriod)
        }
        entry +=! (A_SERIALIZED_HEARTBEAT_RUN_CONFIGURATION, Printer.compact(JsonAST.render(json)))
      case _ => // Save nothing if missing
    }

    for {
      mode <- node.policyMode
    } entry += (A_POLICY_MODE, mode.name)

    entry
  }

  def serializeAgentRunInterval(agentInterval: AgentRunInterval) : JObject = {
    import net.liftweb.json.JsonDSL._
    ( "overrides"  , agentInterval.overrides ) ~
    ( "interval"   , agentInterval.interval ) ~
    ( "startMinute", agentInterval.startMinute ) ~
    ( "startHour"  , agentInterval.startHour ) ~
    ( "splaytime"  , agentInterval.splaytime )
  }

  def unserializeAgentRunInterval(value:String): AgentRunInterval = {
    import net.liftweb.json.JsonParser._
    implicit val formats = DefaultFormats

    parse(value).extract[AgentRunInterval]
  }

  def unserializeNodeHeartbeatConfiguration(value:String): HeartbeatConfiguration = {
    import net.liftweb.json.JsonParser._
    implicit val formats = DefaultFormats

    parse(value).extract[HeartbeatConfiguration]
  }

  def entryToNode(e:LDAPEntry) : Box[Node] = {
    if(e.isA(OC_RUDDER_NODE)||e.isA(OC_POLICY_SERVER_NODE)) {
      //OK, translate
      for {
        id   <- nodeDit.NODES.NODE.idFromDn(e.dn) ?~! s"Bad DN found for a Node: ${e.dn}"
        date <- e.getAsGTime(A_OBJECT_CREATION_DATE) ?~! s"Can not find mandatory attribute '${A_OBJECT_CREATION_DATE}' in entry"
        agentRunInterval = e(A_SERIALIZED_AGENT_RUN_INTERVAL).map(unserializeAgentRunInterval(_))
        heartbeatConf = e(A_SERIALIZED_HEARTBEAT_RUN_CONFIGURATION).map(unserializeNodeHeartbeatConfiguration(_))
        policyMode <- e(A_POLICY_MODE) match {
          case None => Full(None)
          case Some(value) => PolicyMode.parse(value).map {Some(_) }
        }
      } yield {
        Node(
            id
          , e(A_NAME).getOrElse("")
          , e(A_DESCRIPTION).getOrElse("")
          , e.getAsBoolean(A_IS_BROKEN).getOrElse(false)
          , e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
          , e.isA(OC_POLICY_SERVER_NODE)
          , date.dateTime
          , ReportingConfiguration(
                agentRunInterval
              , heartbeatConf
            )
          , e.valuesFor(A_NODE_PROPERTY).map(unserializeLdapNodeProperty(_)).toSeq
          , policyMode
        )
      }
    } else {
      Failure(s"The given entry is not of the expected ObjectClass ${OC_RUDDER_NODE} or ${OC_POLICY_SERVER_NODE}. Entry details: ${e}")
    }
  }
    //////////////////////////////    NodeInfo    //////////////////////////////

  /**
   * From a nodeEntry and an inventoryEntry, create a NodeInfo
   *
   * The only thing used in machineEntry is its object class.
   *
   */
  def convertEntriesToNodeInfos(nodeEntry: LDAPEntry, inventoryEntry: LDAPEntry, machineEntry: Option[LDAPEntry]) : Box[NodeInfo] = {
    //why not using InventoryMapper ? Some required things for node are not
    // wanted here ?
    for {
      node         <- entryToNode(nodeEntry)
      checkSameID  <- if(nodeEntry(A_NODE_UUID).isDefined && nodeEntry(A_NODE_UUID) ==  inventoryEntry(A_NODE_UUID)) Full("Ok")
                      else Failure("Mismatch id for the node %s and the inventory %s".format(nodeEntry(A_NODE_UUID), inventoryEntry(A_NODE_UUID)))

      nodeInfo     <- inventoryEntriesToNodeInfos(node, inventoryEntry, machineEntry)
    } yield {
      nodeInfo
    }
  }

  /**
   * Convert at the best you can node inventories to node information.
   * Some information will be false for sure - that's understood.
   */
  def convertEntriesToSpecialNodeInfos(inventoryEntry: LDAPEntry, machineEntry: Option[LDAPEntry]) : Box[NodeInfo] = {
    for {
      id   <- inventoryEntry(A_NODE_UUID) ?~! s"Missing node id information in Node entry: ${inventoryEntry}"
      node =  Node(
                  NodeId(id)
                , inventoryEntry(A_NAME).getOrElse("")
                , inventoryEntry(A_DESCRIPTION).getOrElse("")
                , inventoryEntry.getAsBoolean(A_IS_BROKEN).getOrElse(false)
                , inventoryEntry.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
                , false //we don't know anymore if it was a policy server
                , new DateTime(0) // we don't know anymore the acceptation date
                , ReportingConfiguration(None, None) //we don't know anymore agent run frequency
                , Seq() //we forgot node properties
                , None
              )
     nodeInfo <- inventoryEntriesToNodeInfos(node, inventoryEntry, machineEntry)
    } yield {
      nodeInfo
    }
  }

  private[this] def inventoryEntriesToNodeInfos(node: Node, inventoryEntry: LDAPEntry, machineEntry: Option[LDAPEntry]) : Box[NodeInfo] = {
    //why not using InventoryMapper ? Some required things for node are not
    // wanted here ?
    for {
      // Compute the parent policy Id
      policyServerId <- inventoryEntry.valuesFor(A_POLICY_SERVER_UUID).toList match {
                          case Nil => Failure(s"Missing policy server id for Node '${node.id.value}'. Entry details: ${inventoryEntry}")
                          case x :: Nil => Full(x)
                          case _ => Failure(s"Too many policy servers for a Node '${node.id.value}'. Entry details: ${inventoryEntry}")
                        }
      agentsName  <- sequence(inventoryEntry.valuesFor(A_AGENTS_NAME).toSeq) {AgentInfoSerialisation.parseCompatNonJson}
      osDetails   <- inventoryMapper.mapOsDetailsFromEntry(inventoryEntry)
      keyStatus   <- inventoryEntry(A_KEY_STATUS).map(KeyStatus(_)).getOrElse(Full(UndefinedKey))
      serverRoles =  inventoryEntry.valuesFor(A_SERVER_ROLE).map(ServerRole(_)).toSet
    } yield {
      val machineInfo = machineEntry.flatMap { e =>
        for {
          machineType <- inventoryMapper.machineTypeFromObjectClasses(e.valuesFor("objectClass"))
          machineUuid <- e(A_MACHINE_UUID).map(MachineUuid)
        } yield {
          MachineInfo(
              machineUuid
            , machineType
            , e(LDAPConstants.A_SERIAL_NUMBER)
            , e(LDAPConstants.A_MANUFACTURER).map(Manufacturer(_))
          )
        }
      }

      // fetch the inventory datetime of the object
      val dateTime = inventoryEntry.getAsGTime(A_INVENTORY_DATE) map(_.dateTime) getOrElse(DateTime.now)
      NodeInfo(
          node
        , inventoryEntry(A_HOSTNAME).getOrElse("")
        , machineInfo
        , osDetails
        , inventoryEntry.valuesFor(A_LIST_OF_IP).toList
        , dateTime
        , inventoryEntry(A_PKEYS).flatMap { s => s.trim match {
                                            case "" => None
                                            case x  => Some(PublicKey(x))
                                          } }
        , keyStatus
        , scala.collection.mutable.Seq() ++ agentsName
        , NodeId(policyServerId)
        , inventoryEntry(A_ROOT_USER).getOrElse("")
        , serverRoles
        , inventoryEntry(A_ARCH)
        , inventoryEntry(A_OS_RAM).map{ m  => MemorySize(m) }
      )
    }
  }

  /**
   * Build the ActiveTechniqueCategoryId from the given DN
   */
  def dn2ActiveTechniqueCategoryId(dn:DN) : ActiveTechniqueCategoryId = {
    rudderDit.ACTIVE_TECHNIQUES_LIB.getCategoryIdValue(dn) match {
      case Full(value) => ActiveTechniqueCategoryId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Active Technique Category ID. Error was: %s".format(dn,e.toString))
    }
  }

  def dn2ActiveTechniqueId(dn:DN) : ActiveTechniqueId = {
    rudderDit.ACTIVE_TECHNIQUES_LIB.getActiveTechniqueId(dn) match {
      case Full(value) => ActiveTechniqueId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Active Technique ID. Error was: %s".format(dn,e.toString))
    }
  }

  /**
   * Build the Group Category Id from the given DN
   */
  def dn2NodeGroupCategoryId(dn:DN) : NodeGroupCategoryId = {
    rudderDit.GROUP.getCategoryIdValue(dn) match {
      case Full(value) => NodeGroupCategoryId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Node Group Category ID. Error was: %s".format(dn,e.toString))
    }
  }

  /**
   * Build the Node Group Category Id from the given DN
   */
  def dn2NodeGroupId(dn:DN) : NodeGroupId = {
    rudderDit.GROUP.getGroupId(dn) match {
      case Full(value) => NodeGroupId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Node Group ID. Error was: %s".format(dn,e.toString))
    }
  }

  /**
   * Build the Rule Category Id from the given DN
   */
  def dn2RuleCategoryId(dn:DN) : RuleCategoryId = {
    rudderDit.RULECATEGORY.getCategoryIdValue(dn) match {
      case Full(value) => RuleCategoryId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Rule Category ID. Error was: %s".format(dn,e.toString))
    }
  }

  def dn2LDAPRuleID(dn:DN) : DirectiveId = {
    rudderDit.ACTIVE_TECHNIQUES_LIB.getLDAPRuleID(dn) match {
      case Full(value) => DirectiveId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Directive ID. Error was: %s".format(dn,e.toString))
    }
  }

  def dn2RuleId(dn:DN) : RuleId = {
    rudderDit.RULES.getRuleId(dn) match {
      case Full(value) => RuleId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Rule ID. Error was: %s".format(dn,e.toString))
    }
  }

  def nodeDn2OptNodeId(dn:DN) : Box[NodeId] = {
    val rdn = dn.getRDN
    if(!rdn.isMultiValued && rdn.hasAttribute(A_NODE_UUID)) {
      Full(NodeId(rdn.getAttributeValues()(0)))
    } else Failure("Bad RDN for a node, expecting attribute name '%s', got: %s".format(A_NODE_UUID,rdn))
  }

  //////////////////////////////    ActiveTechniqueCategory    //////////////////////////////

  /**
   * children and items are left empty
   */
  def entry2ActiveTechniqueCategory(e:LDAPEntry) : Box[ActiveTechniqueCategory] = {
    if(e.isA(OC_TECHNIQUE_CATEGORY)) {
      //OK, translate
      for {
        id <- e(A_TECHNIQUE_CATEGORY_UUID) ?~! "Missing required id (attribute name %s) in entry %s".format(A_TECHNIQUE_CATEGORY_UUID, e)
        name <- e(A_NAME) ?~! "Missing required name (attribute name %s) in entry %s".format(A_NAME, e)
        description = e(A_DESCRIPTION).getOrElse("")
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      } yield {
         ActiveTechniqueCategory(ActiveTechniqueCategoryId(id), name, description, Nil, Nil, isSystem)
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_TECHNIQUE_CATEGORY, e))
  }

  /**
   * children and items are ignored
   */
  def activeTechniqueCategory2ldap(category:ActiveTechniqueCategory, parentDN:DN) = {
    val entry = rudderDit.ACTIVE_TECHNIQUES_LIB.activeTechniqueCategoryModel(category.id.value, parentDN)
    entry +=! (A_NAME, category.name)
    entry +=! (A_DESCRIPTION, category.description)
    entry +=! (A_IS_SYSTEM, category.isSystem.toLDAPString)
    entry
  }

  //////////////////////////////    ActiveTechnique    //////////////////////////////

  //two utilities to serialize / deserialize Map[TechniqueVersion,DateTime]
  def unserializeAcceptations(value:String):Map[TechniqueVersion, DateTime] = {
    import net.liftweb.json.JsonParser._
    import net.liftweb.json.JsonAST.{JField,JString}

    parse(value) match {
      case JObject(fields) =>
        fields.collect { case JField(version, JString(date)) =>
          (TechniqueVersion(version) -> GeneralizedTime(date).dateTime)
        }.toMap
      case _ => Map()
    }
  }

  def serializeAcceptations(dates:Map[TechniqueVersion,DateTime]) : JObject = {
    import net.liftweb.json.JsonDSL._
    ( JObject(List()) /: dates) { case (js, (version, date)) =>
      js ~ (version.toString -> GeneralizedTime(date).toString)
    }
  }

  /**
   * Build a ActiveTechnique from and LDAPEntry.
   * children directives are left empty
   */
  def entry2ActiveTechnique(e:LDAPEntry) : Box[ActiveTechnique] = {
    if(e.isA(OC_ACTIVE_TECHNIQUE)) {
      //OK, translate
      for {
        id <- e(A_ACTIVE_TECHNIQUE_UUID) ?~! "Missing required id (attribute name %s) in entry %s".format(A_ACTIVE_TECHNIQUE_UUID, e)
        refTechniqueUuid <- e(A_TECHNIQUE_UUID).map(x => TechniqueName(x)) ?~! "Missing required name (attribute name %s) in entry %s".format(A_TECHNIQUE_UUID, e)
        isEnabled = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        acceptationDatetimes = e(A_ACCEPTATION_DATETIME).map(unserializeAcceptations(_)).getOrElse(Map())
      } yield {
         ActiveTechnique(ActiveTechniqueId(id), refTechniqueUuid, acceptationDatetimes, Nil, isEnabled, isSystem)
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_TECHNIQUE_CATEGORY, e))
  }

  def activeTechnique2Entry(activeTechnique:ActiveTechnique, parentDN:DN) : LDAPEntry = {
    val entry = rudderDit.ACTIVE_TECHNIQUES_LIB.activeTechniqueModel(
        activeTechnique.id.value,
        parentDN,
        activeTechnique.techniqueName,
        serializeAcceptations(activeTechnique.acceptationDatetimes),
        activeTechnique.isEnabled,
        activeTechnique.isSystem
    )
    entry
  }

   //////////////////////////////    NodeGroupCategory    //////////////////////////////

  /**
   * children and items are left empty
   */
  def entry2NodeGroupCategory(e:LDAPEntry) : Box[NodeGroupCategory] = {
    if(e.isA(OC_GROUP_CATEGORY)) {
      //OK, translate
      for {
        id <- e(A_GROUP_CATEGORY_UUID) ?~! "Missing required id (attribute name %s) in entry %s".format(A_GROUP_CATEGORY_UUID, e)
        name <- e(A_NAME) ?~! "Missing required name (attribute name %s) in entry %s".format(A_NAME, e)
        description = e(A_DESCRIPTION).getOrElse("")
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      } yield {
         NodeGroupCategory(NodeGroupCategoryId(id), name, description, Nil, Nil, isSystem)
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_GROUP_CATEGORY, e))
  }

  /**
   * children and items are ignored
   */
  def nodeGroupCategory2ldap(category:NodeGroupCategory, parentDN:DN) = {
    val entry = rudderDit.GROUP.groupCategoryModel(category.id.value, parentDN)
    entry +=! (A_NAME, category.name)
    entry +=! (A_DESCRIPTION, category.description)
    entry +=! (A_IS_SYSTEM, category.isSystem.toLDAPString)
    entry
  }

  ////////////////////////////////// Node Group //////////////////////////////////

   /**
   * Build a node group from and LDAPEntry.
   */
  def entry2NodeGroup(e:LDAPEntry) : Box[NodeGroup] = {
    if(e.isA(OC_RUDDER_NODE_GROUP)) {
      //OK, translate
      for {
        id <- e(A_NODE_GROUP_UUID) ?~! "Missing required id (attribute name %s) in entry %s".format(A_NODE_GROUP_UUID, e)
        name <- e(A_NAME) ?~! "Missing required name (attribute name %s) in entry %s".format(A_NAME, e)
        query = e(A_QUERY_NODE_GROUP)
        nodeIds =  e.valuesFor(A_NODE_UUID).map(x => NodeId(x))
        query <- e(A_QUERY_NODE_GROUP) match {
          case None => Full(None)
          case Some(q) => cmdbQueryParser(q) match {
            case Full(x) => Full(Some(x))
            case eb:EmptyBox =>
              val error = eb ?~! "Error when parsing query for node group persisted at DN '%s' (name: '%s'), that seems to be an inconsistency. You should modify that group".format(
                  e.dn, name
              )
              logger.error(error)
              Full(None)
          }
        }
        isDynamic = e.getAsBoolean(A_IS_DYNAMIC).getOrElse(false)
        isEnabled = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        description = e(A_DESCRIPTION).getOrElse("")
      } yield {
         NodeGroup(NodeGroupId(id), name, description, query, isDynamic, nodeIds, isEnabled, isSystem)
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_RUDDER_NODE_GROUP, e))
  }

  //////////////////////////////    Special Policy target info    //////////////////////////////

  def entry2RuleTargetInfo(e:LDAPEntry) : Box[RuleTargetInfo] = {
    for {
      target <- {
        if(e.isA(OC_RUDDER_NODE_GROUP)) {
          (e(A_NODE_GROUP_UUID).map(id => GroupTarget(NodeGroupId(id))) )?~! "Missing required id (attribute name %s) in entry %s".format(A_NODE_GROUP_UUID, e)
        } else if(e.isA(OC_SPECIAL_TARGET))
          for {
            targetString <- e(A_RULE_TARGET) ?~! "Missing required target id (attribute name %s) in entry %s".format(A_RULE_TARGET, e)
            target <- RuleTarget.unser(targetString) ?~! "Can not unserialize target, '%s' does not match any known target format".format(targetString)
          } yield {
            target
          } else Failure("The given entry is not of the expected ObjectClass '%s' or '%s'. Entry details: %s".format(OC_RUDDER_NODE_GROUP, OC_SPECIAL_TARGET, e))
      }
      name <-  e(A_NAME) ?~! "Missing required name (attribute name %s) in entry %s".format(A_NAME, e)
      description = e(A_DESCRIPTION).getOrElse("")
      isEnabled = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
      isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
    } yield {
      RuleTargetInfo(target, name , description , isEnabled , isSystem)
    }
  }

  //////////////////////////////    Directive    //////////////////////////////

  def entry2Directive(e:LDAPEntry) : Box[Directive] = {

    if(e.isA(OC_DIRECTIVE)) {
      //OK, translate
      for {
        id              <- e(A_DIRECTIVE_UUID) ?~! "Missing required attribute %s in entry %s".format(A_DIRECTIVE_UUID, e)
        s_version       <- e(A_TECHNIQUE_VERSION) ?~! "Missing required attribute %s in entry %s".format(A_TECHNIQUE_VERSION, e)
        policyMode      <- e(A_POLICY_MODE) match {
          case None => Full(None)
          case Some(value) => PolicyMode.parse(value).map {Some(_) }
        }
        version         <- tryo(TechniqueVersion(s_version))
        name             = e(A_NAME).getOrElse(id)
        params           = parsePolicyVariables(e.valuesFor(A_DIRECTIVE_VARIABLES).toSeq)
        shortDescription = e(A_DESCRIPTION).getOrElse("")
        longDescription  = e(A_LONG_DESCRIPTION).getOrElse("")
        priority         = e.getAsInt(A_PRIORITY).getOrElse(0)
        isEnabled        = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        isSystem         = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        tags             <- e(A_SERIALIZED_TAGS) match {
          case None => Full(Tags(Set()))
          case Some(tags) => CompleteJson.unserializeTags(tags) ?~! s"Invalid attribute value for tags ${A_SERIALIZED_TAGS}: ${tags}"
        }
      } yield {
        Directive(
            DirectiveId(id)
          , version
          , params
          , name
          , shortDescription
          , policyMode
          , longDescription
          , priority
          , isEnabled
          , isSystem
          , tags
        )
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_DIRECTIVE, e))
  }

  def userDirective2Entry(directive:Directive, parentDN:DN) : LDAPEntry = {
    val entry = rudderDit.ACTIVE_TECHNIQUES_LIB.directiveModel(
        directive.id.value,
        directive.techniqueVersion,
        parentDN
    )

    entry +=! (A_DIRECTIVE_VARIABLES, policyVariableToSeq(directive.parameters):_*)
    entry +=! (A_NAME, directive.name)
    entry +=! (A_DESCRIPTION, directive.shortDescription)
    entry +=! (A_LONG_DESCRIPTION, directive.longDescription.toString)
    entry +=! (A_PRIORITY, directive.priority.toString)
    entry +=! (A_IS_ENABLED, directive.isEnabled.toLDAPString)
    entry +=! (A_IS_SYSTEM, directive.isSystem.toLDAPString)
    directive.policyMode.foreach ( mode => entry +=! (A_POLICY_MODE, mode.name) )
    entry +=! (A_SERIALIZED_TAGS, JsonTagSerialisation.serializeTags(directive.tags))
    entry
  }

  //////////////////////////////    Rule Category    //////////////////////////////

  /**
   * children and items are left empty
   */
  def entry2RuleCategory(e:LDAPEntry) : Box[RuleCategory] = {
    if(e.isA(OC_RULE_CATEGORY)) {
      //OK, translate
      for {
        id <- e(A_RULE_CATEGORY_UUID) ?~! s"Missing required id (attribute name '${A_RULE_CATEGORY_UUID}) in entry ${e}"
        name <- e(A_NAME) ?~! s"Missing required name (attribute name '${A_NAME}) in entry ${e}"
        description = e(A_DESCRIPTION).getOrElse("")
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      } yield {
         RuleCategory(RuleCategoryId(id), name, description, Nil, isSystem)
      }
    } else Failure(s"The given entry is not of the expected ObjectClass '${OC_RULE_CATEGORY}'. Entry details: ${e}")
  }

  /**
   * children and items are ignored
   */
  def ruleCategory2ldap(category:RuleCategory, parentDN:DN) = {
    rudderDit.RULECATEGORY.ruleCategoryModel(category.id.value, parentDN, category.name,category.description,category.isSystem)
  }

  //////////////////////////////    Rule    //////////////////////////////
  def entry2OptTarget(optValue:Option[String]) : Box[Option[RuleTarget]] = {
    (for {
      targetValue <- Box(optValue)
      target <- RuleTarget.unser(targetValue) ?~! "Bad parameter for a rule target: %s".format(targetValue)
    } yield {
      target
    }) match {
      case Full(t) => Full(Some(t))
      case Empty => Full(None)
      case f:Failure => f
    }
  }

  def entry2Rule(e:LDAPEntry) : Box[Rule] = {

    if(e.isA(OC_RULE)) {
      for {
        id       <- e(A_RULE_UUID) ?~!
                    "Missing required attribute %s in entry %s".format(A_RULE_UUID, e)
        serial   <- e.getAsInt(A_SERIAL) ?~!
                    "Missing required attribute %s in entry %s".format(A_SERIAL, e)
        tags     <- e(A_SERIALIZED_TAGS) match {
          case None => Full(Tags(Set()))
          case Some(tags) => CompleteJson.unserializeTags(tags) ?~! s"Invalid attribute value for tags ${A_SERIALIZED_TAGS}: ${tags}"
        }
      } yield {
        val targets = for {
          target <- e.valuesFor(A_RULE_TARGET)
          optionRuleTarget <- entry2OptTarget(Some(target)) ?~!
            "Invalid attribute %s for entry %s.".format(target, A_RULE_TARGET)
          ruleTarget <- optionRuleTarget
        } yield {
          ruleTarget
        }
        val directiveIds = e.valuesFor(A_DIRECTIVE_UUID).map(x => DirectiveId(x))
        val name = e(A_NAME).getOrElse(id)
        val shortDescription = e(A_DESCRIPTION).getOrElse("")
        val longDescription = e(A_LONG_DESCRIPTION).getOrElse("")
        val isEnabled = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        val isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        val category = e(A_RULE_CATEGORY).map(RuleCategoryId(_)).getOrElse(rudderDit.RULECATEGORY.rootCategoryId)

        Rule(
            RuleId(id)
          , name
          , serial
          , category
          , targets
          , directiveIds
          , shortDescription
          , longDescription
          , isEnabled
          , isSystem
          , tags
        )
      }
    } else {
      Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s"
          .format(OC_RULE, e))
    }
  }

  /**
   * Map a rule to an LDAP Entry.
   * WARN: serial is NEVER mapped.
   */
  def rule2Entry(rule:Rule) : LDAPEntry = {
    val entry = rudderDit.RULES.ruleModel(
        rule.id.value
      , rule.name
      , rule.isEnabledStatus
      , rule.isSystem
      , rule.categoryId.value
    )

    entry +=! (A_RULE_TARGET, rule.targets.map( _.target).toSeq :_* )
    entry +=! (A_DIRECTIVE_UUID, rule.directiveIds.map( _.value).toSeq :_* )
    entry +=! (A_DESCRIPTION, rule.shortDescription)
    entry +=! (A_LONG_DESCRIPTION, rule.longDescription.toString)
    entry +=! (A_SERIALIZED_TAGS, JsonTagSerialisation.serializeTags(rule.tags))

    entry
  }

  //////////////////////////////    API Accounts    //////////////////////////////

  /**
   * Build an API Account from an entry
   */
  def entry2ApiAccount(e:LDAPEntry) : Box[ApiAccount] = {
    if(e.isA(OC_API_ACCOUNT)) {
      //OK, translate
      for {
        id <- e(A_API_UUID).map( ApiAccountId(_) ) ?~! s"Missing required id (attribute name ${A_API_UUID}) in entry ${e}"
        name <- e(A_NAME).map( ApiAccountName(_) ) ?~! s"Missing required name (attribute name ${A_NAME}) in entry ${e}"
        token <- e(A_API_TOKEN).map( ApiToken(_) ) ?~! s"Missing required token (attribute name ${A_API_TOKEN}) in entry ${e}"
        creationDatetime <- e.getAsGTime(A_CREATION_DATETIME) ?~! s"Missing required creation timestamp (attribute name ${A_CREATION_DATETIME}) in entry ${e}"
        tokenCreationDatetime <- e.getAsGTime(A_API_TOKEN_CREATION_DATETIME) ?~! s"Missing required token creation timestamp (attribute name ${A_API_TOKEN_CREATION_DATETIME}) in entry ${e}"
        isEnabled = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        description = e(A_DESCRIPTION).getOrElse("")
      } yield {
        ApiAccount(id, name, token, description, isEnabled, creationDatetime.dateTime, tokenCreationDatetime.dateTime)
      }
    } else Failure(s"The given entry is not of the expected ObjectClass '${OC_API_ACCOUNT}'. Entry details: ${e}")
  }

  def apiAccount2Entry(principal:ApiAccount) : LDAPEntry = {
    rudderDit.API_ACCOUNTS.API_ACCOUNT.apiAccountModel(principal)
  }

  //////////////////////////////    Parameters    //////////////////////////////

  def entry2Parameter(e:LDAPEntry) : Box[GlobalParameter] = {
    if(e.isA(OC_PARAMETER)) {
      //OK, translate
      for {
        name        <- e(A_PARAMETER_NAME) ?~! "Missing required attribute %s in entry %s".format(A_PARAMETER_NAME, e)
        value       = e(A_PARAMETER_VALUE).getOrElse("")
        description = e(A_DESCRIPTION).getOrElse("")
        overridable = e.getAsBoolean(A_PARAMETER_OVERRIDABLE).getOrElse(true)
      } yield {
        GlobalParameter(
            ParameterName(name)
          , value
          , description
          , overridable
        )
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_PARAMETER, e))
  }

  def parameter2Entry(parameter: GlobalParameter) : LDAPEntry = {
    val entry = rudderDit.PARAMETERS.parameterModel(
        parameter.name
    )
    entry +=! (A_PARAMETER_VALUE, parameter.value)
    entry +=! (A_PARAMETER_OVERRIDABLE, parameter.overridable.toLDAPString)
    entry +=! (A_DESCRIPTION, parameter.description)
    entry
  }

  //////////////////////////////    Rudder Config    //////////////////////////////

  def entry2RudderConfig(e:LDAPEntry) : Box[RudderWebProperty] = {
    if(e.isA(OC_PROPERTY)) {
      //OK, translate
      for {
        name        <-e(A_PROPERTY_NAME) ?~! s"Missing required attribute ${A_PROPERTY_NAME} in entry ${e}"
        value       = e(A_PROPERTY_VALUE).getOrElse("")
        description = e(A_DESCRIPTION).getOrElse("")
      } yield {
        RudderWebProperty(
            RudderWebPropertyName(name)
          , value
          , description
        )
      }
    } else Failure(s"The given entry is not of the expected ObjectClass '${OC_PROPERTY}'. Entry details: ${e}")
  }

  def rudderConfig2Entry(property: RudderWebProperty) : LDAPEntry = {
    val entry = rudderDit.APPCONFIG.propertyModel(
        property.name
    )
    entry +=! (A_PROPERTY_VALUE, property.value)
    entry +=! (A_DESCRIPTION, property.description)
    entry
  }

}
