package com.tom.rv2ide.uidesigner.models

import com.tom.rv2ide.uidesigner.utils.ViewToXml
import org.eclipse.lemminx.dom.DOMAttr
import org.eclipse.lemminx.dom.DOMElement
import org.eclipse.lemminx.dom.DOMParser

internal typealias NodePath = List<Int>

internal data class AttrKey(val uri: String?, val name: String, val prefix: String = "") {
  val qualifiedName: String get() = if (prefix.isEmpty()) name else "$prefix:$name"
}

/** parentId 为空时只能向空文档添加根节点。 */
internal data class Placement(val parentId: Long?, val index: Int)

/** XML 文本是快照；借助现有 DOM 的偏移量，只替换被编辑的片段。 */
internal class LayoutDocument(
    val xml: String,
    private val uri: String,
    ids: Map<NodePath, Long> = emptyMap(),
    private val nextId: Long = 1L,
) {
  class Node(val id: Long, val path: NodePath, val element: DOMElement) {
    val tag: String get() = element.tagName
    val parentPath: NodePath? get() = if (path.isEmpty()) null else path.dropLast(1)
    val attrs: List<Pair<AttrKey, String>>
      get() = element.attributeNodes.orEmpty().filterNot(::namespaceDeclaration).mapNotNull { attr ->
        attr.value?.let { attr.key() to ViewToXml.decode(it) }
      }
    val xmlId: String? get() = attrs.firstOrNull { (key, _) -> key.uri == ANDROID && key.name == "id" }?.second
    val label: String get() = tag.substringAfterLast('.') + (xmlId?.let { " · ${it.substringAfter('/')}" } ?: "")
  }

  private val dom = DOMParser.getInstance().parse(xml, uri, null)
  private var sequence = nextId
  private val byPath = linkedMapOf<NodePath, Node>()
  val root: Node?
  val nodes: Collection<Node> get() = byPath.values
  private val byId: Map<Long, Node>

  init {
    fun index(element: DOMElement, path: NodePath): Node {
      val node = Node(ids[path] ?: sequence++, path, element)
      byPath[path] = node
      sourceChildren(element).forEachIndexed { i, child -> index(child, path + i) }
      return node
    }
    root = dom.documentElement?.let { index(it, emptyList()) }
    byId = nodes.associateBy(Node::id)
  }

  val editable: Boolean
    get() = dom.doctype == null && dom.roots.count { it is DOMElement } <= 1 &&
        nodes.all { node -> node.element.let { it.hasStartTag() && it.isClosed && it.end <= xml.length } }

  fun node(id: Long?): Node? = byId[id]
  fun at(path: NodePath): Node? = byPath[path]
  fun children(node: Node): List<Node> = sourceChildren(node.element).indices.map { byPath.getValue(node.path + it) }

  fun childAt(placement: Placement): Node? = if (placement.parentId == null) root
      else node(placement.parentId)?.let { at(it.path + placement.index) }

  fun attribute(node: Node, key: AttrKey, value: String?): LayoutDocument {
    require(editable && node.tag != "merge" && (node.tag != "include" || key.uri != null || key.name != "layout"))
    val element = node.element
    val existing = element.attributeNodes.orEmpty().firstOrNull {
      !namespaceDeclaration(it) && it.localName == key.name && it.key().uri == key.uri
    }
    val old = existing?.value?.let(ViewToXml::decode)
    if (old == value) return this
    val revised = if (existing != null) {
      val range = requireNotNull(existing.nodeAttrValue)
      if (value == null) xml.removeRange(existing.start, range.end)
      else xml.replaceRange(range.start + 1, range.end - 1, ViewToXml.escape(value))
    } else {
      check(value != null)
      val (prefix, declaration) = prefixFor(node, key)
      val name = if (prefix.isEmpty()) key.name else "$prefix:${key.name}"
      val offset = element.offsetBeforeCloseOfStartTag
      xml.replaceRange(offset, offset, "$declaration $name=\"${ViewToXml.escape(value)}\"")
    }
    return recreate(revised) { it }
  }

  fun insert(parent: Node?, index: Int, content: String): LayoutDocument {
    require(editable)
    if (parent == null) {
      require(root == null)
      val separator = if (xml.isEmpty() || xml.endsWith('\n')) "" else newline
      return recreate(xml + separator + content + newline) { it }
    }
    val revised = insertAt(parent, index, content)
    return recreate(revised) { shift(it, parent.path, index, 1) }
  }

  fun remove(node: Node): LayoutDocument {
    require(editable && node.parentPath != null)
    val revised = xml.removeRange(node.element.start, node.element.end)
    return recreate(revised) { path ->
      if (path.startsWith(node.path)) null else shift(path, node.parentPath!!, node.path.last() + 1, -1)
    }
  }

  /** index 是删除原节点前的目标插槽，保证与添加及拖放提示的编号一致。 */
  fun move(node: Node, parent: Node, index: Int): LayoutDocument {
    require(editable && node.parentPath != null && !parent.path.startsWith(node.path))
    require(index in 0..children(parent).size)
    val fromParent = node.parentPath!!
    val from = node.path.last()
    val sameParent = fromParent == parent.path
    if (sameParent && (index == from || index == from + 1)) return this
    val newIndex = if (sameParent && index > from) index - 1 else index
    val newParent = shift(parent.path, fromParent, from + 1, -1)
    val content = fragment(node, parent)
    val without = remove(node)
    val revised = without.insertAt(requireNotNull(without.at(newParent)), newIndex, content)
    return recreate(revised) { path ->
      if (path.startsWith(node.path)) newParent + newIndex + path.drop(node.path.size)
      else shift(shift(path, fromParent, from + 1, -1), newParent, newIndex, 1)
    }
  }

  fun fragment(node: Node, target: Node? = null): String {
    val source = namespaces(node)
    val destination = target?.let(::namespaces).orEmpty()
    val local = node.element.attributeNodes.orEmpty().filter(::namespaceDeclaration).map { it.extractPrefixFromXmlns() }
    val declarations = (source.keys + "").filterNot { it in local }.mapNotNull { prefix ->
      val value = source[prefix].orEmpty()
      if (value == destination[prefix].orEmpty()) null else declaration(prefix, value)
    }.joinToString("")
    val start = node.element.start
    val offset = node.element.offsetBeforeCloseOfStartTag - start
    return xml.substring(start, node.element.end).replaceRange(offset, offset, declarations)
  }

  private fun insertAt(parent: Node, index: Int, content: String): String {
    val children = children(parent)
    require(index in 0..children.size)
    val element = parent.element
    val parentIndent = indentAt(element.start).orEmpty()
    val childIndent = children.firstOrNull()?.let { indentAt(it.element.start) } ?: "$parentIndent    "
    if (element.isSelfClosed) {
      return xml.replaceRange(element.offsetBeforeCloseOfStartTag, element.end,
          ">$newline$childIndent$content$newline$parentIndent</${parent.tag}>")
    }
    val sibling = children.getOrNull(index)
    val offset = sibling?.element?.start ?: element.endTagOpenOffset
    require(offset >= 0)
    val indent = indentAt(offset)
    val insertion = if (sibling != null) {
      "$content$newline${indent ?: childIndent}"
    } else {
      val start = if (indent != null && childIndent.startsWith(indent)) childIndent.removePrefix(indent) else "$newline$childIndent"
      "$start$content$newline$parentIndent"
    }
    return xml.replaceRange(offset, offset, insertion)
  }

  private fun indentAt(offset: Int): String? {
    val start = if (offset == 0) 0 else xml.lastIndexOf('\n', offset - 1) + 1
    return xml.substring(start, offset).takeIf { it.all { c -> c == ' ' || c == '\t' } }
  }

  private fun prefixFor(node: Node, key: AttrKey): Pair<String, String> {
    val namespace = key.uri ?: return "" to ""
    if (namespace == "http://www.w3.org/XML/1998/namespace") return "xml" to ""
    val active = namespaces(node)
    active.entries.firstOrNull { it.key.isNotEmpty() && it.value == namespace }?.let { return it.key to "" }
    val base = key.prefix.ifEmpty { if (namespace == ANDROID) "android" else "app" }
    val prefix = generateSequence(0) { it + 1 }.map { if (it == 0) base else "$base$it" }.first { it !in active }
    return prefix to declaration(prefix, namespace)
  }

  private fun namespaces(node: Node): Map<String, String> = buildMap {
    for (depth in 0..node.path.size) {
      val element = byPath.getValue(node.path.take(depth)).element
      for (attr in element.attributeNodes.orEmpty()) {
        if (namespaceDeclaration(attr)) put(attr.extractPrefixFromXmlns(), ViewToXml.decode(attr.value.orEmpty()))
      }
    }
  }

  private fun recreate(revised: String, map: (NodePath) -> NodePath?): LayoutDocument =
      LayoutDocument(revised, uri, buildMap {
        for (node in nodes) map(node.path)?.let { put(it, node.id) }
      }, sequence)

  private val newline: String get() = if ("\r\n" in xml) "\r\n" else "\n"

  companion object {
    const val ANDROID = "http://schemas.android.com/apk/res/android"
    const val APP = "http://schemas.android.com/apk/res-auto"
    private const val AAPT = "http://schemas.android.com/aapt"

    private fun sourceChildren(element: DOMElement): List<DOMElement> =
        element.children.filterIsInstance<DOMElement>().filterNot { it.namespaceURI == AAPT && it.localName == "attr" }

    private fun namespaceDeclaration(attr: DOMAttr): Boolean = attr.name == "xmlns" || attr.name.startsWith("xmlns:")

    private fun DOMAttr.key(): AttrKey {
      val prefix = prefix.orEmpty()
      val uri = when (prefix) {
        "" -> null // 默认命名空间只适用于元素，不适用于无前缀属性。
        "xml" -> "http://www.w3.org/XML/1998/namespace"
        else -> namespaceURI?.ifEmpty { null }
      }
      return AttrKey(uri, localName, prefix)
    }

    private fun declaration(prefix: String, uri: String): String {
      val name = if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"
      return " $name=\"${ViewToXml.escape(uri)}\""
    }

    internal fun NodePath.startsWith(ancestor: NodePath): Boolean = size >= ancestor.size && take(ancestor.size) == ancestor

    private fun shift(path: NodePath, parent: NodePath, from: Int, delta: Int): NodePath {
      if (!path.startsWith(parent) || path.size <= parent.size || path[parent.size] < from) return path
      return parent + (path[parent.size] + delta) + path.drop(parent.size + 1)
    }
  }
}
