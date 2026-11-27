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

package com.tom.rv2ide.uidesigner.utils

import android.content.Context
import com.blankj.utilcode.util.ThreadUtils
import com.tom.rv2ide.inflater.IView
import com.tom.rv2ide.inflater.IViewGroup
import com.tom.rv2ide.inflater.internal.ViewGroupImpl
import com.tom.rv2ide.inflater.internal.ViewImpl
import com.tom.rv2ide.lsp.xml.utils.XMLBuilder
import com.tom.rv2ide.tasks.executeAsyncProvideError
import com.tom.rv2ide.uidesigner.R
import com.tom.rv2ide.uidesigner.models.LayoutDocument
import com.tom.rv2ide.utils.DialogUtils
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.slf4j.LoggerFactory

/**
 * Generates XML code for [IView].
 *
 * @author Akash Yadav
 */
object ViewToXml {

  private val log = LoggerFactory.getLogger(ViewToXml::class.java)
  private val entities = Regex("&(?:amp|lt|gt|apos|quot|#[0-9]+|#x[0-9a-fA-F]+);")
  private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "apos" to "'", "quot" to "\"")

  internal fun escape(value: String): String = value
      .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
      .replace("\"", "&quot;").replace("'", "&apos;").replace("\t", "&#9;")
      .replace("\n", "&#10;").replace("\r", "&#13;")

  internal fun decode(value: String): String = entities.replace(value) { match ->
    val entity = match.value.drop(1).dropLast(1)
    val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16)
        else entity.removePrefix("#").toIntOrNull()
    namedEntities[entity] ?: code?.takeIf(Character::isValidCodePoint)
        ?.let { String(Character.toChars(it)) } ?: match.value
  }

  /** 仅序列化从控件库新建的控件；已有 XML 不经 View 树重写。 */
  internal fun newWidget(view: IView, defaults: List<Pair<String, String>>): String {
    val attributes = linkedMapOf(
        "android:layout_width" to "wrap_content", "android:layout_height" to "wrap_content",
    )
    view.attributes.forEach { attributes[it.qualifiedName] = it.value }
    defaults.forEach { (name, value) -> attributes.putIfAbsent(name, value) }
    val namespaces = linkedMapOf("android" to LayoutDocument.ANDROID)
    view.attributes.forEach { it.namespace?.let { ns -> namespaces[ns.prefix] = ns.uri } }
    if (defaults.any { it.first.startsWith("app:") }) namespaces["app"] = LayoutDocument.APP
    return buildString {
      append('<').append(view.tag)
      for ((prefix, uri) in namespaces) append(" xmlns:$prefix=\"").append(escape(uri)).append('"')
      for ((name, value) in attributes) append(" $name=\"").append(escape(value)).append('"')
      append(" />")
    }
  }

  @JvmStatic
  @JvmOverloads
  fun generateXml(
      context: Context,
      workspace: ViewGroupImpl,
      onGenerated: (String) -> Unit = {},
      onFailure: (result: String?, error: Throwable?) -> Unit,
  ) {
    context.apply {
      val future: CompletableFuture<String?> =
          executeAsyncProvideError({
            if (workspace.childCount == 0) {
              throw CompletionException(
                  IllegalStateException("No views have been added to workspace")
              )
            }

            if (workspace.childCount > 1) {
              throw CompletionException(
                  IllegalStateException("Invalid view hierarchy. More than one root views found.")
              )
            }

            return@executeAsyncProvideError generateXml(workspace[0] as ViewImpl)
          }) { _, _ ->
          }

      val progress =
          DialogUtils.newProgressDialog(
                  this,
                  getString(R.string.title_generating_xml),
                  getString(R.string.please_wait),
                  false,
              ) { _, _ ->
                future.cancel(true)
              }
              .show()

      future.whenComplete { result, error ->
        ThreadUtils.runOnUiThread {
          progress.dismiss()

          if (result.isNullOrBlank() || error != null) {
            log.error("Unable to generate XML code", error)
            onFailure(result, error)
            return@runOnUiThread
          }

          onGenerated(result)
        }
      }
    }
  }

  @JvmStatic
  fun generateXml(view: ViewImpl): String {
    val builder = XMLBuilder("", System.lineSeparator())
    builder.apply {
      appendXmlProlog()
      linefeed()
      appendView(view)
    }
    return builder.toString()
  }

  private fun XMLBuilder.appendView(view: ViewImpl, indent: Int = 1) {
    startElement(view.tag, false)

    for (namespace in view.namespaceDecls) {
      linefeed()
      indent(indent)
      addSingleAttribute("xmlns:${namespace.prefix}", namespace.uri, true)
    }

    for (attr in view.attributes) {
      linefeed()
      indent(indent)

      val ns = attr.namespace?.prefix?.let { "${it}:" } ?: ""
      addSingleAttribute("${ns}${attr.name}", attr.value, true)
    }

    if (view is IViewGroup) {

      closeStartElement()

      for (i in 0 until view.childCount) {
        linefeed()
        linefeed()
        indent(indent)
        appendView(view[i] as ViewImpl, indent + 1)
      }

      linefeed()
      linefeed()
      indent(indent - 1)
      endElement(view.tag)
    } else {
      selfCloseElement()
    }
  }

  private fun XMLBuilder.appendXmlProlog() {
    startPrologOrPI("xml")
    addSingleAttribute("version", "1.0", true)
    addSingleAttribute("encoding", "utf-8", true)
    endPrologOrPI()
  }
}
