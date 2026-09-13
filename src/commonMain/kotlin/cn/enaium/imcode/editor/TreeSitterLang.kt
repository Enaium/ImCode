package cn.enaium.imcode.editor

import cn.enaium.lsp.edit.syntax.treesitter.HighlightQueries
import cn.enaium.lsp.edit.syntax.treesitter.TreeSitterHighlighter
import cn.enaium.treesitter.languages.agda.TreeSitterAgda
import cn.enaium.treesitter.languages.bash.TreeSitterBash
import cn.enaium.treesitter.languages.c.TreeSitterC
import cn.enaium.treesitter.languages.cpp.TreeSitterCpp
import cn.enaium.treesitter.languages.csharp.TreeSitterCSharp
import cn.enaium.treesitter.languages.css.TreeSitterCss
import cn.enaium.treesitter.languages.diff.TreeSitterDiff
import cn.enaium.treesitter.languages.embeddedtemplate.TreeSitterEmbeddedTemplate
import cn.enaium.treesitter.languages.glsl.TreeSitterGlsl
import cn.enaium.treesitter.languages.go.TreeSitterGo
import cn.enaium.treesitter.languages.haskell.TreeSitterHaskell
import cn.enaium.treesitter.languages.html.TreeSitterHtml
import cn.enaium.treesitter.languages.java.TreeSitterJava
import cn.enaium.treesitter.languages.javascript.TreeSitterJavascript
import cn.enaium.treesitter.languages.json.TreeSitterJson
import cn.enaium.treesitter.languages.julia.TreeSitterJulia
import cn.enaium.treesitter.languages.kotlin.TreeSitterKotlin
import cn.enaium.treesitter.languages.lua.TreeSitterLua
import cn.enaium.treesitter.languages.markdown.TreeSitterMarkdown
import cn.enaium.treesitter.languages.ocaml.TreeSitterOcaml
import cn.enaium.treesitter.languages.php.TreeSitterPhp
import cn.enaium.treesitter.languages.properties.TreeSitterProperties
import cn.enaium.treesitter.languages.python.TreeSitterPython
import cn.enaium.treesitter.languages.regex.TreeSitterRegex
import cn.enaium.treesitter.languages.ruby.TreeSitterRuby
import cn.enaium.treesitter.languages.rust.TreeSitterRust
import cn.enaium.treesitter.languages.scala.TreeSitterScala
import cn.enaium.treesitter.languages.smali.TreeSitterSmali
import cn.enaium.treesitter.languages.toml.TreeSitterToml
import cn.enaium.treesitter.languages.tsx.TreeSitterTsx
import cn.enaium.treesitter.languages.typescript.TreeSitterTypescript
import cn.enaium.treesitter.languages.verilog.TreeSitterVerilog
import cn.enaium.treesitter.languages.xml.TreeSitterXml
import cn.enaium.treesitter.languages.yaml.TreeSitterYaml
import io.github.treesitter.ktreesitter.Language
import cn.enaium.imcode.platform.ioFile

/**
 * Every tree-sitter grammar available to ImCode, mirroring lsp-edit's
 * syntax example. [queryId] selects the highlight query from
 * [HighlightQueries.all]; files whose extension maps to a spec here get
 * tree-sitter highlighting when no LSP server owns them.
 */
