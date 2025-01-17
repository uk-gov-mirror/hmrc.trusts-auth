/*
 * Copyright 2021 HM Revenue & Customs
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

package implicits

import com.typesafe.config.ConfigList
import implicits.Config.TypedConfigList
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.GuiceApplicationBuilder

class ConfigSpec extends PlaySpec with GuiceOneAppPerSuite {

  "Config" when {

    "TypedConfigList" must {
      "map ConfigList to List[T]" when {
        "strings" in {

          val path = "strings"
          val list: List[String] = List("string1", "string2", "string3")

          val app = new GuiceApplicationBuilder()
            .configure((path, list))
            .build()

          val configList = app.configuration.get[ConfigList](path)

          configList.toList[String] mustEqual list
        }
      }
    }
  }
}
