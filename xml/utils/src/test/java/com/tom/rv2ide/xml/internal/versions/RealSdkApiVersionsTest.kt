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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Parses real `api-versions.xml` data.
 *
 * The primary test runs against a checked-in excerpt of the real SDK file
 * (`src/test/resources/api-versions-sample.xml`), so the `major.minor` regression is caught on
 * any machine. A second test additionally parses whatever Android SDK platforms are installed
 * locally; it is skipped when no SDK is present.
 */
class RealSdkApiVersionsTest {

  private class RecordingParser : ApiVersionsParser() {
    var classCount = 0
    var memberCount = 0
    val classes = LinkedHashMap<String, ApiVersion>()
    val members = LinkedHashMap<String, ApiVersion>()

    override fun consumeClassVersionInfo(name: String, apiVersion: ApiVersion) {
      classCount++
      classes[name] = apiVersion
    }

    override fun consumeMemberVersionInfo(
        className: String,
        member: String,
        memberType: String,
        apiVersion: ApiVersion,
    ) {
      memberCount++
      members["$className#$member"] = apiVersion
    }
  }

  private fun parseFixture(): RecordingParser {
    val parser = RecordingParser()
    val stream =
        javaClass.classLoader?.getResourceAsStream("api-versions-sample.xml")
            ?: error("api-versions-sample.xml fixture is missing from the test resources")
    stream.buffered().use { parser.parse(it) }
    return parser
  }

  // --------------------------------------------------------------- checked-in fixture

  @Test
  fun `parses the checked-in fixture with decimal API levels`() {
    val parser = parseFixture()

    assertTrue("Fixture should contain classes", parser.classCount > 0)
    assertTrue("Fixture should contain members", parser.memberCount > 0)
  }

  @Test
  fun `keeps the minor component of every decimal value in the fixture`() {
    val parser = parseFixture()
    val permission = "android/Manifest\$permission"

    // Values taken verbatim from the real android-37.2 file.
    assertEquals(
        ApiVersion(since = 37, sinceMinor = 0),
        parser.members["$permission#ACCESS_BIOMETRIC_SENSOR_STRENGTHS"],
    )
    assertEquals(
        ApiVersion(since = 37, sinceMinor = 1),
        parser.members["$permission#ACCESS_HID"],
    )
    assertEquals(
        ApiVersion(since = 36, sinceMinor = 1),
        parser.members["$permission#ACCESS_LAUNCHER_DATA"],
    )
    assertEquals(
        ApiVersion(since = 37, sinceMinor = 2),
        parser.members["$permission#BIND_CONTENT_RESTRICTION_SERVICE"],
    )
  }

  @Test
  fun `distinguishes 37_0 from 37_2 in the fixture`() {
    val parser = parseFixture()
    val permission = "android/Manifest\$permission"

    val zero = parser.members["$permission#ACCESS_LOCAL_NETWORK"]
    val two = parser.members["$permission#BIND_CONTENT_SAFETY_SERVICE"]

    assertNotNull(zero)
    assertNotNull(two)
    assertTrue("37.0 and 37.2 must not be conflated", zero != two)
    assertEquals(0, zero!!.sinceMinor)
    assertEquals(2, two!!.sinceMinor)
  }

  @Test
  fun `parses integer values alongside decimal ones in the fixture`() {
    val parser = parseFixture()

    // An integer `since` in the same document must still parse as before.
    val activity = parser.classes["android/app/Activity"]
    assertNotNull(activity)
    assertEquals(1, activity!!.since)
    assertEquals(ApiVersion.NONE, activity.sinceMinor)

    // Decimal deprecated/removed on a field whose `since` is a plain integer.
    val legacy = parser.members["android/Manifest\$permission#LEGACY_INTEGER_FIELD"]
    assertNotNull(legacy)
    assertEquals(34, legacy!!.since)
    assertEquals(36, legacy.deprecatedIn)
    assertEquals(1, legacy.deprecatedMinor)
    assertEquals(37, legacy.removedIn)
    assertEquals(2, legacy.removedMinor)
  }

  // --------------------------------------------------------------- optional local SDK

  /** Locates platform directories under the SDK root, if the SDK is present. */
  private fun platformDirs(): List<File> {
    val sdkDir =
        System.getenv("ANDROID_HOME")?.let(::File)
            ?: System.getenv("ANDROID_SDK_ROOT")?.let(::File)
            ?: return emptyList()
    val platforms = File(sdkDir, "platforms")
    if (!platforms.isDirectory) {
      return emptyList()
    }
    return platforms.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
  }

  @Test
  fun `parses every installed platform's api-versions file`() {
    val platforms = platformDirs().filter { File(it, "data/api-versions.xml").isFile }
    assumeTrue("No Android SDK platform found; skipping", platforms.isNotEmpty())

    for (platform in platforms) {
      val parser = RecordingParser()
      File(platform, "data/api-versions.xml").inputStream().buffered().use { parser.parse(it) }

      assertTrue("${platform.name}: no classes parsed", parser.classCount > 0)
      assertTrue("${platform.name}: no members parsed", parser.memberCount > 0)

      println("${platform.name}: ${parser.classCount} classes, ${parser.memberCount} members")
    }
  }
}
