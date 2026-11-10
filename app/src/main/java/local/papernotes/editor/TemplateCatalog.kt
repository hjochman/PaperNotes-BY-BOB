package local.papernotes.editor

/** Gruppen im Vorlagendialog (Reihenfolge = Reihenfolge im Dialog). */
enum class TemplateGroup { BASIC, WRITING, PLANS, WORK }

/**
 * Eine Seitenvorlage. [id] ist der gespeicherte `Page.template`-Wert (Muster `[a-z0-9_]{1,32}`) und darf sich nie
 * ändern. Anzeigenamen stehen in den Android-Ressourcen (siehe `TemplateText`), damit dieser Katalog ohne Android testbar bleibt.
 */
data class TemplateSpec(val id: String, val group: TemplateGroup)

/** Die EINZIGE Tabelle der Vorlagen. */
object TemplateCatalog {
    const val DEFAULT_ID = "blank"

    val all: List<TemplateSpec> = listOf(
        TemplateSpec("blank", TemplateGroup.BASIC),
        TemplateSpec("lines_wide", TemplateGroup.BASIC),
        TemplateSpec("lines_narrow", TemplateGroup.BASIC),
        TemplateSpec("dotted", TemplateGroup.BASIC),
        TemplateSpec("handwriting", TemplateGroup.WRITING),
        TemplateSpec("millimeter", TemplateGroup.WRITING),
        TemplateSpec("grid", TemplateGroup.WRITING),
        TemplateSpec("letter", TemplateGroup.WRITING),
        TemplateSpec("character_grid", TemplateGroup.WRITING),
        TemplateSpec("staff", TemplateGroup.WRITING),
        TemplateSpec("todo", TemplateGroup.PLANS),
        TemplateSpec("meeting", TemplateGroup.PLANS),
        TemplateSpec("weekly", TemplateGroup.PLANS),
        TemplateSpec("monthly", TemplateGroup.PLANS),
        TemplateSpec("time_management", TemplateGroup.PLANS),
        TemplateSpec("tasks", TemplateGroup.WORK),
        TemplateSpec("error_log", TemplateGroup.WORK),
        TemplateSpec("reading_notes", TemplateGroup.WORK),
        TemplateSpec("cornell", TemplateGroup.WORK),
        TemplateSpec("point_sheet", TemplateGroup.WORK),
        TemplateSpec("swot", TemplateGroup.WORK),
    )

    val groups: List<TemplateGroup> = TemplateGroup.values().toList()

    private val byId: Map<String, TemplateSpec> = all.associateBy { it.id }

    fun inGroup(group: TemplateGroup): List<TemplateSpec> = all.filter { it.group == group }

    fun isKnown(id: String): Boolean = id in byId

    /** Unbekannte IDs (z. B. Notizen aus späteren Versionen) fallen auf "blank" zurück. */
    fun lookup(id: String): TemplateSpec = byId[id] ?: byId.getValue(DEFAULT_ID)
}
