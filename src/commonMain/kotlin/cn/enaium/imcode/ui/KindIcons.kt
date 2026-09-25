package cn.enaium.imcode.ui

import cn.enaium.lsp.model.CompletionItemKind
import cn.enaium.xicons.imgui.Icon
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesAnyTypeDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesClassDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesConstantDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesConstructorDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesEnumDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesFieldDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesFolderDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesFunctionDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesInterfaceDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesMethodDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesModuleDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesParameterDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesPropertyDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesVariableDark

/**
 * IntelliJ node icons for LSP completion kinds, for the completion popup this
 * UI draws itself (lsp-edit has the same mapping for its own popup, but its
 * copy is module-internal and this host composes its rows by hand).
 *
 * Uses the `Expui*Dark` set to match the editor chrome. Kinds IntelliJ has no
 * distinct icon for fall back to the closest structural relative; ones with no
 * sensible relative at all (keyword, snippet, color, operator, …) return null
 * and render unadorned.
 */
internal object KindIcons {

    /** Icon for [kind] (`CompletionItemKind.*`), or null when there is none. */
    fun iconFor(kind: Int?): Icon? = when (kind) {
        CompletionItemKind.Method -> ExpuiNodesMethodDark
        CompletionItemKind.Function -> ExpuiNodesFunctionDark
        CompletionItemKind.Constructor -> ExpuiNodesConstructorDark
        CompletionItemKind.Field -> ExpuiNodesFieldDark
        CompletionItemKind.Variable -> ExpuiNodesVariableDark
        CompletionItemKind.Class -> ExpuiNodesClassDark
        CompletionItemKind.Interface -> ExpuiNodesInterfaceDark
        CompletionItemKind.Module -> ExpuiNodesModuleDark
        CompletionItemKind.Property -> ExpuiNodesPropertyDark
        CompletionItemKind.Enum -> ExpuiNodesEnumDark
        CompletionItemKind.Constant -> ExpuiNodesConstantDark
        CompletionItemKind.Folder -> ExpuiNodesFolderDark
        // No dedicated icon in the IntelliJ set: pick the nearest structure.
        CompletionItemKind.Value -> ExpuiNodesConstantDark
        CompletionItemKind.TypeParameter -> ExpuiNodesParameterDark
        CompletionItemKind.EnumMember -> ExpuiNodesEnumDark
        CompletionItemKind.Struct -> ExpuiNodesClassDark
        CompletionItemKind.File -> ExpuiFileTypesAnyTypeDark
        else -> null
    }
}
