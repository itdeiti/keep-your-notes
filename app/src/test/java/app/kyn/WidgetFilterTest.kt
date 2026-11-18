package app.kyn

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetFilterTest {
    private fun note(path: String, body: String = "", tags: String? = null) =
        Note(path, listOfNotNull(tags?.let { "tags: [$it]" }), body, 0)

    private val notes = listOf(
        note("Einkauf.md", "- [ ] Milch"),
        note("Arbeit/Plan.md", "Text"),
        note("Arbeit/Kunde/Notiz.md", "- [x] erledigt"),
        note("Arbeitszeit/Log.md", "", tags = "privat"),
        note("inbox/Idee.md", "#idee etwas"),
    )

    private fun paths(f: WidgetFilter) = f.apply(notes).map { it.path }

    @Test fun noSelectionShowsAll() {
        assertEquals(notes.size, paths(WidgetFilter()).size)
    }

    @Test fun folderIncludesSubfoldersButNotPrefixSiblings() {
        assertEquals(listOf("Arbeit/Plan.md", "Arbeit/Kunde/Notiz.md"), paths(WidgetFilter(folders = setOf("Arbeit"))))
    }

    @Test fun rootFolderMeansOnlyTopLevelNotes() {
        assertEquals(listOf("Einkauf.md"), paths(WidgetFilter(folders = setOf(WidgetFilter.ROOT))))
    }

    @Test fun selectionsAreCombinedWithOr() {
        val f = WidgetFilter(folders = setOf("inbox"), tags = setOf("privat"), notes = setOf("Einkauf.md"))
        assertEquals(listOf("Einkauf.md", "Arbeitszeit/Log.md", "inbox/Idee.md"), paths(f))
    }

    @Test fun inlineTagCounts() {
        assertEquals(listOf("inbox/Idee.md"), paths(WidgetFilter(tags = setOf("idee"))))
    }

    @Test fun taskModesDropNotesWithoutTasks() {
        assertEquals(listOf("Einkauf.md", "Arbeit/Kunde/Notiz.md"), paths(WidgetFilter(content = WidgetFilter.Content.Tasks)))
        assertEquals(listOf("Einkauf.md"), paths(WidgetFilter(content = WidgetFilter.Content.OpenTasks)))
    }
}
