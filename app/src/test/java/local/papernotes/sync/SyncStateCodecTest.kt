package local.papernotes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncStateCodecTest {
    @Test
    fun roundTrip() {
        val st = SyncState(
            "https://c|u|PaperNotes",
            setOf("A", "A/B", "Open Shift"),
            mapOf(
                "11111111-1111-4111-8111-111111111111" to SyncedFile("A/B", 123, 1_700_000_000_000),
                "22222222-2222-4222-8222-222222222222" to SyncedFile("", 0, 0),
            ),
        )
        assertEquals(st, SyncStateCodec.fromJson(SyncStateCodec.toJson(st)))
        assertEquals(SyncState(), SyncStateCodec.fromJson(SyncStateCodec.toJson(SyncState())))
    }

    @Test
    fun brokenInputIsNull() {
        listOf(
            null, "", "  ", "nix", "[]", "{}", """{"version":2,"scope":"","folders":[],"files":{}}""",
            """{"version":1,"scope":"s","folders":"x","files":{}}""",
            """{"version":1,"scope":"s","folders":[],"files":{"a":{"f":"","s":"x","m":1}}}""",
            """{"version":1,"scope":"s","folders":[],"files":{"a":{"f":""}}}""",
        ).forEach { assertNull("sollte null sein: $it", SyncStateCodec.fromJson(it)) }
    }
}
