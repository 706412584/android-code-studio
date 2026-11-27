package com.tom.rv2ide.uidesigner.utils

import com.tom.rv2ide.uidesigner.models.AttrKey
import com.tom.rv2ide.uidesigner.models.LayoutDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttrValueSupportTest {
  private val android = LayoutDocument.ANDROID
  private val app = LayoutDocument.APP

  private fun key(uri: String, name: String, prefix: String = "a") = AttrKey(uri, name, prefix)

  private fun document(xml: String) = LayoutDocument(xml, "file:///project/res/layout/example.xml")

  private val layout = document("""<RelativeLayout xmlns:a="$android" xmlns:app="$app" a:id="@+id/root">
    |  <TextView a:id="@+id/title" />
    |  <Button a:id="@id/action" />
    |  <TextView a:id="@+id/title" />
    |  <View a:id="@android:id/empty" />
    |  <View />
    |</RelativeLayout>""".trimMargin())

  private val names = listOf(key(app, "cardCornerRadius", "app"), key(android, "contentDescription", "android"),
      key(android, "layout_weight", "android"), key(app, "layout_widthPercent", "app"))
  private val namespaceOf: (String) -> String? = layout.root!!.element::getNamespaceURI

  @Test
  fun filtersLocalAndQualifiedNamesWithoutLosingTheNamespace() {
    assertEquals(listOf(names[0]), matchingAttrNames(names, "card", namespaceOf))
    assertEquals(listOf(names[1]), matchingAttrNames(names, "CONT", namespaceOf))
    assertEquals(names.drop(2), matchingAttrNames(names, "layout_w", namespaceOf))
    assertEquals(listOf(names[2]), matchingAttrNames(names, "a:layout_w", namespaceOf))
    assertEquals(listOf(names[3]), matchingAttrNames(names, "app:layout_w", namespaceOf))
    assertTrue(matchingAttrNames(names, "app:cont", namespaceOf).isEmpty())
  }

  @Test
  fun typedAndPickedNamesResolveToTheSameAttribute() {
    assertEquals(names[0], resolveAttrName(names, "cardCornerRadius", namespaceOf))
    assertEquals(names[0], resolveAttrName(names, "app:cardCornerRadius", namespaceOf))
    assertEquals(names[1], resolveAttrName(names, " contentDescription ", namespaceOf))
    val alias = requireNotNull(resolveAttrName(names, "a:gravity", namespaceOf))
    val canonical = requireNotNull(resolveAttrName(names, "android:gravity", namespaceOf))
    assertEquals(canonical.uri to canonical.name, alias.uri to alias.name)
    assertEquals("a", alias.prefix)
  }

  @Test
  fun unindexedCustomNamesStillWorkButUnknownPrefixesDoNot() {
    assertEquals(key(app, "localOption", "app"), resolveAttrName(emptyList(), "app:localOption", namespaceOf))
    assertNull(resolveAttrName(names, "unknown:localOption", namespaceOf))
    assertNull(resolveAttrName(names, "android:gravity:extra", namespaceOf))
    assertNull(resolveAttrName(names, ":gravity", namespaceOf))
  }

  @Test
  fun idNameAcceptsOnlyAppIds() {
    assertEquals("title", idName("@+id/title"))
    assertEquals("title", idName(" @id/title "))
    assertNull(idName("@android:id/empty"))
    assertNull(idName("@string/title"))
    assertNull(idName("@+id/"))
  }

  @Test
  fun recognisesIdReferenceAttributes() {
    assertTrue(isIdReferenceAttr(key(android, "layout_below")))
    assertTrue(isIdReferenceAttr(key(android, "nextFocusDown")))
    assertTrue(isIdReferenceAttr(key(app, "layout_constraintTop_toBottomOf", "app")))
    assertTrue(isIdReferenceAttr(key(app, "layout_constraintCircle", "app")))
    assertTrue(isIdReferenceAttr(key(app, "layout_anchor", "app")))
    assertFalse(isIdReferenceAttr(key(android, "id")))
    assertFalse(isIdReferenceAttr(key(android, "layout_width")))
    assertFalse(isIdReferenceAttr(key(app, "layout_constraintHorizontal_bias", "app")))
    // 约束属性只认 app 命名空间。
    assertFalse(isIdReferenceAttr(key(android, "layout_constraintTop_toBottomOf")))
    assertTrue(isIdAttr(key(android, "id")))
    assertFalse(isIdAttr(key(app, "id", "app")))
  }

  @Test
  fun collectsDistinctSortedIdsExcludingSelf() {
    assertEquals(listOf("action", "root", "title"), documentIds(layout, null))
    val root = requireNotNull(layout.root)
    assertEquals(listOf("action", "title"), documentIds(layout, root.id))
  }

  @Test
  fun reportsDuplicateIdOwnersExceptSelf() {
    val first = requireNotNull(layout.at(listOf(0)))
    val second = requireNotNull(layout.at(listOf(2)))
    assertEquals(listOf(second.id), duplicateIdOwners(layout, first.id, "@+id/title").map { it.id })
    // 写法不同（@id 与 @+id）也算重名。
    assertEquals(listOf(first.id, second.id), duplicateIdOwners(layout, -1, "@id/title").map { it.id })
    assertTrue(duplicateIdOwners(layout, first.id, "@+id/fresh").isEmpty())
    assertTrue(duplicateIdOwners(layout, first.id, "not an id").isEmpty())
  }

  @Test
  fun idSuggestionsFilterByNameAndOfferParentForConstraints() {
    val ids = listOf("action", "root", "title")
    val below = key(android, "layout_below")
    assertEquals(listOf("@id/action", "@id/root", "@id/title"), idSuggestions(ids, below, ""))
    assertEquals(listOf("@id/title"), idSuggestions(ids, below, "@+id/TIT"))
    // 按包含匹配："ti" 同时命中 action 与 title。
    assertEquals(listOf("@id/action", "@id/title"), idSuggestions(ids, below, "ti"))
    assertEquals(listOf("@id/action"), idSuggestions(ids, below, "ac"))
    val constraint = key(app, "layout_constraintStart_toEndOf", "app")
    assertEquals(listOf("parent", "@id/action", "@id/root", "@id/title"), idSuggestions(ids, constraint, ""))
    assertEquals(listOf("parent"), idSuggestions(ids, constraint, "par"))
  }

  @Test
  fun splitsFlagsAtLastSeparator() {
    assertEquals("" to "cen", splitFlags("cen"))
    assertEquals("center|" to "b", splitFlags("center|b"))
    assertEquals("top|start|" to "", splitFlags("top|start| "))
    assertEquals(setOf("top", "start"), usedFlags("top| start |"))
    assertTrue(usedFlags("").isEmpty())
  }
}
