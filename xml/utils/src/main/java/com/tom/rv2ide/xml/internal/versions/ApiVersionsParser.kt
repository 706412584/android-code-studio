/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.xml.internal.versions

import com.tom.rv2ide.xml.versions.ApiVersion
import java.io.InputStream
import jaxp.xml.namespace.QName
import jaxp.xml.stream.XMLInputFactory
import jaxp.xml.stream.events.Attribute
import jaxp.xml.stream.events.EndElement
import jaxp.xml.stream.events.StartElement
import org.slf4j.LoggerFactory

/**
 * Parser for parsing `api-versions.xml` file from the Android SDK and building [ApiVersion] models.
 *
 * @author Akash Yadav
 */
open class ApiVersionsParser {

  // A cache to store the ApiVersion instances with different values
  // For example, if two classes have the same value for `since`,
  // 'removed' and 'deprecated', then the same ApiVersion instance
  // will be used for the two classes. To prevent value corruption, ApiVersion instances are
  // immutable.
  //
  // Note: the key is the parsed [ApiLevels] rather than a packed int. The packed-int key used
  // previously could not represent the `major.minor` API levels (e.g. 36.1, 37.2) that recent
  // SDKs write, so distinct levels would have collided.
  private val apiInfosCache = HashMap<ApiLevels, ApiVersion>()

  private var apiVersion: Int? = null
  private var currentClass: String? = null

  companion object {
    internal const val TAG_API = "api"
    internal const val TAG_CLASS = "class"
    internal const val TAG_FIELD = "field"
    internal const val TAG_METHOD = "method"
    internal const val ATTR_NAME = "name"
    internal const val ATTR_DEPR = "deprecated"
    internal const val ATTR_REM = "removed"
    internal const val ATTR_SIN = "since"
    private val log = LoggerFactory.getLogger(ApiVersionsParser::class.java)

    private val NO_LEVEL = ApiLevel(ApiVersion.NONE, ApiVersion.NONE)
    private val MAJOR_INCEPTION = ApiLevel(1, ApiVersion.NONE)

    /**
     * Parses an API level from an `api-versions.xml` attribute value.
     *
     * Recent SDKs write these as `major.minor` (for example `36.1` or `37.2`) instead of a plain
     * integer. Parsing such a value with `toInt()` throws [NumberFormatException], which
     * propagates out of [parse] and aborts project setup. The minor component is preserved so
     * callers can tell `37.0` and `37.2` apart.
     *
     * @param value The raw attribute value, either `major` or `major.minor`.
     */
    private fun parseApiLevel(value: String): ApiLevel {
      val dot = value.indexOf('.')
      if (dot < 0) {
        return ApiLevel(major = value.toInt(), minor = ApiVersion.NONE)
      }

      val major = value.substring(0, dot).toInt()
      // Treat an unparsable fractional part as zero rather than failing the whole parse.
      val minor = value.substring(dot + 1).toIntOrNull() ?: ApiVersion.NONE
      return ApiLevel(major = major, minor = minor)
    }
  }

  fun parse(apiVersionsXml: InputStream) {
    if (apiVersionsXml.available() <= 0) {
      log.warn("api-versions.xml InputStream is empty")
      return
    }

    val inputFactory = XMLInputFactory.newInstance()
    val reader = inputFactory.createXMLEventReader(apiVersionsXml)

    while (reader.hasNext()) {
      val event = reader.nextEvent()
      if (event.isStartElement) {
        consumeStartElement(event.asStartElement())
        continue
      }

      if (event.isEndElement) {
        consumeEndElement(event.asEndElement())
      }
    }

    onFinishParse()
  }

  /** Called when the parser is done parsing the `api-versions.xml` file. */
  protected open fun onFinishParse() {}

  private fun consumeStartElement(event: StartElement) {
    when (event.name.localPart) {
      // Only the major component matters here; parse defensively so a future `version="4.1"`
      // cannot abort project setup the way `since="36.1"` used to.
      TAG_API ->
          apiVersion =
              event.getAttributeByName(QName("version")).value.substringBefore('.').toInt()
      TAG_CLASS -> consumeClass(event)
      TAG_FIELD -> consumeMember(event, TAG_FIELD)
      TAG_METHOD -> consumeMember(event, TAG_METHOD)
    }
  }

