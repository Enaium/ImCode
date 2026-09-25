package cn.enaium.imcode.ui

import cn.enaium.xicons.imgui.Icon
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesAnyTypeDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesArchiveDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesConfigDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesCss
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesCsvDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesHtml
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesJava
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesJavaScriptDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesJsonDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesShellDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesTextDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesXmlDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesYamlDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesFolderDark
import cn.enaium.xicons.imgui.icons.intellij.JavaScriptCoreIconsIconsExpuiFileTypesTypeScriptDark
import cn.enaium.xicons.imgui.icons.intellij.KotlinBaseResourcesIconsOrgJetbrainsKotlinIdeaIconsExpuiKotlinDark
import cn.enaium.xicons.imgui.icons.intellij.LanguageGo
import cn.enaium.xicons.imgui.icons.intellij.LanguagePython
import cn.enaium.xicons.imgui.icons.intellij.LanguageRust
import cn.enaium.xicons.imgui.icons.intellij.MarkdownIconsIconsExpuiMarkdownDark

/**
 * IntelliJ file-type icons for the explorer tree and the editor tab strip.
 *
 * All are the `Expui*Dark` variants — the ones IntelliJ's new UI uses on a
 * dark theme, matching this editor's chrome. Unknown extensions fall back to
 * the generic file icon so the tree keeps a uniform left gutter. Layout
 * metrics live in [IconLayout].
 */
internal object FileIcons {

    /** Icon of the folder nodes in the explorer tree. */
    val folder: Icon = ExpuiNodesFolderDark

    private val byExtension: Map<String, Icon> = mapOf(
        // Kotlin/JVM
        "kt" to KotlinBaseResourcesIconsOrgJetbrainsKotlinIdeaIconsExpuiKotlinDark,
        "kts" to KotlinBaseResourcesIconsOrgJetbrainsKotlinIdeaIconsExpuiKotlinDark,
        "java" to ExpuiFileTypesJava,
        "gradle" to ExpuiFileTypesConfigDark,
        "properties" to ExpuiFileTypesConfigDark,
        // Data/config
        "json" to ExpuiFileTypesJsonDark,
        "xml" to ExpuiFileTypesXmlDark,
        "yaml" to ExpuiFileTypesYamlDark,
        "yml" to ExpuiFileTypesYamlDark,
        "toml" to ExpuiFileTypesConfigDark,
        "csv" to ExpuiFileTypesCsvDark,
        // Docs
        "md" to MarkdownIconsIconsExpuiMarkdownDark,
        "markdown" to MarkdownIconsIconsExpuiMarkdownDark,
        "txt" to ExpuiFileTypesTextDark,
        // Web
        "html" to ExpuiFileTypesHtml,
        "htm" to ExpuiFileTypesHtml,
        "css" to ExpuiFileTypesCss,
        "js" to ExpuiFileTypesJavaScriptDark,
        "mjs" to ExpuiFileTypesJavaScriptDark,
        "cjs" to ExpuiFileTypesJavaScriptDark,
        "jsx" to ExpuiFileTypesJavaScriptDark,
        "ts" to JavaScriptCoreIconsIconsExpuiFileTypesTypeScriptDark,
        "tsx" to JavaScriptCoreIconsIconsExpuiFileTypesTypeScriptDark,
        // Other languages
        "py" to LanguagePython,
        "rs" to LanguageRust,
        "go" to LanguageGo,
        "sh" to ExpuiFileTypesShellDark,
        "bash" to ExpuiFileTypesShellDark,
        "zsh" to ExpuiFileTypesShellDark,
        // Archives
        "zip" to ExpuiFileTypesArchiveDark,
        "jar" to ExpuiFileTypesArchiveDark,
        "tar" to ExpuiFileTypesArchiveDark,
        "gz" to ExpuiFileTypesArchiveDark,
    )

    private val fallback: Icon = ExpuiFileTypesAnyTypeDark

    /** Icon for the file named [name], by extension; generic when unknown. */
    fun forFile(name: String): Icon {
        val ext = name.substringAfterLast('.', "")
        return if (ext.isEmpty()) fallback else byExtension[ext.lowercase()] ?: fallback
    }
}
