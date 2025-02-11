/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.rest

import com.normation.inventory.domain.NodeId
import com.normation.rudder.MockDirectives
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockRules
import com.normation.rudder.MockTechniques
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies._
import com.normation.utils.StringUuidGeneratorImpl
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.specs2.specification.core.Fragments
import com.normation.rudder.rest.JsonQueryObjects._
import com.normation.rudder.rest.JsonResponseObjects._
import com.normation.rudder.rest.JsonResponseObjects.JRRuleTarget._
import com.normation.rudder.rest.implicits._

@RunWith(classOf[JUnitRunner])
class RestDataExtractorTest extends Specification {

  val mockGitRepo = new MockGitConfigRepo("")
  val mockTechniques = MockTechniques(mockGitRepo)
  val mockDirectives = new MockDirectives(mockTechniques)
  val mockRules = new MockRules()
  val extract = new RestExtractorService(
      mockRules.ruleRepo
    , mockDirectives.directiveRepo
    , null
    , mockTechniques.techniqueRepo
    , null
    , null
    , null
    , new StringUuidGeneratorImpl()
    , null
  )
  val jparse  = net.liftweb.json.parse _

  "extract RuleTarget" >> {
    val tests = List(
      ("""group:3d5d1d6c-4ba5-4ffc-b5a4-12ce02336f52"""
      , JRRuleTarget(GroupTarget(NodeGroupId("3d5d1d6c-4ba5-4ffc-b5a4-12ce02336f52")))
      )
    , ("""group:hasPolicyServer-root"""
      , JRRuleTarget(GroupTarget(NodeGroupId("hasPolicyServer-root")))
      )
    , ("""policyServer:root"""
      , JRRuleTarget(PolicyServerTarget(NodeId("root")))
      )
    , ("""special:all"""
      , JRRuleTarget(AllTarget)
      )
    , ("""{"include":{"or":["special:all"]},"exclude":{"or":["group:all-nodes-with-dsc-agent"]}}"""
      , JRRuleTarget(TargetExclusion(TargetUnion(Set(AllTarget)), TargetUnion(Set(GroupTarget(NodeGroupId("all-nodes-with-dsc-agent"))))))
      )
    , ("""{"include":{"or":[]},"exclude":{"or":[]}}"""
      , JRRuleTarget(TargetExclusion(TargetUnion(Set()), TargetUnion(Set())))
      )
    , ("""{"or":["special:all"]}"""
      , JRRuleTarget(TargetUnion(Set(AllTarget)))
      )
    )

    Fragments.foreach(tests) { case (json, expected) =>
      (extractRuleTargetJson(json) must beEqualTo(Right(expected)))
    }
  }

  "extract JsonRule" >> {

    val tests = List(
      ("""{
            "source": "b9f6d98a-28bc-4d80-90f7-d2f14269e215",
            "id": "0c1713ae-cb9d-4f7b-abda-ca38c5d643ea",
            "displayName": "Security policy",
            "shortDescription": "Baseline applying CIS guidelines",
            "longDescription": "This rules should be applied to all Linux nodes required basic hardening",
            "category": "38e0c6ea-917f-47b8-82e0-e6a1d3dd62ca",
            "directives": [
              "16617aa8-1f02-4e4a-87b6-d0bcdfb4019f"
            ],
            "targets": [
              "special:all"
            ],
            "enabled": true,
            "tags": [
              {
                "customer": "MyCompany"
              }
            ]
         }"""
      , JQRule(
            Some("0c1713ae-cb9d-4f7b-abda-ca38c5d643ea")
          , Some("Security policy")
          , Some("38e0c6ea-917f-47b8-82e0-e6a1d3dd62ca")
          , Some("Baseline applying CIS guidelines")
          , Some("This rules should be applied to all Linux nodes required basic hardening")
          , Some(Set(DirectiveId("16617aa8-1f02-4e4a-87b6-d0bcdfb4019f")))
          , Some(Set(JRRuleTargetString(AllTarget)))
          , Some(true)
          , Some(Tags(Set(Tag(TagName("customer"), TagValue("MyCompany")))))
          , Some("b9f6d98a-28bc-4d80-90f7-d2f14269e215")
        )
      ),
      ("""{
            "source": "b9f6d98a-28bc-4d80-90f7-d2f14269e215"
         }"""
      , JQRule(source = Some("b9f6d98a-28bc-4d80-90f7-d2f14269e215"))
      ),
      ("""{
            "category": "38e0c6ea-917f-47b8-82e0-e6a1d3dd62ca"
         }"""
      , JQRule(category = Some("38e0c6ea-917f-47b8-82e0-e6a1d3dd62ca"))
      ),
      ("""{
            "tags": []
         }"""
      , JQRule(tags = Some(Tags(Set())))
      )
    )

    Fragments.foreach(tests) { case (json, expected) =>
      (ruleDecoder.decodeJson(json)) must beEqualTo(Right(expected))
    }
  }
}
