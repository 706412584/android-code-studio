/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.xml.internal.versions

import com.tom.rv2ide.xml.versions.ApiVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ApiVersionsParser].
 *
 * The inputs are synthetic `api-versions.xml` documents modelled on the real SDK files, including
 * the `major.minor` attribute values (`since="36.1"`, `since="37.2"`) that recent SDKs use. Parsing
 * those with `toInt()` used to throw and abort project setup.
 */
class ApiVersionsParserTest {

  /** Collects the parsed values so assertions can be made on them. */
  private class RecordingParser : ApiVersionsParser() {
    val classes = LinkedHashMap<String, ApiVersion>()
    val members = LinkedHashMap<String, ApiVersion>()

    override fun consumeClassVersionInfo(name: String, apiVersion: ApiVersion) {
      classes[name] = apiVersion
    }

    override fun consumeMemberVersionInfo(
        className: String,
        member: String,
        memberType: String,
        apiVersion: ApiVersion,
    ) {
      members["$className#$member"] = apiVersion
    }
  }

  private fun parse(xml: String): RecordingParser {
    val parser = RecordingParser()
    parser.parse(xml.byteInputStream(Charsets.UTF_8))
    return parser
  }

  private fun wrap(body: String) =
      """<?xml version="1.0" encoding="utf-8"?>
         |<api version="4">
         |$body
         |</api>
      """.trimMargin()

  // ------------------------------------------------------------- integer values (legacy format)

  @Test
  fun `parses integer since values`() {
    val parser =
        parse(
            wrap(
                """  <class name="android/app/Activity" since="1">
                   |    <method name="onCreate(Landroid/os/Bundle;)V" since="5"/>
                   |  </class>""".trimMargin()))

    val cls = parser.classes["android/app/Activity"]
    assertNotNull(cls)
    assertEquals(5, parser.members["android/app/Activity#onCreate(Landroid/os/Bundle;)V"]?.since)
  }

  // ------------------------------------------------------------- decimal values (new SDK format)

  @Test
  fun `parses decimal since value without throwing`() {
    val parser =
        parse(
            wrap(
                """  <class name="android/app/Foo" since="36.1"/>""".trimMargin()))

    val cls = parser.classes["android/app/Foo"]
    assertNotNull("Class with decimal since should be parsed", cls)
    assertEquals(36, cls!!.since)
    assertEquals(1, cls.sinceMinor)
  }

  @Test
  fun `distinguishes minor versions of the same major`() {
    val parser =
        parse(
            wrap(
                """  <class name="android/app/Zero" since="37.0"/>
                   |  <class name="android/app/One" since="37.1"/>
                   |  <class name="android/app/Two" since="37.2"/>"""
                    .trimMargin()))

    assertEquals(0, parser.classes["android/app/Zero"]!!.sinceMinor)
    assertEquals(1, parser.classes["android/app/One"]!!.sinceMinor)
    assertEquals(2, parser.classes["android/app/Two"]!!.sinceMinor)
    // All three share the same major component but must remain distinct values.
    assertTrue(
        "37.0 and 37.2 must not be conflated",
        parser.classes["android/app/Zero"] != parser.classes["android/app/Two"])
  }

  @Test
  fun `parses decimal deprecated and removed values`() {
    val parser =
        parse(
            wrap(
                """  <class name="android/app/Legacy" since="1">
                   |    <field name="LEGACY" since="1" deprecated="36.1" removed="37.2"/>
                   |  </class>""".trimMargin()))

    val field = parser.members["android/app/Legacy#LEGACY"]
    assertNotNull(field)
    assertEquals(36, field!!.deprecatedIn)
    assertEquals(1, field.deprecatedMinor)
    assertEquals(37, field.removedIn)
    assertEquals(2, field.removedMinor)
  }

