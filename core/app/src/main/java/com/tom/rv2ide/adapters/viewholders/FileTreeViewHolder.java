/************************************************************************************
 * This file is part of AndroidIDE.
 *
 *
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 **************************************************************************************/

package com.tom.rv2ide.adapters.viewholders;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import androidx.transition.ChangeImageTransform;
import androidx.transition.TransitionManager;
import android.content.res.ColorStateList;
import com.google.android.material.color.MaterialColors;
import com.tom.rv2ide.databinding.LayoutFiletreeItemBinding;
import com.tom.rv2ide.models.FileExtension;
import com.tom.rv2ide.resources.R;
import com.unnamed.b.atv.model.TreeNode;
import java.io.File;

public class FileTreeViewHolder extends TreeNode.BaseNodeViewHolder<File> {

  private LayoutFiletreeItemBinding binding;

  public FileTreeViewHolder(Context context) {
    super(context);
  }

  @Override
  public View createNodeView(TreeNode node, File file) {
    this.binding = LayoutFiletreeItemBinding.inflate(LayoutInflater.from(context));

    final var indent = context.getResources().getDimensionPixelSize(R.dimen.acs_ui_space_md);
    final var icon = getIconForFile(file);
    final var chevron = binding.filetreeChevron;
    binding.filetreeName.setText(file.getName());
    binding.filetreeIcon.setImageResource(icon);

    final var root = applyPadding(node, binding, indent);
    final var iconColor = file.isDirectory()
        ? R.attr.colorPrimary
        : com.google.android.material.R.attr.colorOnSurfaceVariant;
    binding.filetreeIcon.setImageTintList(
        ColorStateList.valueOf(MaterialColors.getColor(root, iconColor)));

    if (file.isDirectory()) {
      chevron.setVisibility(View.VISIBLE);
      updateChevronIcon(node.isExpanded());
    } else {
      chevron.setVisibility(View.INVISIBLE);
    }

    return root;
  }

  private void updateChevronIcon(boolean expanded) {
    final int chevronIcon;
    if (expanded) {
      chevronIcon = R.drawable.ic_chevron_down;
    } else {
      chevronIcon = R.drawable.ic_chevron_right;
    }

    TransitionManager.beginDelayedTransition(binding.getRoot(), new ChangeImageTransform());
    binding.filetreeChevron.setImageResource(chevronIcon);
  }

  protected LinearLayout applyPadding(
      final TreeNode node, final LayoutFiletreeItemBinding binding, final int padding) {
    final var root = binding.getRoot();
    root.setPaddingRelative(
        root.getPaddingStart() + (padding * (node.getLevel() - 1)),
        root.getPaddingTop(),
        root.getPaddingEnd(),
        root.getPaddingBottom());
    return root;
  }

  protected int getIconForFile(final File file) {
    return FileExtension.Factory.forFile(file).getIcon();
  }

  public void setActivated(boolean activated) {
    if (binding == null) {
      return;
    }
    getView().setActivated(activated);
    binding.filetreeName.setTextColor(MaterialColors.getColor(
        binding.getRoot(),
        activated ? R.attr.colorOnPrimaryContainer : R.attr.colorOnSurface));
    if (!mNode.getValue().isDirectory()) {
      binding.filetreeIcon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(
          binding.getRoot(),
          activated ? R.attr.colorOnPrimaryContainer : R.attr.colorOnSurfaceVariant)));
    }
  }

  public void updateChevron(boolean expanded) {
    setLoading(false);
    updateChevronIcon(expanded);
  }

  public void setLoading(boolean loading) {
    final int viewIndex;
    if (loading) {
      viewIndex = 1;
    } else {
      viewIndex = 0;
    }

    binding.chevronLoadingSwitcher.setDisplayedChild(viewIndex);
  }
}
