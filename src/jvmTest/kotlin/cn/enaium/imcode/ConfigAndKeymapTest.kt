package cn.enaium.imcode

import cn.enaium.imcode.config.Config
import cn.enaium.imcode.config.DefaultShortcuts
import cn.enaium.imcode.config.KeyAction
import cn.enaium.imcode.config.Keymap
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeymapTest {
    @Test
    fun parsesModifierChords() {
        val chord = Keymap.parse("Ctrl+Shift+F4")
        assertNotNull(chord)
        assertEquals(setOf("Ctrl", "Shift"), chord.mods)
        assertEquals("F4", chord.key)
        // The label is platform-specific (macOS shows Cmd for Ctrl), so the
        // contract is the round trip: the label renders the same chord back.
        assertEquals(chord, Keymap.parse(chord.display()))
    }

    @Test
    fun rejectsUnknownKeys() {
        assertNull(Keymap.parse("Ctrl+NotAKey"))
        assertNull(Keymap.parse(""))
        assertNull(Keymap.parse("Ctrl+Shift+Alt+Super+Meta"))
    }

    @Test
    fun everyDefaultShortcutParses() {
        for ((action, chordText) in DefaultShortcuts.IDEA) {
            val chord = Keymap.parse(chordText)
            assertNotNull(chord, "default chord '$chordText' for '$action' must parse")
            assertEquals(chord, Keymap.parse(chord.display()), "'$chordText' must render back to itself")
        }
    }

    @Test
    fun effectiveShortcutsMergeCustomOverrides() {
        val cfg = Config(shortcuts = mapOf(KeyAction.SAVE to "Ctrl+Shift+S"))
        assertEquals("Ctrl+Shift+S", cfg.effectiveShortcuts()[KeyAction.SAVE])
        assertEquals("Ctrl+O", cfg.effectiveShortcuts()[KeyAction.OPEN_FILE])
    }
}

class ConfigSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun roundTrip() {
        val cfg = Config(fontSize = 15f, rpcLogging = true, lspServers = listOf(cn.enaium.imcode.config.LspServer()))
        val encoded = json.encodeToString(Config.serializer(), cfg)
        val decoded = json.decodeFromString(Config.serializer(), encoded)
        assertEquals(cfg.fontSize, decoded.fontSize)
        assertEquals(cfg.rpcLogging, decoded.rpcLogging)
        assertEquals(cfg.lspServers, decoded.lspServers)
    }

    @Test
    fun commandStringRoundTripsWithQuotes() {
        val server = cn.enaium.imcode.config.LspServer(
            name = "kotlin-lsp",
            command = listOf("faketime", "2026-06-04", "kotlin-lsp", "--stdio"),
            commandString = "faketime \"2026-06-04\" kotlin-lsp --stdio",
        )
        val cfg = Config(lspServers = listOf(server))
        val encoded = json.encodeToString(Config.serializer(), cfg)
        val decoded = json.decodeFromString(Config.serializer(), encoded)
        assertEquals(server, decoded.lspServers[0])
        // The verbatim command string with quotes survives serialization.
        assertTrue(decoded.lspServers[0].commandString!!.contains("\"2026-06-04\""))
    }

    @Test
    fun legacyConfigWithoutCommandStringStillLoads() {
        val jsonText = """{"lspServers": [{"name": "k", "command": ["kotlin-lsp", "--stdio"]}]}"""
        val decoded = json.decodeFromString(Config.serializer(), jsonText)
        assertEquals("k", decoded.lspServers[0].name)
        assertEquals(listOf("kotlin-lsp", "--stdio"), decoded.lspServers[0].command)
        assertEquals(null, decoded.lspServers[0].commandString)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val jsonText = """{"fontSize": 14.0, "futureField": {"nested": true}}"""
        val decoded = json.decodeFromString(Config.serializer(), jsonText)
        assertEquals(14.0f, decoded.fontSize)
    }

    @Test
    fun defaultsAppliedForMissingFields() {
        val jsonText = """{}"""
        val decoded = json.decodeFromString(Config.serializer(), jsonText)
        assertEquals(13.0f, decoded.fontSize)
        assertEquals(0, decoded.recentFiles.size)
    }
}