  @Test
  fun `parses the full range of minor values seen in real SDKs`() {
    // Values observed in android-36.1 and android-37.2 api-versions.xml files.
    val values = listOf("36.1", "37.0", "37.1", "37.2")
    val body = values.mapIndexed { i, v -> """  <class name="android/app/C$i" since="$v"/>""" }
    val parser = parse(wrap(body.joinToString("\n")))

    assertEquals(36, parser.classes["android/app/C0"]!!.since)
    assertEquals(1, parser.classes["android/app/C0"]!!.sinceMinor)
    assertEquals(37, parser.classes["android/app/C3"]!!.since)
    assertEquals(2, parser.classes["android/app/C3"]!!.sinceMinor)
  }

  @Test
  fun `repeated identical levels reuse one ApiVersion instance`() {
    val parser =
        parse(
            wrap(
                """  <class name="android/app/A" since="36.1"/>
                   |  <class name="android/app/B" since="36.1"/>"""
                    .trimMargin()))

    // The cache exists to avoid allocating duplicates; equal levels must map to one instance.
    assertTrue(
        "Equal levels should be cached",
        parser.classes["android/app/A"] === parser.classes["android/app/B"])
  }

  @Test
  fun `distinct minor versions are not conflated by the cache`() {
    val parser =
        parse(
            wrap(
                """  <class name="android/app/A" since="36.1"/>
                   |  <class name="android/app/B" since="36.2"/>"""
                    .trimMargin()))

    assertTrue(
        "36.1 and 36.2 must not share a cache entry",
        parser.classes["android/app/A"] !== parser.classes["android/app/B"])
  }

  // ------------------------------------------------------------- robustness

  @Test
  fun `unparsable minor component is treated as zero`() {
    val parser =
        parse(
            wrap(
                """  <class name="android/app/Weird" since="36.x"/>""".trimMargin()))

    val cls = parser.classes["android/app/Weird"]
    assertNotNull(cls)
    assertEquals(36, cls!!.since)
    assertEquals(ApiVersion.NONE, cls.sinceMinor)
  }

  @Test
  fun `empty input is ignored`() {
    val parser = RecordingParser()
    parser.parse(ByteArray(0).inputStream())
    assertTrue(parser.classes.isEmpty())
  }

  @Test
  fun `since inception is reported as such`() {
    val parser =
        parse(
            wrap(
                """  <class name="android/app/Base" since="1"/>""".trimMargin()))

    assertTrue(parser.classes["android/app/Base"]!!.isSinceInception())
  }

  // ------------------------------------------------------------- display formatting

  @Test
  fun `formats a minor level with a dot`() {
    val parser =
        parse(
            wrap(
                """  <class name="android/app/One" since="36.1"/>
                   |  <class name="android/app/Two" since="37.2"/>"""
                    .trimMargin()))

    assertEquals("36.1", parser.classes["android/app/One"]!!.formatSince())
    assertEquals("37.2", parser.classes["android/app/Two"]!!.formatSince())
  }

  @Test
  fun `formats a zero minor level without a dot`() {
    val parser =
        parse(
            wrap(
                """  <class name="android/app/Zero" since="37.0"/>""".trimMargin()))

    // 37.0 must render as "37", not "37.0".
    assertEquals("37", parser.classes["android/app/Zero"]!!.formatSince())
  }

  @Test
  fun `formats plain integer levels as before`() {
    val parser =
        parse(
            wrap(
                """  <class name="android/app/Legacy" since="34"/>""".trimMargin()))

    assertEquals("34", parser.classes["android/app/Legacy"]!!.formatSince())
  }

  @Test
  fun `formats deprecated and removed levels`() {
    val parser =
        parse(
            wrap(
                """  <class name="android/app/Legacy" since="1">
                   |    <field name="F" since="34" deprecated="36.1" removed="37.2"/>
                   |  </class>""".trimMargin()))

    val field = parser.members["android/app/Legacy#F"]!!
    assertEquals("34", field.formatSince())
    assertEquals("36.1", field.formatDeprecatedIn())
    assertEquals("37.2", field.formatRemovedIn())
  }

  @Test
  fun `formats a default ApiVersion`() {
    assertEquals("1", ApiVersion(1).formatSince())
    assertEquals("0", ApiVersion(ApiVersion.NONE).formatDeprecatedIn())
  }
}