  private fun consumeClass(event: StartElement) {
    checkNotNull(apiVersion) { "<class> element must be inside <api> element" }

    check(currentClass == null) { "<class> elements cannot be nested" }

    val (name, versions) = event.parseAttrs()
    check(!isDuplicateClass(name)) { "Duplicate class entry: $name" }

    val apiInfo = apiInfosCache.getOrPut(versions) { createApiInfo(versions) }

    consumeClassVersionInfo(name, apiInfo)
    this.currentClass = name
  }

  /** Check if the given class name is a duplicate. */
  protected open fun isDuplicateClass(name: String): Boolean {
    return false
  }

  /**
   * Consume [ApiVersion] info of a class.
   *
   * @param name The fully qualified name of the class in its internal form.
   * @param apiVersion The [ApiVersion] info of the class.
   */
  protected open fun consumeClassVersionInfo(name: String, apiVersion: ApiVersion) {}

  private fun consumeMember(event: StartElement, memberType: String) {
    val (name, versions) = event.parseAttrs()
    val currentClass =
        checkNotNull(currentClass) { "<${memberType}> element must be inside <class> element" }
    check(!isDuplicateMember(currentClass, name)) {
      "Duplicate $memberType entry in class $currentClass: $name"
    }
    consumeMemberVersionInfo(currentClass, name, memberType, createApiInfo(versions))
  }

  /** Check if the given member of the given class is a duplicate. */
  protected open fun isDuplicateMember(className: String, memberName: String): Boolean {
    return false
  }

  /**
   * Consume [ApiVersion] info for a member of a class.
   *
   * @param className The name of the class which declares this member.
   * @param member The identifier of the member. This is the name of the field and the method
   *   signature for methods.
   * @param memberType The type of member. This may be "field" or "method".
   * @param apiVersion The [ApiVersion] info.
   */
  protected open fun consumeMemberVersionInfo(
      className: String,
      member: String,
      memberType: String,
      apiVersion: ApiVersion,
  ) {}

  private fun consumeEndElement(element: EndElement) {
    when (element.name.localPart) {
      TAG_API -> apiVersion = null
      TAG_CLASS -> currentClass = null
    }
  }

  private fun StartElement.parseAttrs(): Pair<String, ApiLevels> {
    var name: String? = null
    var since = MAJOR_INCEPTION
    var deprecated = NO_LEVEL
    var removed = NO_LEVEL

    attributes.forEach { attribute ->
      attribute as Attribute

      when (attribute.name.localPart) {
        ATTR_NAME -> name = attribute.value
        ATTR_SIN -> since = parseApiLevel(attribute.value)
        ATTR_DEPR -> deprecated = parseApiLevel(attribute.value)
        ATTR_REM -> removed = parseApiLevel(attribute.value)
      }
    }

    checkNotNull(name) { "Missing name attribute" }

    // Android API versions would not exceed 255, right?
    check(since.major in 1..255 && deprecated.major in 0..255 && removed.major in 0..255) {
      "Invalid version: $since, $deprecated, $removed"
    }

    return name!! to ApiLevels(since, deprecated, removed)
  }

  private fun createApiInfo(levels: ApiLevels): ApiVersion {
    return ApiVersion(
        since = levels.since.major,
        deprecatedIn = levels.deprecated.major,
        removedIn = levels.removed.major,
        sinceMinor = levels.since.minor,
        deprecatedMinor = levels.deprecated.minor,
        removedMinor = levels.removed.minor,
    )
  }

  /**
   * A single API level, split into its major and minor components.
   *
   * The minor component is zero when the SDK file wrote a plain integer, or when the minor
   * component of a `major.minor` value is zero.
   */
  private data class ApiLevel(val major: Int, val minor: Int)

  /** The three API levels of one `api-versions.xml` element. */
  private data class ApiLevels(
      val since: ApiLevel,
      val deprecated: ApiLevel,
      val removed: ApiLevel,
  )
}
