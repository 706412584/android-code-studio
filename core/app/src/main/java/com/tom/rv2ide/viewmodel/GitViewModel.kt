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
package com.tom.rv2ide.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.tom.rv2ide.R
import com.tom.rv2ide.git.CommitInfo
import com.tom.rv2ide.git.FileChange
import com.tom.rv2ide.git.GitManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tom.rv2ide.git.RemoteInfo
import com.tom.rv2ide.git.PushResult
import com.tom.rv2ide.git.PullResult
import com.tom.rv2ide.git.FetchResult
import com.tom.rv2ide.utils.*

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class GitViewModel(application: Application) : AndroidViewModel(application) {

    // Kept as a plain Application reference (not Application itself) so it can be
    // dropped in onCleared(); the ViewModel intentionally outlives the activity.
    private var appContext: Application? = application

    private fun str(resId: Int, vararg args: Any): String {
        val context = appContext ?: return ""
        return if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)
    }

    private var gitManager: GitManager? = null

    private val _changedFiles = MutableLiveData<List<FileChange>>()
    val changedFiles: LiveData<List<FileChange>> = _changedFiles
    
    private val _commitHistory = MutableLiveData<List<CommitInfo>>()
    val commitHistory: LiveData<List<CommitInfo>> = _commitHistory
    
    private val _currentBranch = MutableLiveData<String>()
    val currentBranch: LiveData<String> = _currentBranch
    
    private val _branches = MutableLiveData<List<String>>()
    val branches: LiveData<List<String>> = _branches
    
    private val _repositoryStatus = MutableLiveData<RepositoryStatus>()
    val repositoryStatus: LiveData<RepositoryStatus> = _repositoryStatus
    
    private val _operationResult = MutableLiveData<OperationResult>()
    val operationResult: LiveData<OperationResult> = _operationResult


    private val _remotes = MutableLiveData<List<RemoteInfo>>()
    val remotes: LiveData<List<RemoteInfo>> = _remotes
    
    private val _isRepositoryInitialized = MutableLiveData<Boolean>()
    val isRepositoryInitialized: LiveData<Boolean> = _isRepositoryInitialized
    
    private val _pushPullResult = MutableLiveData<RemoteOperationResult>()
    val pushPullResult: LiveData<RemoteOperationResult> = _pushPullResult

    private val _progressMessage = MutableLiveData<String?>()
    val progressMessage: LiveData<String?> = _progressMessage
    
    fun setUserConfig(name: String, email: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val success = gitManager?.setUserConfig(name, email) ?: false
                _operationResult.postValue(
                    OperationResult(
                        success = success,
                        message = if (success) str(R.string.git_client_user_config_updated) else str(R.string.git_client_user_config_update_failed)
                    )
                )
            }
        }
    }
    
    fun getUserConfig(): Pair<String, String>? {
        return gitManager?.getUserConfig()
    }
    
    fun checkRepositoryStatus(projectPath: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                gitManager = GitManager(projectPath)
                val initialized = gitManager?.isRepositoryInitialized() ?: false
                _isRepositoryInitialized.postValue(initialized)
                
                if (initialized) {
                    val opened = gitManager?.openRepository() ?: false
                    if (opened) {
                        _repositoryStatus.postValue(RepositoryStatus.OPENED)
                        refreshAll()
                    }
                }
            }
        }
    }
    
    fun refreshRemotes() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val remotesList = gitManager?.getRemotes() ?: emptyList()
                _remotes.postValue(remotesList)
            }
        }
    }
    
    fun addRemote(name: String, url: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val success = gitManager?.addRemote(name, url) ?: false
                _operationResult.postValue(
                    OperationResult(
                        success = success,
                        message = if (success) str(R.string.git_client_remote_added) else str(R.string.git_client_remote_add_failed)
                    )
                )
                if (success) refreshRemotes()
            }
        }
    }
    
    fun removeRemote(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val success = gitManager?.removeRemote(name) ?: false
                _operationResult.postValue(
                    OperationResult(
                        success = success,
                        message = if (success) str(R.string.git_client_remote_removed) else str(R.string.git_client_remote_remove_failed)
                    )
                )
                if (success) refreshRemotes()
            }
        }
    }
    
    fun stageFile(filePath: String) {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_staging_file))
            withContext(Dispatchers.IO) {
                val success = gitManager?.stageFile(filePath) ?: false
                _operationResult.postValue(
                    OperationResult(
                        success = success,
                        message = if (success) str(R.string.git_client_file_staged) else str(R.string.git_client_stage_file_failed)
                    )
                )
                if (success) refreshChangedFiles()
            }
            _progressMessage.postValue(null)
        }
    }
    
    fun stageAllFiles() {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_staging_all))
            withContext(Dispatchers.IO) {
                val success = gitManager?.stageAllFiles() ?: false
                _operationResult.postValue(
                    OperationResult(
                        success = success,
                        message = if (success) str(R.string.git_client_all_files_staged) else str(R.string.git_client_stage_files_failed)
                    )
                )
                if (success) refreshChangedFiles()
            }
            _progressMessage.postValue(null)
        }
    }

    fun unstageFile(filePath: String) {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_unstaging_file))
            withContext(Dispatchers.IO) {
                val success = gitManager?.unstageFile(filePath) ?: false
                _operationResult.postValue(
                    OperationResult(
                        success = success,
                        message = if (success) str(R.string.git_client_file_unstaged) else str(R.string.git_client_unstage_file_failed)
                    )
                )
                if (success) refreshChangedFiles()
            }
            _progressMessage.postValue(null)
        }
    }
    
    fun fetch(remoteName: String = "origin", username: String? = null, password: String? = null) {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_fetching))
            withContext(Dispatchers.IO) {
                val result = gitManager?.fetch(remoteName, username, password)
                    ?: FetchResult(false, str(R.string.git_client_git_manager_unavailable))
                _pushPullResult.postValue(
                    RemoteOperationResult(
                        success = result.success,
                        message = result.message,
                        operation = "fetch"
                    )
                )
                if (result.success) {
                    refreshCommitHistory()
                }
            }
            _progressMessage.postValue(null)
        }
    }
    
    fun discardChanges(filePath: String) {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_discarding))
            withContext(Dispatchers.IO) {
                val success = gitManager?.discardChanges(filePath) ?: false
                _operationResult.postValue(
                    OperationResult(
                        success = success,
                        message = if (success) str(R.string.git_client_changes_discarded) else str(R.string.git_client_discard_failed)
                    )
                )
                if (success) refreshChangedFiles()
            }
            _progressMessage.postValue(null)
        }
    }
    
    fun createBranch(branchName: String) {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_creating_branch))
            withContext(Dispatchers.IO) {
                val success = gitManager?.createBranch(branchName) ?: false
                _operationResult.postValue(
                    OperationResult(
                        success = success,
                        message = if (success) str(R.string.git_client_branch_created) else str(R.string.git_client_branch_create_failed)
                    )
                )
                if (success) {
                    refreshBranches()
                }
            }
            _progressMessage.postValue(null)
        }
    }
    
    fun checkoutBranch(branchName: String) {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_switching_branch))
            withContext(Dispatchers.IO) {
                val success = gitManager?.checkoutBranch(branchName) ?: false
                _operationResult.postValue(
                    OperationResult(
                        success = success,
                        message = if (success) str(R.string.git_client_switched_to, branchName) else str(R.string.git_client_checkout_failed)
                    )
                )
                if (success) {
                    refreshAll()
                }
            }
            _progressMessage.postValue(null)
        }
    }
    
    fun deleteBranch(branchName: String) {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_deleting_branch))
            withContext(Dispatchers.IO) {
                val success = gitManager?.deleteBranch(branchName) ?: false
                _operationResult.postValue(
                    OperationResult(
                        success = success,
                        message = if (success) str(R.string.git_client_branch_deleted) else str(R.string.git_client_branch_delete_failed)
                    )
                )
                if (success) refreshBranches()
            }
            _progressMessage.postValue(null)
        }
    }

    fun commit(message: String, author: String, email: String) {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_committing))
            withContext(Dispatchers.IO) {
                val success = gitManager?.commit(message, author, email) ?: false
                _operationResult.postValue(
                    OperationResult(
                        success = success,
                        message = if (success) str(R.string.git_client_changes_committed) else str(R.string.git_client_commit_failed)
                    )
                )
                if (success) {
                    refreshChangedFiles()
                    refreshCommitHistory()
                }
            }
            _progressMessage.postValue(null)
        }
    }
    
    fun push(remoteName: String = "origin", branchName: String? = null, username: String? = null, password: String? = null) {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_pushing))
            withContext(Dispatchers.IO) {
                val result = gitManager?.push(remoteName, branchName, username, password) 
                    ?: PushResult(false, str(R.string.git_client_git_manager_unavailable))
                _pushPullResult.postValue(
                    RemoteOperationResult(
                        success = result.success,
                        message = result.message,
                        operation = "push"
                    )
                )
            }
            _progressMessage.postValue(null)
        }
    }
    
    fun pull(remoteName: String = "origin", branchName: String? = null, username: String? = null, password: String? = null) {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_pulling))
            withContext(Dispatchers.IO) {
                val result = gitManager?.pull(remoteName, branchName, username, password)
                    ?: PullResult(false, str(R.string.git_client_git_manager_unavailable))
                _pushPullResult.postValue(
                    RemoteOperationResult(
                        success = result.success,
                        message = result.message,
                        operation = "pull"
                    )
                )
                if (result.success) {
                    refreshAll()
                }
            }
            _progressMessage.postValue(null)
        }
    }
    
    fun cloneRepository(remoteUrl: String, localPath: String, username: String? = null, password: String? = null) {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_cloning))
            withContext(Dispatchers.IO) {
                gitManager = GitManager(localPath)
                val success = gitManager?.clone(remoteUrl, localPath, username, password) ?: false
                if (success) {
                    _repositoryStatus.postValue(RepositoryStatus.INITIALIZED)
                    refreshAll()
                } else {
                    _repositoryStatus.postValue(RepositoryStatus.ERROR)
                    _operationResult.postValue(
                        OperationResult(
                            success = false,
                            message = str(R.string.git_client_clone_failed)
                        )
                    )
                }
            }
            _progressMessage.postValue(null)
        }
    }
    
    fun initializeRepository(path: String, initialBranch: String = "main") {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_initializing))
            withContext(Dispatchers.IO) {
                gitManager = GitManager(path)
                val success = gitManager?.initRepository(initialBranchName = initialBranch) ?: false
                if (success) {
                    gitManager?.openRepository()
                    _repositoryStatus.postValue(RepositoryStatus.INITIALIZED)
                    refreshAll()
                } else {
                    _repositoryStatus.postValue(RepositoryStatus.ERROR)
                }
            }
            _progressMessage.postValue(null)
        }
    }
    
    fun openExistingRepository(path: String) {
        viewModelScope.launch {
            _progressMessage.postValue(str(R.string.git_client_progress_opening))
            withContext(Dispatchers.IO) {
                gitManager = GitManager(path)
                val success = gitManager?.openRepository() ?: false
                if (success) {
                    _repositoryStatus.postValue(RepositoryStatus.OPENED)
                    refreshAll()
                } else {
                    _repositoryStatus.postValue(RepositoryStatus.ERROR)
                }
            }
            _progressMessage.postValue(null)
        }
    }
    
    fun refreshAll() {
        refreshChangedFiles()
        refreshCommitHistory()
        refreshBranches()
        refreshRemotes()
    }
    
    fun refreshChangedFiles() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val changes = gitManager?.getChangedFiles() ?: emptyList()
                _changedFiles.postValue(changes)
            }
        }
    }
    
    fun refreshCommitHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val commits = gitManager?.getCommitHistory() ?: emptyList()
                _commitHistory.postValue(commits)
            }
        }
    }
    
    fun refreshBranches() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val current = gitManager?.getCurrentBranch()
                _currentBranch.postValue(current ?: "")
                
                val allBranches = gitManager?.getAllBranches() ?: emptyList()
                _branches.postValue(allBranches)
            }
        }
    }
    
    fun commit(message: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val success = gitManager?.commit(message) ?: false
                _operationResult.postValue(
                    OperationResult(
                        success = success,
                        message = if (success) str(R.string.git_client_changes_committed) else str(R.string.git_client_commit_failed)
                    )
                )
                if (success) {
                    refreshChangedFiles()
                    refreshCommitHistory()
                }
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        gitManager?.close()
        appContext = null
    }
}

enum class RepositoryStatus {
    UNINITIALIZED,
    INITIALIZED,
    OPENED,
    ERROR
}

data class OperationResult(
    val success: Boolean,
    val message: String
)

data class RemoteOperationResult(
    val success: Boolean,
    val message: String,
    val operation: String
)