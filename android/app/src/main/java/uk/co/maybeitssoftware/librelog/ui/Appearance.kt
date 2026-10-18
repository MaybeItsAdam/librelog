package uk.co.maybeitssoftware.librelog.ui

/**
 * Settings → Appearance (docs/appearance.md): synced settings, so the choice
 * follows the user to every device. An unset or unknown value is the default.
 */
data class Appearance(
    val theme: Theme = Theme.System,
    val accent: Accent = Accent.Azure,
    val font: ReadingFont = ReadingFont.Arvo,
    val textSize: TextSize = TextSize.Default,
) {
    enum class Theme(val key: String, val title: String) {
        System("system", "System"),
        Light("light", "Light"),
        Dark("dark", "Dark"),
    }

    /** The primary role's hue. Status colours keep their own whatever this is. */
    enum class Accent(val key: String, val title: String, val hex: Long) {
        Azure("azure", "Azure", Palette.AZURE),
        Emerald("emerald", "Emerald", Palette.EMERALD),
        Raspberry("raspberry", "Raspberry", Palette.RASPBERRY),
        Amber("amber", "Amber", Palette.AMBER),
        Purple("purple", "Purple", Palette.PURPLE),
        Pink("pink", "Pink", Palette.PINK),
        Orange("orange", "Orange", Palette.ORANGE);

        /** Amber is light: text and icons on it take the ink colour, not white. */
        val inkOnFill: Boolean get() = this == Amber
    }

    /** The reading and writing face: entries, titles and the editor. */
    enum class ReadingFont(val key: String, val title: String) {
        Arvo("arvo", "Arvo"),
        Sans("sans", "Sans"),
        Serif("serif", "Serif"),
        Mono("mono", "Mono"),
    }

    /** On top of the system font scale, never instead of it. */
    enum class TextSize(val key: String, val title: String, val scale: Float) {
        Small("small", "Small", 0.9f),
        Default("default", "Default", 1f),
        Large("large", "Large", 1.15f),
        Larger("larger", "Larger", 1.3f),
    }

    companion object {
        const val THEME = "appearance.theme"
        const val ACCENT = "appearance.accent"
        const val FONT = "appearance.font"
        const val TEXT_SIZE = "appearance.text_size"
        val KEYS = listOf(THEME, ACCENT, FONT, TEXT_SIZE)

        /** Builds the appearance from the stored values; [read] returns null when unset. */
        fun from(read: (String) -> String?): Appearance = Appearance(
            theme = Theme.entries.firstOrNull { it.key == read(THEME) } ?: Theme.System,
            accent = Accent.entries.firstOrNull { it.key == read(ACCENT) } ?: Accent.Azure,
            font = ReadingFont.entries.firstOrNull { it.key == read(FONT) } ?: ReadingFont.Arvo,
            textSize = TextSize.entries.firstOrNull { it.key == read(TEXT_SIZE) } ?: TextSize.Default,
        )
    }
}
