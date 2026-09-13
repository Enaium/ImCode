import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(25)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass = "cn.enaium.imcode.MainKt"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.sdl.kmp)
            implementation(libs.imgui.kmp)
            implementation(libs.lsp.kmp)
            implementation(libs.lsp.edit)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // Tree-sitter grammar bindings (tree-sitter-languages-kmp).
            // Versions come from the version catalog (README table).
            implementation(libs.ktreesitter)
            implementation(libs.treesitter.agda)
            implementation(libs.treesitter.bash)
            implementation(libs.treesitter.c)
            implementation(libs.treesitter.c.sharp)
            implementation(libs.treesitter.cpp)
            implementation(libs.treesitter.css)
            implementation(libs.treesitter.diff)
            implementation(libs.treesitter.embedded.template)
            implementation(libs.treesitter.glsl)
            implementation(libs.treesitter.go)
            implementation(libs.treesitter.haskell)
            implementation(libs.treesitter.html)
            implementation(libs.treesitter.java)
            implementation(libs.treesitter.javascript)
            implementation(libs.treesitter.json)
            implementation(libs.treesitter.julia)
            implementation(libs.treesitter.kotlin)
            implementation(libs.treesitter.lua)
            implementation(libs.treesitter.markdown)
            implementation(libs.treesitter.ocaml)
            implementation(libs.treesitter.php)
            implementation(libs.treesitter.properties)
            implementation(libs.treesitter.python)
            implementation(libs.treesitter.regex)
            implementation(libs.treesitter.ruby)
            implementation(libs.treesitter.rust)
            implementation(libs.treesitter.scala)
            implementation(libs.treesitter.smali)
            implementation(libs.treesitter.toml)
            implementation(libs.treesitter.tsx)
            implementation(libs.treesitter.typescript)
            implementation(libs.treesitter.verilog)
            implementation(libs.treesitter.xml)
            implementation(libs.treesitter.yaml)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// SDL3 on macOS (via LWJGL) must run on the first thread, otherwise video
// driver init fails with "No available video device" (same as sdl-kmp's
// examples). --enable-native-access silences the LWJGL JVM warnings.
tasks.withType(JavaExec::class.java).configureEach {
    if (OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}