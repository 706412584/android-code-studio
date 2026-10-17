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

package com.tom.rv2ide.lsp.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tom.rv2ide.eventbus.events.project.ProjectInitializedEvent;
import com.tom.rv2ide.projects.IWorkspace;
import com.tom.rv2ide.utils.ILogger;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe implementation of {@link ILanguageServerRegistry}.
 *
 * @author Akash Yadav
 */
public class DefaultLanguageServerRegistry extends ILanguageServerRegistry {

  private final Map<String, ILanguageServer> mRegister = new HashMap<>();
  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  /**
   * Workspace of the foreground project session. Guarded by {@link #lock} for writes; reads are
   * volatile so that {@link #getActiveWorkspace()} never blocks.
   */
  @Nullable private volatile IWorkspace activeWorkspace = null;

  @Override
  public void register(@NonNull final ILanguageServer server) {
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this);
    }
    final boolean added;
    lock.writeLock().lock();
    try {
      final var old = mRegister.put(server.getServerId(), server);
      added = old == null;
      if (old != null) {
        mRegister.put(old.getServerId(), old);
      }
    } finally {
      lock.writeLock().unlock();
    }

    // A server registered while a project is already open (e.g. a new server type added after the
    // first workspace setup) must not stay unconfigured until the next sync.
    final IWorkspace workspace = activeWorkspace;
    if (added && workspace != null) {
      server.setupWorkspace(workspace);
    }
  }

  @Override
  public void connectClient(@NonNull final ILanguageClient client) {
    Objects.requireNonNull(client);
    lock.readLock().lock();
    try {
      for (final var server : mRegister.values()) {
        server.connectClient(client);
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void unregister(@NonNull final String serverId) {
    lock.writeLock().lock();
    try {
      final var registered = mRegister.remove(serverId);
      if (registered == null) {
        throw new IllegalStateException("No server found for the given server ID");
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void destroy() {
    EventBus.getDefault().unregister(this);
    lock.readLock().lock();
    try {
      for (var server : mRegister.values()) {
        server.shutdown();
      }
    } finally {
      lock.readLock().unlock();
    }

    lock.writeLock().lock();
    try {
      mRegister.clear();
      activeWorkspace = null;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Nullable
  @Override
  public ILanguageServer getServer(@NonNull final String serverId) {
    lock.readLock().lock();
    try {
      return mRegister.get(serverId);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void setActiveWorkspace(@Nullable final IWorkspace workspace, final boolean force) {
    final IWorkspace previous;
    lock.writeLock().lock();
    try {
      previous = activeWorkspace;
      if (!force && sameWorkspace(previous, workspace)) {
        return;
      }
      activeWorkspace = workspace;
    } finally {
      lock.writeLock().unlock();
    }

    if (workspace == null) {
      ILogger.ROOT.debug("Active workspace cleared; language servers keep their last workspace");
      return;
    }

    if (previous != null && !sameWorkspace(previous, workspace)) {
      ILogger.ROOT.debug(
          "Active workspace changed: {} -> {}", workspaceRoot(previous), workspaceRoot(workspace));
    }

    ILogger.ROOT.debug("Pushing active workspace to language servers...");
    lock.readLock().lock();
    try {
      for (final var server : mRegister.values()) {
        server.setupWorkspace(workspace);
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  @Nullable
  @Override
  public IWorkspace getActiveWorkspace() {
    return activeWorkspace;
  }

  /**
   * A {@link ProjectInitializedEvent} may be posted for a background session (e.g. a Gradle sync
   * finishing while the user is on another tab). Only the workspace of the foreground session may
   * be pushed to the servers, otherwise the last project to finish syncing would silently take over
   * completion and diagnostics for every tab.
   */
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  @SuppressWarnings("unused")
  public void onProjectInitialized(ProjectInitializedEvent event) {
    final var workspace = event.get(IWorkspace.class);
    if (workspace == null) {
      return;
    }

    final boolean adopt;
    lock.writeLock().lock();
    try {
      final IWorkspace active = activeWorkspace;
      if (active == null) {
        // No active workspace has been declared yet (single-project startup). Adopt the workspace
        // from the event so that the servers are still configured exactly as before.
        activeWorkspace = workspace;
        adopt = true;
      } else if (!sameWorkspace(active, workspace)) {
        ILogger.ROOT.debug(
            "Ignoring ProjectInitializedEvent for background workspace: {}",
            workspaceRoot(workspace));
        return;
      } else {
        // A re-sync replaces the workspace instance for the same project directory. Keep the
        // active reference pointing at the live instance so later `getActiveWorkspace()` callers
        // see the refreshed model.
        activeWorkspace = workspace;
        adopt = false;
      }
    } finally {
      lock.writeLock().unlock();
    }

    if (adopt) {
      ILogger.ROOT.debug(
          "No active workspace set; adopting workspace from ProjectInitializedEvent");
    }

    ILogger.ROOT.debug("Dispatching ProjectInitializedEvent to language servers...");
    lock.readLock().lock();
    try {
      for (final var server : mRegister.values()) {
        server.setupWorkspace(workspace);
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Workspace identity is the canonical project directory. A re-sync produces a fresh {@link
   * IWorkspace} instance for the same directory; that must not be treated as a tab switch (which
   * would leave the old workspace active) nor as a duplicate (which would skip the re-setup that a
   * re-sync needs).
   */
  private static boolean sameWorkspace(@Nullable final IWorkspace first,
      @Nullable final IWorkspace second) {
    if (first == second) {
      return true;
    }
    if (first == null || second == null) {
      return false;
    }
    return workspaceRoot(first).equals(workspaceRoot(second));
  }

  @NonNull
  private static String workspaceRoot(@NonNull final IWorkspace workspace) {
    final var dir = workspace.getProjectDir();
    try {
      return dir.getCanonicalPath();
    } catch (final Exception e) {
      return dir.getAbsolutePath();
    }
  }
}
