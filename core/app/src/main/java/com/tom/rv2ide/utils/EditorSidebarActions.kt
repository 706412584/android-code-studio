package com.tom.rv2ide.utils

import android.content.Context
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.createGraph
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.FragmentNavigatorDestinationBuilder
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.get
import androidx.navigation.navOptions
import androidx.recyclerview.widget.LinearLayoutManager
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.ActionItem
import com.tom.rv2ide.actions.ActionsRegistry
import com.tom.rv2ide.actions.SidebarActionItem
import com.tom.rv2ide.actions.internal.DefaultActionsRegistry
import com.tom.rv2ide.actions.sidebar.AIAgentSidebarAction
import com.tom.rv2ide.actions.sidebar.AssetStudioSidebarAction
import com.tom.rv2ide.actions.sidebar.BuildVariantsSidebarAction
import com.tom.rv2ide.actions.sidebar.CloseProjectSidebarAction
import com.tom.rv2ide.actions.sidebar.FileTreeSidebarAction
import com.tom.rv2ide.actions.sidebar.PreferencesSidebarAction
import com.tom.rv2ide.actions.sidebar.SubModuleSidebarAction
import com.tom.rv2ide.actions.sidebar.GitClientAction
import com.tom.rv2ide.actions.sidebar.TerminalSidebarAction
import com.tom.rv2ide.fragments.sidebar.EditorSidebarFragment
import androidx.fragment.app.Fragment
import java.lang.ref.WeakReference

internal object EditorSidebarActions {

  private val fragmentCache = mutableMapOf<String, Fragment>()

  /**
   * 取得可用于 [fragmentManager] 的 Fragment 实例，按以下优先级：
   *
   * <ol>
   *   <li>[FragmentManager.findFragmentByTag] 命中的实例——侧栏重建时框架会按 tag
   *       自动恢复上次的 Fragment，必须复用它，否则会添加出两个同 tag 的实例
   *   <li>静态缓存中且确实挂在 [fragmentManager] 上的实例
   *   <li>都没有 → 新建
   * </ol>
   *
   * <p><b>为什么不能只看缓存</b>：`fragmentCache` 是 object 级静态缓存，生命周期长于
   * [EditorSidebarFragment]，而侧栏每次重建都会产生新的 `childFragmentManager`。
   * 缓存实例仍挂在<b>旧</b> manager 上时 `isAdded` 依然为 true，于是
   * `transaction.show(fragment)` 会抛
   * `Cannot show Fragment attached to a different FragmentManager`。
   *
   * <p>原先靠各 Fragment 在 `onDestroy` 里手动调用 [removeFragmentFromCache] 规避，
   * 但那是约定而非机制——漏掉一个（ArtificialFragment 就漏了）即崩溃。
   * 把校验收到这里后，清缓存退化为优化而非正确性前提。
   */
  private fun cachedFragmentFor(
      id: String,
      fragmentManager: androidx.fragment.app.FragmentManager,
      create: () -> Fragment,
  ): Fragment {
    // 1. 框架已按 tag 恢复的实例优先
    val restored = fragmentManager.findFragmentByTag(id)
    if (restored != null) {
      fragmentCache[id] = restored
      return restored
    }
    // 2. 缓存中且属于当前 manager
    val cached = fragmentCache[id]
    if (cached != null && cached.fragmentManager === fragmentManager) {
      return cached
    }
    // 3. 新建（顺带丢弃属于其它 manager 的陈旧缓存）
    return create().also { fragmentCache[id] = it }
  }

  @JvmStatic
  fun registerActions(context: Context) {
    val registry = ActionsRegistry.getInstance()
    var order = -1

    @Suppress("KotlinConstantConditions")
    registry.registerAction(FileTreeSidebarAction(context, ++order))
    registry.registerAction(BuildVariantsSidebarAction(context, ++order))
    registry.registerAction(GitClientAction(context, ++order))
    registry.registerAction(AIAgentSidebarAction(context, ++order))
    registry.registerAction(AssetStudioSidebarAction(context, ++order))
    registry.registerAction(SubModuleSidebarAction(context, ++order))
    registry.registerAction(PreferencesSidebarAction(context, ++order))
    registry.registerAction(CloseProjectSidebarAction(context, ++order))
  }

  @JvmStatic
  fun removeFragmentFromCache(fragmentId: String) {
    fragmentCache.remove(fragmentId)
  }

