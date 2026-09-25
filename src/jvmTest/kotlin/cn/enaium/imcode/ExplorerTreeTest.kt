package cn.enaium.imcode

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import cn.enaium.imcode.ui.ExplorerWidget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bulk actions on the file tree.
 *
 * ImGui only submits a tree node's children while the node is open, so a
 * collapse-all that forced state on the drawn nodes closed the root and left
 * every folder below it open — they reappeared expanded on the next expand.
 * The tree keeps that state itself, which is what these assert.
 */
class ExplorerTreeTest {

    @Test
    fun bulkActionsCoverFoldersThatAreNotDrawn() {
        val root = File.createTempFile("imcode-tree", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        try {
            val a = File(root, "alpha").apply { mkdirs() }
            val b = File(root, "beta").apply { mkdirs() }
            File(a, "one.txt").writeText("one")
            File(b, "two.txt").writeText("two")
            File(a, "nested").mkdirs()

            val ctx = ImGui.createContext()
            try {
                val io = ImGui.getIO()
                io.displaySize = ImVec2(800f, 600f)
                io.deltaTime = 1f / 60f
                io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
                check(io.fonts.build())
                io.fonts.setTexID(ImTextureID(0uL))

                val tree = ExplorerWidget(root.absolutePath) { }
                fun frame() {
                    ImGui.newFrame()
                    ImGui.begin("##tree")
                    tree.draw()
                    ImGui.end()
                    ImGui.render()
                }

                // Expand All opens every folder, drawn or not.
                tree.expandAll()
                frame()
                frame()
                assertEquals(
                    setOf(root.absolutePath, a.absolutePath, File(a, "nested").absolutePath, b.absolutePath),
                    tree.openFolders,
                    "expand-all should open every folder",
                )

                // Collapse All closes every one of them, not just the root.
                tree.collapseAll()
                frame()
                assertTrue(tree.openFolders.isEmpty(), "collapse-all left ${tree.openFolders} open")

                // Re-expanding a folder must show it alone: nothing else may
                // come back, which is what the stale ImGui state used to do.
                tree.expandAll()
                frame()
                frame()
                tree.collapseAll()
                frame()
                tree.reveal(File(a, "one.txt").absolutePath)
                frame()
                frame()
                val revealed = File(a, "one.txt").absolutePath
                assertEquals(
                    setOf(root.absolutePath, a.absolutePath),
                    tree.openFolders,
                    "reveal should open its own path only",
                )
                assertEquals(revealed, tree.selected, "reveal should select the file it locates")
            } finally {
                ImGui.destroyContext(ctx)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun locateSelectsTheActiveFile() {
        val root = File.createTempFile("imcode-tree", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        try {
            val a = File(root, "alpha").apply { mkdirs() }
            val b = File(root, "beta").apply { mkdirs() }
            File(a, "one.txt").writeText("one")
            // Tall enough that the last entry is below the fold.
            repeat(80) { i -> File(b, "f%02d.txt".format(i)).writeText("x") }

            val ctx = ImGui.createContext()
            try {
                val io = ImGui.getIO()
                io.displaySize = ImVec2(800f, 600f)
                io.deltaTime = 1f / 60f
                io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
                check(io.fonts.build())
                io.fonts.setTexID(ImTextureID(0uL))

                val tree = ExplorerWidget(root.absolutePath) { }
                var scrollY = 0f
                fun frame() {
                    ImGui.newFrame()
                    ImGui.begin("##tree")
                    // Read inside the tree's window; the scroll target set while
                    // drawing is applied by the frame that follows.
                    scrollY = ImGui.getScrollY()
                    tree.draw()
                    ImGui.end()
                    ImGui.render()
                }

                // The toolbar button and the context menu item both run this:
                // locating the active file has to make it the selected node
                // and scroll it into view, not only expand the branch to it.
                val target = File(b, "f79.txt").absolutePath
                tree.lastReveal = target
                tree.locateActive()
                frame()
                frame()
                assertEquals(target, tree.selected, "locate should make the active file the selected node")
                assertEquals(
                    setOf(root.absolutePath, b.absolutePath),
                    tree.openFolders,
                    "locate should open the path to the active file",
                )
                assertTrue(scrollY > 0f, "locate should scroll the located file into view (scrollY=$scrollY)")
            } finally {
                ImGui.destroyContext(ctx)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
