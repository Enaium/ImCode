package cn.enaium.imcode.util

import cn.enaium.imcode.platform.ioFile
import cn.enaium.lsp.edit.Language

/**
 * Maps file names/extensions to the lsp-edit highlighting [Language] +
 * the LSP languageId sent in didOpen. Unknown files get plain-text.
 */
object LanguageDetect {
    private val plainFileNames = setOf(
        ".gitignore", ".gitattributes", ".gitmodules", ".editorconfig", ".env", ".npmrc", ".nvmrc",
        "Dockerfile", "Makefile", "gradle.properties", "local.properties",
        "README", "README.md", "LICENSE", "LICENSE.txt", "CHANGELOG", "NOTICE",
    )

    data class Lang(val language: Language, val languageId: String)

    private fun lang(l: Language, id: String = l.name) = Lang(l, id)

    private val byExtension: Map<String, Lang> = mapOf(
        "c" to lang(Language.cpp, "c"),
        "h" to lang(Language.cpp, "c"),
        "cpp" to lang(Language.cpp, "cpp"),
        "cc" to lang(Language.cpp, "cpp"),
        "cxx" to lang(Language.cpp, "cpp"),
        "hpp" to lang(Language.cpp, "cpp"),
        "hh" to lang(Language.cpp, "cpp"),
        "cs" to lang(Language.cpp, "csharp"),
        "lua" to lang(Language.cpp, "lua"),
        "py" to lang(Language.python, "python"),
        "glsl" to lang(Language.cpp, "glsl"),
        "hlsl" to lang(Language.cpp, "hlsl"),
        "json" to lang(Language.json, "json"),
        "jsonc" to lang(Language.json, "jsonc"),
        "md" to lang(Language.cpp, "markdown"),
        "markdown" to lang(Language.cpp, "markdown"),
        "sql" to lang(Language.cpp, "sql"),
        "kt" to lang(Language.kotlin, "kotlin"),
        "kts" to lang(Language.kotlin, "kotlin"),
        "java" to lang(Language.cpp, "java"),
        "go" to lang(Language.cpp, "go"),
        "rs" to lang(Language.cpp, "rust"),
        "ts" to lang(Language.cpp, "typescript"),
        "js" to lang(Language.cpp, "javascript"),
        "tsx" to lang(Language.cpp, "typescriptreact"),
        "jsx" to lang(Language.cpp, "javascriptreact"),
        "css" to lang(Language.cpp, "css"),
        "html" to lang(Language.cpp, "html"),
        "xml" to lang(Language.cpp, "xml"),
        "yml" to lang(Language.cpp, "yaml"),
        "yaml" to lang(Language.cpp, "yaml"),
        "toml" to lang(Language.cpp, "toml"),
        "sh" to lang(Language.cpp, "shellscript"),
        "txt" to lang(Language.cpp, "plaintext"),
        // gradle build scripts are NOT kotlin
        "gradle" to lang(Language.cpp, "plaintext"),
    )

    fun detect(path: String): Lang {
        val file = ioFile(path)
        val name = file.name
        if (name in plainFileNames) return Lang(Language.cpp, "plaintext")
        val ext = file.extension.lowercase()
        return byExtension[ext] ?: Lang(Language.cpp, "plaintext")
    }
}
