package cn.enaium.imcode.ui

import cn.enaium.lsp.model.SymbolKind
import cn.enaium.xicons.imgui.Icon
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesAnyTypeDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiJsonArrayDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiJsonObjectDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesClassDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesConstantDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesConstructorDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesEnumDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesFieldDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesFunctionDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesInterfaceDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesMethodDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesModuleDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesPackageDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesParameterDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesPropertyDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesVariableDark

/**
 * IntelliJ icons for LSP `documentSymbol` kinds, used by the Structure pane.
 *
 * The Expui set (new UI, dark variant) has no icon for a handful of the
 * LSP kinds — namespace, key, the JSON value kinds, struct, event, operator,
 * type parameter — so those fall back to the closest concept IntelliJ does
 * draw; `Event` and `Operator` have no defensible relative at all and render
 * unadorned (the pane still reserves their gutter, so the names stay in one
 * column).
 */
internal object SymbolIcons {

    /** Icon for [kind] (`SymbolKind.*`), or null when there is none. */
    fun iconFor(kind: Int?): Icon? = when (kind) {
        SymbolKind.File -> ExpuiFileTypesAnyTypeDark
        SymbolKind.Module -> ExpuiNodesModuleDark
        // No namespace icon: a namespace is the same concept as a package.
        SymbolKind.Namespace -> ExpuiNodesPackageDark
        SymbolKind.Package -> ExpuiNodesPackageDark
        SymbolKind.Class -> ExpuiNodesClassDark
        SymbolKind.Method -> ExpuiNodesMethodDark
        SymbolKind.Property -> ExpuiNodesPropertyDark
        SymbolKind.Field -> ExpuiNodesFieldDark
        SymbolKind.Constructor -> ExpuiNodesConstructorDark
        SymbolKind.Enum -> ExpuiNodesEnumDark
        SymbolKind.Interface -> ExpuiNodesInterfaceDark
        SymbolKind.Function -> ExpuiNodesFunctionDark
        SymbolKind.Variable -> ExpuiNodesVariableDark
        SymbolKind.Constant -> ExpuiNodesConstantDark
        // LSP's JSON-flavoured kinds: IntelliJ draws these in the JSON
        // structure view, which is where they actually show up.
        SymbolKind.Array -> ExpuiJsonArrayDark
        SymbolKind.Object -> ExpuiJsonObjectDark
        // A key is a field of the enclosing object.
        SymbolKind.Key -> ExpuiNodesFieldDark
        SymbolKind.EnumMember -> ExpuiNodesEnumDark
        SymbolKind.Struct -> ExpuiNodesClassDark
        SymbolKind.TypeParameter -> ExpuiNodesParameterDark
        // Literal-valued kinds; IntelliJ has no per-type icon for them.
        SymbolKind.String,
        SymbolKind.Number,
        SymbolKind.Boolean,
        SymbolKind.Null,
        -> ExpuiNodesConstantDark
        else -> null
    }
}
