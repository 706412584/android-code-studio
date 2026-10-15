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
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
*/
package com.tom.rv2ide.git

import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.ignore.IgnoreNode
import org.eclipse.jgit.attributes.AttributesNode
import org.eclipse.jgit.attributes.AttributesNodeProvider
import java.io.File
import java.io.FileInputStream
import com.tom.rv2ide.utils.*

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class GitManager(private val projectPath: String) {
    
    private var git: Git? = null
    private var repository: Repository? = null
    private var ignoreNode: IgnoreNode? = null
    private var attributesNode: AttributesNode? = null
    
    fun initRepository(initialBranchName: String = "main"): Boolean {
        return try {
            val projectDir = File(projectPath)
            if (!projectDir.exists()) {
                projectDir.mkdirs()
            }
            
            git = Git.init().setDirectory(projectDir).call()
            repository = git?.repository
            
            if (initialBranchName != "master") {
                try {
                    git?.branchRename()?.setOldName("master")?.setNewName(initialBranchName)?.call()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            loadGitIgnore()
            loadGitAttributes()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun openRepository(): Boolean {
        return try {
            val gitDir = File(projectPath, ".git")
            if (!gitDir.exists()) return false
            
            repository = FileRepositoryBuilder()
                .setGitDir(gitDir)
                .readEnvironment()
                .findGitDir()
                .build()
            
            git = Git(repository)
            loadGitIgnore()
            loadGitAttributes()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    private fun loadGitIgnore() {
        try {
            val gitignoreFile = File(projectPath, ".gitignore")
            if (gitignoreFile.exists()) {
                ignoreNode = IgnoreNode()
                FileInputStream(gitignoreFile).use { input ->
                    ignoreNode?.parse(input)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun loadGitAttributes() {
        try {
            val gitattributesFile = File(projectPath, ".gitattributes")
            if (gitattributesFile.exists()) {
                attributesNode = AttributesNode()
                FileInputStream(gitattributesFile).use { input ->
                    attributesNode?.parse(input)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun isIgnored(path: String): Boolean {
        return try {
            val workTree = repository?.workTree ?: return false
            val file = File(workTree, path)
            val relativePath = file.relativeTo(workTree).path.replace(File.separatorChar, '/')
            
            ignoreNode?.isIgnored(relativePath, file.isDirectory) == IgnoreNode.MatchResult.IGNORED
        } catch (e: Exception) {
            false
        }
    }
    
    fun getStatus(): Status? {
        return try {
            git?.status()?.call()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun setUserConfig(name: String, email: String): Boolean {
        return try {
            val config = repository?.config
            config?.setString("user", null, "name", name)
            config?.setString("user", null, "email", email)
            config?.save()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun getUserConfig(): Pair<String, String>? {
        return try {
            val config = repository?.config
            val name = config?.getString("user", null, "name") ?: "User"
            val email = config?.getString("user", null, "email") ?: "user@example.com"
            Pair(name, email)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun getChangedFiles(): List<FileChange> {
        val changes = mutableListOf<FileChange>()
        val status = getStatus() ?: return changes
        
        status.added.forEach {
            if (!isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.ADDED, isStaged = true))
            }
        }
        
        status.changed.forEach {
            if (!isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.MODIFIED, isStaged = true))
            }
        }
        
        status.removed.forEach {
            if (!isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.DELETED, isStaged = true))
            }
        }
        
        status.modified.forEach {
            if (!status.changed.contains(it) && !isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.MODIFIED, isStaged = false))
            }
        }
        
        status.missing.forEach {
            if (!status.removed.contains(it) && !isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.DELETED, isStaged = false))
            }
        }
        
        status.untracked.forEach { 
            if (!isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.UNTRACKED, isStaged = false))
            }
        }
        
        status.conflicting.forEach {
            if (!isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.CONFLICTING, isStaged = false))
            }
        }
        
        return changes
    }
    
    fun stageFile(filePath: String): Boolean {
        return try {
            if (isIgnored(filePath)) {
                // Force add ignored files if explicitly staged
                git?.add()?.addFilepattern(filePath)?.setUpdate(false)?.call()
            } else {
                git?.add()?.addFilepattern(filePath)?.call()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun stageAllFiles(): Boolean {
        return try {
            git?.add()?.addFilepattern(".")?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun unstageFile(filePath: String): Boolean {
        return try {
            git?.reset()?.addPath(filePath)?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun commit(message: String, author: String = "User", email: String = "user@example.com"): Boolean {
        return try {
            git?.commit()
                ?.setMessage(message)
                ?.setAuthor(PersonIdent(author, email))
                ?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun getCommitHistory(maxCount: Int = 100): List<CommitInfo> {
        val commits = mutableListOf<CommitInfo>()
        try {
            val logs = git?.log()?.setMaxCount(maxCount)?.call()
            logs?.forEach { commit ->
                commits.add(
                    CommitInfo(
                        hash = commit.name,
                        shortHash = commit.name.substring(0, 7),
                        message = commit.fullMessage,
                        author = commit.authorIdent.name,
                        email = commit.authorIdent.emailAddress,
                        timestamp = commit.commitTime.toLong() * 1000
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return commits
    }
    
    fun getCurrentBranch(): String? {
        return try {
            repository?.branch
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 列出仓库已知的全部分支：本地分支（`refs/heads/...`）与远程跟踪分支
     * （`refs/remotes/...`）。
     *
     * <p>克隆只会为远端的默认分支创建本地分支，因此**只列本地分支会让刚克隆的仓库
     * 看起来只有一个分支**，用户无法切换到其它分支。远程分支与本地分支同名时去重，
     * 并跳过 `origin/HEAD` 这类符号引用。
     *
     * <p>注意：本方法返回的名字可能对应**只有远程跟踪 ref 的分支**（尚未在本地建立）。
     * 需要判断「本地是否已有该分支」的场景（如删除、创建校验）应改用
     * [getLocalBranches]。
     */
    fun getAllBranches(): List<String> {
        val branches = linkedSetOf<String>()
        try {
            git?.branchList()
                ?.setListMode(ListBranchCommand.ListMode.ALL)
                ?.call()
                ?.forEach { ref ->
                    val name = ref.name
                    when {
                        name.startsWith(Constants.R_HEADS) ->
                            branches.add(name.removePrefix(Constants.R_HEADS))

                        name.startsWith(Constants.R_REMOTES) -> {
                            val remoteBranch = name.removePrefix(Constants.R_REMOTES)
                            val branchName = remoteBranch.substringAfter('/', "")
                            if (branchName.isNotEmpty() && branchName != "HEAD") {
                                branches.add(branchName)
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return branches.sorted()
    }

    /**
     * 只列出**本地**分支名（`refs/heads/...`）。
     *
     * <p>与 [getAllBranches] 的区别在于本方法不会返回仅存在于远程跟踪 ref 的分支。
     * 删除分支、判断「分支是否已存在」等操作需要这个语义：JGit 的 `branchDelete`
     * 只删本地 ref，对只有远程跟踪 ref 的分支调用会失败。
     */
    fun getLocalBranches(): List<String> {
        val branches = mutableListOf<String>()
        try {
            git?.branchList()?.call()?.forEach { ref ->
                branches.add(ref.name.removePrefix(Constants.R_HEADS))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return branches.sorted()
    }

    fun createBranch(branchName: String): Boolean {
        return try {
            git?.branchCreate()?.setName(branchName)?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 切换到指定分支。
     *
     * <p>若本地没有该分支、但存在同名的远程跟踪 ref，则先基于它创建本地跟踪分支
     * （等价于 `git checkout <branch>` 的行为），再切换过去。否则直接切换本地分支。
     */
    fun checkoutBranch(branchName: String): Boolean {
        return try {
            val repo = repository
            val localRef = repo?.exactRef(Constants.R_HEADS + branchName)
            if (localRef != null) {
                git?.checkout()?.setName(branchName)?.call()
            } else {
                val remoteRef = findRemoteTrackingRef(branchName)
                if (remoteRef != null) {
                    git?.checkout()
                        ?.setName(branchName)
                        ?.setCreateBranch(true)
                        ?.setStartPoint(remoteRef)
                        ?.setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                        ?.call()
                } else {
                    // 既无本地分支也无远程跟踪分支——让 JGit 抛出，交由 catch 返回 false。
                    git?.checkout()?.setName(branchName)?.call()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 查找与 [branchName] 同名的远程跟踪 ref（如 `refs/remotes/origin/feature`）。
     *
     * <p>优先选 `origin`，其余远端按字母序。找不到返回 `null`。
     */
    private fun findRemoteTrackingRef(branchName: String): String? {
        val repo = repository ?: return null
        return try {
            repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES)
                .map { it.name }
                .filter { name ->
                    val remoteBranch = name.removePrefix(Constants.R_REMOTES)
                    val branch = remoteBranch.substringAfter('/', "")
                    branch.isNotEmpty() && branch != "HEAD" && branch == branchName
                }
                .sortedWith(
                    compareBy(
                        { it.removePrefix(Constants.R_REMOTES).substringBefore('/') != "origin" },
                        { it },
                    )
                )
                .firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 删除**本地**分支。
     *
     * <p>JGit 的 `branchDelete` 对不存在的分支名不会抛异常，而是静默返回——若不先校验，
     * 对「只有远程跟踪 ref」的分支调用会返回 `true`，让调用方误以为删除成功。
     * 因此这里先确认本地存在该分支。
     *
     * <p>删除远程分支需用 `git.push().setDelete(true)`，不在本方法职责内。
     */
    fun deleteBranch(branchName: String): Boolean {
        return try {
            val repo = repository ?: return false
            if (repo.exactRef(Constants.R_HEADS + branchName) == null) {
                return false
            }
            git?.branchDelete()?.setBranchNames(branchName)?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun getFileDiff(filePath: String): String {
        return try {
            val repo = repository ?: return ""
            val head = repo.resolve(Constants.HEAD)
            
            RevWalk(repo).use { revWalk ->
                val commit = revWalk.parseCommit(head)
                val tree = commit.tree
                
                val oldTreeParser = CanonicalTreeParser()
                repo.newObjectReader().use { reader ->
                    oldTreeParser.reset(reader, tree)
                }
                
                val diffs = git?.diff()
                    ?.setOldTree(oldTreeParser)
                    ?.setPathFilter(PathFilter.create(filePath))
                    ?.call()
                
                val diffBuilder = StringBuilder()
                diffs?.forEach { diff ->
                    diffBuilder.append("${diff.changeType}: ${diff.newPath}\n")
                }
                diffBuilder.toString()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
    
    fun discardChanges(filePath: String): Boolean {
        return try {
            git?.checkout()?.addPath(filePath)?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun reloadGitIgnoreAndAttributes() {
        loadGitIgnore()
        loadGitAttributes()
    }
    
    fun close() {
        git?.close()
        repository?.close()
    }

    fun addRemote(name: String, url: String): Boolean {
        return try {
            val remoteAddCommand = git?.remoteAdd()
            remoteAddCommand?.setName(name)
            remoteAddCommand?.setUri(org.eclipse.jgit.transport.URIish(url))
            remoteAddCommand?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun removeRemote(name: String): Boolean {
        return try {
            git?.remoteRemove()?.setRemoteName(name)?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun getRemotes(): List<RemoteInfo> {
        val remotes = mutableListOf<RemoteInfo>()
        try {
            git?.remoteList()?.call()?.forEach { remote ->
                remotes.add(
                    RemoteInfo(
                        name = remote.name,
                        fetchUrl = remote.urIs.firstOrNull()?.toString() ?: "",
                        pushUrl = remote.urIs.firstOrNull()?.toString() ?: ""
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return remotes
    }
    
    fun push(remoteName: String = "origin", branchName: String? = null, username: String? = null, password: String? = null): PushResult {
        return try {
            val pushCommand = git?.push()
            pushCommand?.setRemote(remoteName)
            
            if (branchName != null) {
                pushCommand?.setRefSpecs(org.eclipse.jgit.transport.RefSpec("refs/heads/$branchName:refs/heads/$branchName"))
            }
            
            pushCommand?.setCredentialsProvider(
                org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                    username ?: "", 
                    password ?: ""
                )
            )
            
            val results = pushCommand?.call()
            val success = results?.all { result ->
                result.remoteUpdates.all { update ->
                    update.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK ||
                    update.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE
                }
            } ?: false
            
            PushResult(success, if (success) "Push successful" else "Push failed")
        } catch (e: Exception) {
            e.printStackTrace()
            PushResult(false, e.message ?: "Push failed")
        }
    }
    
    fun pull(remoteName: String = "origin", branchName: String? = null, username: String? = null, password: String? = null): PullResult {
        return try {
            val pullCommand = git?.pull()
            pullCommand?.setRemote(remoteName)
            
            if (branchName != null) {
                pullCommand?.setRemoteBranchName(branchName)
            }
            
            pullCommand?.setCredentialsProvider(
                org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                    username ?: "", 
                    password ?: ""
                )
            )
            
            val result = pullCommand?.call()
            val success = result?.isSuccessful ?: false
            
            PullResult(success, if (success) "Pull successful" else "Pull failed")
        } catch (e: Exception) {
            e.printStackTrace()
            PullResult(false, e.message ?: "Pull failed")
        }
    }
    
    fun fetch(remoteName: String = "origin", username: String? = null, password: String? = null): FetchResult {
        return try {
            val fetchCommand = git?.fetch()
            fetchCommand?.setRemote(remoteName)
            
            fetchCommand?.setCredentialsProvider(
                org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                    username ?: "", 
                    password ?: ""
                )
            )
            
            fetchCommand?.call()
            FetchResult(true, "Fetch successful")
        } catch (e: Exception) {
            e.printStackTrace()
            FetchResult(false, e.message ?: "Fetch failed")
        }
    }
    
    fun clone(remoteUrl: String, localPath: String, username: String? = null, password: String? = null): Boolean {
        return try {
            val cloneCommand = Git.cloneRepository()
            cloneCommand.setURI(remoteUrl)
            cloneCommand.setDirectory(File(localPath))
            
            cloneCommand.setCredentialsProvider(
                org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                    username ?: "", 
                    password ?: ""
                )
            )
            
            git = cloneCommand.call()
            repository = git?.repository
            loadGitIgnore()
            loadGitAttributes()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun hasRemote(): Boolean {
        return try {
            git?.remoteList()?.call()?.isNotEmpty() ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    fun isRepositoryInitialized(): Boolean {
        return try {
            val gitDir = File(projectPath, ".git")
            gitDir.exists() && gitDir.isDirectory
        } catch (e: Exception) {
            false
        }
    }

}

data class FileChange(
    val path: String,
    val changeType: ChangeType,
    val isStaged: Boolean = false
)

enum class ChangeType {
    UNTRACKED,
    MODIFIED,
    ADDED,
    DELETED,
    CONFLICTING
}

data class CommitInfo(
    val hash: String,
    val shortHash: String,
    val message: String,
    val author: String,
    val email: String,
    val timestamp: Long
)

data class RemoteInfo(
    val name: String,
    val fetchUrl: String,
    val pushUrl: String
)

data class PushResult(
    val success: Boolean,
    val message: String
)

data class PullResult(
    val success: Boolean,
    val message: String
)

data class FetchResult(
    val success: Boolean,
    val message: String
)