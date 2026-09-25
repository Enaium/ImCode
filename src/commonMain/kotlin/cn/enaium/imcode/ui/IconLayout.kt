package cn.enaium.imcode.ui

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import cn.enaium.xicons.imgui.Icon

/**
 * Metrics shared by every icon gutter this UI draws (explorer rows, tab
 * strip, completion popup).
 *
 * All of it derives from the active font rather than fixed pixel values: the
 * host scales its font, and icons that keep a hardcoded size then look wrong
 * next to the text — too small, and vertically off by the difference.
 */
internal object IconLayout {

    /** Space between an icon and the text it labels. */
    private const val GAP = 4f

    /**
     * A square button showing [icon] instead of a label — the toolbar idiom
     * for actions whose text is long and whose meaning the icon carries.
     * [tooltip] names the action on hover; a disabled button greys out and
     * does not click.
     *
     * Deliberately does not call `sameLine`: the caller decides how the row
     * continues (a trailing `sameLine` would pull a separator onto the row).
     */
    fun iconButton(
        id: String,
        icon: Icon,
        tooltip: String? = null,
        enabled: Boolean = true,
    ): Boolean {
        val edge = size()
        val side = ImGui.getFrameHeight()
        if (!enabled) ImGui.beginDisabled()
        val clicked = ImGui.button("##$id", ImVec2(side, side))
        val min = ImGui.getItemRectMin()
        val max = ImGui.getItemRectMax()
        icon.draw(
            ImVec2(min.x + (max.x - min.x - edge) / 2f, min.y + (max.y - min.y - edge) / 2f),
            edge,
        )
        if (!enabled) ImGui.endDisabled()
        if (tooltip != null && ImGui.isItemHovered()) ImGui.setTooltip(tooltip)
        return clicked
    }

    /** Edge length of the icons for the active font. */
    fun size(): Float = ImGui.getTextLineHeight()

    /** Horizontal space a row reserves for its icon plus [GAP]. */
    fun gutter(): Float = size() + GAP

    /** [width] as whole spaces of the active font; the label's indent. */
    fun spacesFor(width: Float): String {
        val spaceW = ImGui.calcTextSize(" ").x
        if (spaceW <= 0f) return "    "
        return " ".repeat((width / spaceW).toInt().coerceAtLeast(1))
    }

    /**
     * Draws [icon] vertically centered in a row spanning [min]..[max].
     *
     * [xOffset] pushes it past leading chrome the row owns itself — the tree's
     * collapsing arrow — so every gutter sits the same distance from its text.
     */
    fun drawCentered(icon: Icon, min: ImVec2, max: ImVec2, xOffset: Float = 0f) {
        val edge = size()
        icon.draw(
            ImVec2(min.x + xOffset + GAP * 0.5f, min.y + (max.y - min.y - edge) / 2f),
            edge,
        )
    }

    /**
     * Where a tree node's label starts, relative to the node's rect: ImGui
     * draws the collapsing arrow in that leading strip (`fontSize +
     * 2 * framePadding`, which is what `frameHeight` evaluates to under the
     * default style). Icons must start past it or they sit on the arrow.
     */
    fun treeLabelOffset(): Float = ImGui.getFrameHeight()
}
