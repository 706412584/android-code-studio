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

package com.tom.rv2ide.git

import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the branch listing / switching logic in [GitManager].
 *
 * The regression being locked down: a freshly cloned repository only has a local branch for the
 * remote's default branch. Listing local branches alone therefore made such a repository look like
 * it had exactly one branch, and checking out any other branch failed.
 *
 * Each test builds a real repository pair (origin + clone) on disk with JGit, so the behaviour
 * under test is the real `refs/heads` vs `refs/remotes` layout rather than a mock.
 */
class GitManagerBranchTest {

  private lateinit var root: File
  private lateinit var originDir: File
  private lateinit var cloneDir: File

  private val author = PersonIdent("Tester", "tester@example.com")

  @Before
  fun setUp() {
    root = Files.createTempDirectory("gitmanager-branch").toFile()
    originDir = File(root, "origin")
    cloneDir = File(root, "clone")

    // Build an "origin" repository with three branches: main, feature, release.
    Git.init().setDirectory(originDir).setInitialBranch("main").call().use { origin ->
      commitFile(origin, "a.txt", "a", "initial")
      for (branch in listOf("feature", "release")) {
        origin.branchCreate().setName(branch).call()
      }
    }

    // Clone it. The clone gets a local branch for `main` only; `feature` and `release`
    // exist solely as remote-tracking refs.
    Git.cloneRepository()
        .setURI(originDir.toURI().toString())
        .setDirectory(cloneDir)
        .call()
        .close()
  }

  @After
  fun tearDown() {
    root.deleteRecursively()
  }

  private fun commitFile(git: Git, name: String, content: String, message: String) {
    val file = File(git.repository.workTree, name)
    file.writeText(content)
    git.add().addFilepattern(name).call()
    git.commit().setMessage(message).setAuthor(author).setCommitter(author).call()
  }

  private fun managerForClone(): GitManager {
    val manager = GitManager(cloneDir.absolutePath)
    assertTrue("clone should open", manager.openRepository())
    return manager
  }

  // ------------------------------------------------------- the regression

  @Test
  fun `getAllBranches sees remote branches after a clone`() {
    val branches = managerForClone().getAllBranches()

    // Before the fix this returned only ["main"].
    assertTrue("main should be listed", branches.contains("main"))
    assertTrue("feature should be listed from its remote ref", branches.contains("feature"))
    assertTrue("release should be listed from its remote ref", branches.contains("release"))
  }

  @Test
  fun `getAllBranches does not list the symbolic remote HEAD`() {
    val branches = managerForClone().getAllBranches()

    // `refs/remotes/origin/HEAD` must not surface as a branch named "HEAD".
    assertFalse("HEAD must not appear as a branch", branches.contains("HEAD"))
    assertFalse("origin/HEAD must not appear as a branch", branches.contains("origin/HEAD"))
  }

  @Test
  fun `getAllBranches is sorted and has no duplicates`() {
    val branches = managerForClone().getAllBranches()

    assertEquals(branches.sorted(), branches)
    assertEquals(branches.distinct(), branches)
  }

  @Test
  fun `getLocalBranches sees only the checked out branch after a clone`() {
    val local = managerForClone().getLocalBranches()

    // The clone has a local branch for the default branch only.
    assertTrue("main should be a local branch", local.contains("main"))
    assertFalse("feature is remote-only in a fresh clone", local.contains("feature"))
    assertFalse("release is remote-only in a fresh clone", local.contains("release"))
  }

  // ------------------------------------------------------- checkout

  @Test
  fun `checkoutBranch creates a local tracking branch for a remote-only branch`() {
    val manager = managerForClone()

    assertTrue("checkout of a remote-only branch should succeed", manager.checkoutBranch("feature"))

    // The branch is now local, and it is the current one.
    assertTrue(manager.getLocalBranches().contains("feature"))
    assertEquals("feature", manager.getCurrentBranch())

    // And the local branch tracks origin/feature. JGit stores the upstream as
    // remote = origin plus merge = the branch name *within that remote*.
    Git.open(cloneDir).use { git ->
      val config = git.repository.config
      assertEquals("origin", config.getString("branch", "feature", "remote"))
      assertEquals(
          "refs/heads/feature",
          config.getString("branch", "feature", "merge"),
      )
    }
  }

  @Test
  fun `checkoutBranch switches to an existing local branch`() {
    val manager = managerForClone()
    assertTrue(manager.checkoutBranch("feature"))
    assertTrue(manager.checkoutBranch("main"))
    assertEquals("main", manager.getCurrentBranch())
  }

  @Test
  fun `checkoutBranch fails for a branch that does not exist anywhere`() {
    assertFalse(managerForClone().checkoutBranch("no-such-branch"))
  }

  // ------------------------------------------------------- delete semantics

  @Test
  fun `deleteBranch removes a local branch`() {
    val manager = managerForClone()
    assertTrue(manager.checkoutBranch("feature"))
    assertTrue(manager.checkoutBranch("main"))

    assertTrue("deleting the local feature branch should succeed", manager.deleteBranch("feature"))
    assertFalse(manager.getLocalBranches().contains("feature"))
  }

  @Test
  fun `deleteBranch does not remove a remote-only branch`() {
    val manager = managerForClone()

    // `release` has no local branch. Deleting must not report success — this is why
    // callers check `getLocalBranches()` rather than `getAllBranches()` before deleting.
    assertFalse("remote-only branch must not be deletable", manager.deleteBranch("release"))

    // The remote-tracking ref is untouched and still listed.
    assertTrue(manager.getAllBranches().contains("release"))
    assertFalse(manager.getLocalBranches().contains("release"))
  }

  // ------------------------------------------------------- ref layout sanity

  @Test
  fun `clone has remote refs but only one local branch`() {
    // Guards the premise of the whole test class: if JGit's clone behaviour ever changes,
    // these tests would otherwise pass vacuously.
    Git.open(cloneDir).use { git ->
      val remoteRefs =
          git.repository.refDatabase
              .getRefsByPrefix(Constants.R_REMOTES)
              .map { it.name }
              .filterNot { it.endsWith("/HEAD") }
      val localRefs =
          git.repository.refDatabase.getRefsByPrefix(Constants.R_HEADS).map { it.name }

      assertEquals("clone should know the three remote branches", 3, remoteRefs.size)
      assertEquals("clone should have exactly one local branch", 1, localRefs.size)
      assertNotNull(remoteRefs.firstOrNull { it.endsWith("origin/feature") })
    }
  }
}