enum class TreeSitterLang(
    val id: String,
) {
    AGDA("agda"),
    BASH("bash"),
    C("c"),
    C_SHARP("c-sharp"),
    CPP("cpp"),
    CSS("css"),
    DIFF("diff"),
    EMBEDDED_TEMPLATE("embedded-template"),
    GLSL("glsl"),
    GO("go"),
    HASKELL("haskell"),
    HTML("html"),
    JAVA("java"),
    JAVASCRIPT("javascript"),
    JSON("json"),
    JULIA("julia"),
    KOTLIN("kotlin"),
    LUA("lua"),
    MARKDOWN("markdown"),
    OCAML("ocaml"),
    PHP("php"),
    PROPERTIES("properties"),
    PYTHON("python"),
    REGEX("regex"),
    RUBY("ruby"),
    RUST("rust"),
    SCALA("scala"),
    SMALI("smali"),
    TOML("toml"),
    TSX("tsx"),
    TYPESCRIPT("typescript"),
    VERILOG("verilog"),
    XML("xml"),
    YAML("yaml"),
    ;

    /** The native grammar, loaded from the bundled library. */
    fun language(): Language = when (this) {
        AGDA -> Language(TreeSitterAgda.language())
        BASH -> Language(TreeSitterBash.language())
        C -> Language(TreeSitterC.language())
        C_SHARP -> Language(TreeSitterCSharp.language())
        CPP -> Language(TreeSitterCpp.language())
        CSS -> Language(TreeSitterCss.language())
        DIFF -> Language(TreeSitterDiff.language())
        EMBEDDED_TEMPLATE -> Language(TreeSitterEmbeddedTemplate.language())
        GLSL -> Language(TreeSitterGlsl.language())
        GO -> Language(TreeSitterGo.language())
        HASKELL -> Language(TreeSitterHaskell.language())
        HTML -> Language(TreeSitterHtml.language())
        JAVA -> Language(TreeSitterJava.language())
        JAVASCRIPT -> Language(TreeSitterJavascript.language())
        JSON -> Language(TreeSitterJson.language())
        JULIA -> Language(TreeSitterJulia.language())
        KOTLIN -> Language(TreeSitterKotlin.language())
        LUA -> Language(TreeSitterLua.language())
        MARKDOWN -> Language(TreeSitterMarkdown.language())
        OCAML -> Language(TreeSitterOcaml.language())
        PHP -> Language(TreeSitterPhp.language())
        PROPERTIES -> Language(TreeSitterProperties.language())
        PYTHON -> Language(TreeSitterPython.language())
        REGEX -> Language(TreeSitterRegex.language())
        RUBY -> Language(TreeSitterRuby.language())
        RUST -> Language(TreeSitterRust.language())
        SCALA -> Language(TreeSitterScala.language())
        SMALI -> Language(TreeSitterSmali.language())
        TOML -> Language(TreeSitterToml.language())
        TSX -> Language(TreeSitterTsx.language())
        TYPESCRIPT -> Language(TreeSitterTypescript.language())
        VERILOG -> Language(TreeSitterVerilog.language())
        XML -> Language(TreeSitterXml.language())
        YAML -> Language(TreeSitterYaml.language())
    }

    /** The highlight query source for this grammar (from lsp-edit). */
    val query: String? get() = HighlightQueries.all[id]

    /** Builds a highlighter for this grammar, or null when no query exists. */
    fun highlighter(): TreeSitterHighlighter? {
        val q = query ?: return null
        return try {
            TreeSitterHighlighter(language(), q)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private val byExtension: Map<String, TreeSitterLang> = buildMap {
            put("agda", AGDA)
            put("sh", BASH); put("bash", BASH)
            put("c", C); put("h", C)
            put("cs", C_SHARP)
            put("cpp", CPP); put("cc", CPP); put("cxx", CPP); put("hpp", CPP); put("hh", CPP)
            put("css", CSS)
            put("diff", DIFF); put("patch", DIFF)
            put("erb", EMBEDDED_TEMPLATE)
            put("glsl", GLSL)
            put("go", GO)
            put("hs", HASKELL)
            put("html", HTML); put("htm", HTML)
            put("java", JAVA)
            put("js", JAVASCRIPT); put("mjs", JAVASCRIPT); put("cjs", JAVASCRIPT)
            put("json", JSON); put("jsonc", JSON)
            put("jl", JULIA)
            put("kt", KOTLIN); put("kts", KOTLIN)
            put("lua", LUA)
            put("md", MARKDOWN); put("markdown", MARKDOWN)
            put("ml", OCAML)
            put("php", PHP)
            put("properties", PROPERTIES); put("ini", PROPERTIES)
            put("py", PYTHON); put("python", PYTHON)
            put("regex", REGEX)
            put("rb", RUBY)
            put("rs", RUST)
            put("scala", SCALA); put("sc", SCALA)
            put("smali", SMALI)
            put("toml", TOML)
            put("tsx", TSX)
            put("ts", TYPESCRIPT)
            put("v", VERILOG)
            put("xml", XML); put("svg", XML)
            put("yaml", YAML); put("yml", YAML)
        }

        /** The tree-sitter grammar for [path]'s extension, or null. */
        fun forPath(path: String): TreeSitterLang? =
            byExtension[ioFile(path).extension.lowercase()]
    }
}
