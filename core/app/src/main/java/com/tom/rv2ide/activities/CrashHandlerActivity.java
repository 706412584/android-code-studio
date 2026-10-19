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

package com.tom.rv2ide.activities;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.tom.rv2ide.app.IDEActivity;
import com.tom.rv2ide.databinding.ActivityCrashHandlerBinding;
import com.tom.rv2ide.fragments.CrashReportFragment;

public class CrashHandlerActivity extends IDEActivity {

  public static final String REPORT_ACTION = "com.tom.rv2ide.REPORT_CRASH";
  public static final String TRACE_KEY = "crash_trace";

  /**
   * OOM 恢复提示。非空表示「进程活下来了，只是某个操作失败」——界面应显示这条
   * 可操作建议，而不是「应用已崩溃」的完整上报页。
   */
  public static final String OOM_RECOVERED_KEY = "oom_recovered";

  private ActivityCrashHandlerBinding binding;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final var extra = getIntent().getExtras();
    if (extra == null) {
      finishAffinity();
      return;
    }

    final var report = extra.getString(TRACE_KEY, "Unable to get logs.");

    // OOM 已恢复：不显示完整崩溃上报页（那会让用户以为应用崩了），
    // 而是给一条可操作提示后直接返回。进程仍在运行，未保存内容还在。
    final var oomNotice = extra.getString(OOM_RECOVERED_KEY, "");
    if (oomNotice != null && !oomNotice.isEmpty()) {
      new androidx.appcompat.app.AlertDialog.Builder(this)
          .setTitle("内存不足")
          .setMessage(oomNotice + "\n\n技术细节：\n" + report)
          .setPositiveButton("知道了", (dialog, which) -> finish())
          .setCancelable(false)
          .show();
      return;
    }

    final var fragment = CrashReportFragment.newInstance(report);

    getSupportFragmentManager()
        .beginTransaction()
        .replace(binding.getRoot().getId(), fragment, "crash_report_fragment")
        .addToBackStack(null)
        .commit();
  }

  @Override
  @NonNull
  protected View bindLayout() {
    binding = ActivityCrashHandlerBinding.inflate(getLayoutInflater());
    return binding.getRoot();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    binding = null;
  }
}