  @JvmStatic
  fun setup(sidebarFragment: EditorSidebarFragment) {
    val binding = sidebarFragment.getBinding() ?: return
    val context = sidebarFragment.requireContext()
    val navigationRecycler =
        binding.navigation.findViewById<androidx.recyclerview.widget.RecyclerView>(
            com.tom.rv2ide.R.id.navigation_recycler
        )

    val registry = ActionsRegistry.getInstance()
    val actions = registry.getActions(ActionItem.Location.EDITOR_SIDEBAR)
    if (actions.isEmpty()) {
      return
    }

    val data = ActionData()
    data.put(Context::class.java, context)

    val titleRef = WeakReference(binding.title)
    val subtitleRef = WeakReference(binding.subtitle)

    fun updateTitleVisibility(title: String?) {
      titleRef.get()?.let { titleView ->
        if (!title.isNullOrEmpty()) {
          titleView.text = title
          titleView.visibility = android.view.View.VISIBLE
        } else {
          titleView.visibility = android.view.View.GONE
        }
      }
    }

    fun updateSubtitleVisibility(subtitle: String?) {
      subtitleRef.get()?.let { subtitleView ->
        if (!subtitle.isNullOrEmpty()) {
          subtitleView.text = subtitle
          subtitleView.visibility = android.view.View.VISIBLE
        } else {
          subtitleView.visibility = android.view.View.GONE
        }
      }
    }

    val sortedActions =
        actions.entries.sortedBy { (_, action) ->
          (action as? SidebarActionItem)?.order ?: Int.MAX_VALUE
        }

    val navigationItems =
        sortedActions.map { (actionId, action) ->
          action as SidebarActionItem

          action.prepare(data)

          SidebarNavigationItem(
              id = actionId,
              icon = ContextCompat.getDrawable(context, action.iconRes),
              title = action.label,
              subtitle = action.subtitle,
              isSelected = actionId == FileTreeSidebarAction.ID,
              action = action,
          )
        }

    var currentFragmentId: String? = null
    lateinit var adapter: SidebarNavigationAdapter

    adapter = SidebarNavigationAdapter(
            onItemClick = { item ->
              val action = item.action

              if (action.fragmentClass == null) {
                (registry as DefaultActionsRegistry).executeAction(action, data)
                return@SidebarNavigationAdapter
              }

              if (currentFragmentId == action.id) {
                return@SidebarNavigationAdapter
              }

              val fragmentManager = sidebarFragment.childFragmentManager
              val fragment = cachedFragmentFor(action.id, fragmentManager) {
                action.fragmentClass!!.java.newInstance()
              }

              val transaction = fragmentManager.beginTransaction()

              fragmentManager.fragments.forEach { existingFragment ->
                if (existingFragment.isAdded) {
                  transaction.hide(existingFragment)
                }
              }

              // 复用前确认它确实挂在本 manager 上，否则 add 会因重复添加而抛异常
              if (fragment.fragmentManager === fragmentManager) {
                transaction.show(fragment)
              } else {
                transaction.add(binding.fragmentContainer.id, fragment, action.id)
              }

              transaction.commitNow()

              currentFragmentId = action.id

              updateTitleVisibility(item.title)
              updateSubtitleVisibility(item.subtitle)

              val updatedItems =
                  navigationItems.map { navItem -> 
                    navItem.copy(isSelected = navItem.id == item.id) 
                  }
              adapter.submitList(updatedItems)
            },
            onItemLongClick = { item ->
              if (item.action is TerminalSidebarAction) {
                true
              } else {
                false
              }
            },
        )

    navigationRecycler.layoutManager =
        LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    navigationRecycler.adapter = adapter
    adapter.submitList(navigationItems)

    val childFragmentManager = sidebarFragment.childFragmentManager

    // 侧栏重建（配置变更、从后台返回）时 childFragmentManager 会自动恢复上次的
    // Fragment。此时若仍按第一个 tab 建立初始状态，就会出现「显示的是 AI 助手、
    // 但 currentFragmentId 记的是文件树」，用户点回文件树时被
    // `currentFragmentId == action.id` 提前 return，界面卡住不动。
    // 因此优先认已恢复的那个，只有全新创建时才用第一个 tab。
    val restoredItem =
        navigationItems.firstOrNull { childFragmentManager.findFragmentByTag(it.id) != null }

    val initialItem = restoredItem ?: navigationItems.first()
    // 已恢复时 cachedFragmentFor 必定命中 findFragmentByTag，不会走到 create
    val initialFragment = cachedFragmentFor(initialItem.id, childFragmentManager) {
      initialItem.action.fragmentClass?.java?.newInstance()
          ?: throw IllegalStateException("First action must have a fragment")
    }

    // 框架恢复的实例已在栈上，重复 add 会抛异常；只有确实不在栈上才添加。
    if (initialFragment.fragmentManager !== childFragmentManager) {
      childFragmentManager.beginTransaction()
          .add(binding.fragmentContainer.id, initialFragment, initialItem.id)
          .commitNow()
    }

    currentFragmentId = initialItem.id

    updateTitleVisibility(initialItem.title)
    updateSubtitleVisibility(initialItem.subtitle)

    // 导航栏高亮同步到实际显示项，否则恢复后高亮停留在第一个 tab
    adapter.submitList(
        navigationItems.map { navItem ->
          navItem.copy(isSelected = navItem.id == initialItem.id)
        }
    )
  }

  @JvmStatic
  internal fun NavDestination.matchDestination(route: String): Boolean =
      hierarchy.any { it.route == route }

  @JvmStatic
  internal fun NavDestination.matchDestination(@IdRes destId: Int): Boolean =
      hierarchy.any { it.id == destId }
}