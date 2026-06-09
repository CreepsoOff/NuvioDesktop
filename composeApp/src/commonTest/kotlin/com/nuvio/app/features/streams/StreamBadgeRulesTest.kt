package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamBadgeRulesTest {
    @Test
    fun `normalization trims dedupes limits imports and keeps one active source`() {
        val rules = StreamBadgeRules(
            imports = listOf(
                badgeImport(sourceUrl = " https://example.test/one.json ", name = "ONE", active = false),
                badgeImport(sourceUrl = "https://example.test/two.json", name = "TWO", active = true),
                badgeImport(sourceUrl = "https://EXAMPLE.test/one.json", name = "ONE-REPLACED", active = false),
                badgeImport(sourceUrl = "https://example.test/three.json", name = "THREE", active = true),
                badgeImport(sourceUrl = "https://example.test/four.json", name = "FOUR", active = false),
                badgeImport(sourceUrl = " ", name = "BLANK", active = true),
                badgeImport(sourceUrl = "https://example.test/empty.json", filters = emptyList(), active = true),
            ),
        ).normalized()

        assertEquals(
            listOf(
                "https://EXAMPLE.test/one.json",
                "https://example.test/two.json",
                "https://example.test/three.json",
            ),
            rules.imports.map { it.sourceUrl },
        )
        assertEquals(listOf("ONE-REPLACED", "TWO", "THREE"), rules.imports.map { it.filters.single().name })
        assertEquals(listOf(false, true, false), rules.imports.map { it.isActive })
    }

    @Test
    fun `upsert activates replacement and deactivates previous active import`() {
        val original = StreamBadgeRules(
            imports = listOf(
                badgeImport(sourceUrl = "https://example.test/one.json", name = "ONE", active = true),
                badgeImport(sourceUrl = "https://example.test/two.json", name = "TWO", active = false),
            ),
        )

        val updated = original.upsert(
            badgeImport(sourceUrl = "https://example.test/two.json", name = "TWO-UPDATED", active = false),
            activate = true,
        )

        assertEquals(listOf(false, true), updated.imports.map { it.isActive })
        assertEquals("TWO-UPDATED", updated.activeImport?.filters?.single()?.name)
    }

    @Test
    fun `remove active source promotes first remaining import`() {
        val rules = StreamBadgeRules(
            imports = listOf(
                badgeImport(sourceUrl = "https://example.test/one.json", name = "ONE", active = false),
                badgeImport(sourceUrl = "https://example.test/two.json", name = "TWO", active = true),
                badgeImport(sourceUrl = "https://example.test/three.json", name = "THREE", active = false),
            ),
        )

        val updated = rules.removeSource("https://example.test/two.json")

        assertEquals(listOf("https://example.test/one.json", "https://example.test/three.json"), updated.imports.map { it.sourceUrl })
        assertEquals("https://example.test/one.json", updated.activeImport?.sourceUrl)
    }

    @Test
    fun `matcher ignores disabled invalid and duplicate badge filters`() {
        val rules = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.test/badges.json",
                    filters = listOf(
                        StreamBadgeFilter(name = "WEB", pattern = "(?i)web-dl", imageURL = "https://example.test/web.png"),
                        StreamBadgeFilter(name = "WEB-DUPE", pattern = "(?i)web", imageURL = "https://example.test/web.png"),
                        StreamBadgeFilter(name = "DISABLED", pattern = "(?i)web", isEnabled = false),
                        StreamBadgeFilter(name = "INVALID", pattern = "["),
                    ),
                ),
            ),
        )

        val stream = StreamItem(
            name = "Movie.2026.1080p.WEB-DL-GRP",
            addonName = "Addon",
            addonId = "addon",
        )

        val matched = StreamBadgeMatcher.matchedBadges(stream, StreamBadgeMatcher.compile(rules))

        assertEquals(1, matched.size)
        assertEquals("WEB", matched.single().name)
        assertFalse(matched.any { it.name == "DISABLED" })
        assertFalse(matched.any { it.name == "INVALID" })
    }

    @Test
    fun `badge candidates combine parsed and source fields for matching`() {
        val stream = StreamItem(
            name = "Fallback title",
            title = "Visible title",
            sourceName = "PluginSource",
            addonName = "AddonName",
            addonId = "addon",
            behaviorHints = StreamBehaviorHints(filename = "Movie.2026.2160p.REMUX.TrueHD-GRP.mkv"),
        )

        val candidates = StreamBadgeMatcher.badgeMatchCandidates(stream)

        assertTrue(candidates.any { it.contains("REMUX") })
        assertTrue(candidates.any { it.contains("TrueHD") })
        assertTrue(candidates.any { it.contains("PluginSource") })
        assertTrue(candidates.any { it.contains("AddonName") })
        assertTrue(candidates.any { it.contains("REMUX") && it.contains("PluginSource") })
    }

    @Test
    fun `parser keeps usable filters and groups while rejecting empty imports`() {
        val payload = """
            {
              "filters": [
                {
                  "id": "web",
                  "groupId": "quality",
                  "name": " WEB ",
                  "pattern": " (?i)web-dl ",
                  "imageURL": "https://example.test/web.png",
                  "isEnabled": false,
                  "tagColor": "#00ff00"
                },
                {
                  "name": "   ",
                  "pattern": "(?i)ignored"
                },
                {
                  "name": "REMUX",
                  "pattern": "   "
                }
              ],
              "groups": [
                {
                  "id": "quality",
                  "name": "Quality",
                  "color": "#333333",
                  "isExpanded": false
                }
              ],
              "unknown": "ignored"
            }
        """.trimIndent()

        val import = StreamBadgeRulesParser.parse(" https://example.test/badges.json ", payload)

        assertEquals("https://example.test/badges.json", import.sourceUrl)
        assertEquals(1, import.filters.size)
        assertEquals("WEB", import.filters.single().name)
        assertEquals("(?i)web-dl", import.filters.single().pattern)
        assertFalse(import.filters.single().isEnabled)
        assertEquals(1, import.groups.size)
        assertEquals("quality", import.groups.single().id)
        assertFalse(import.groups.single().isExpanded)

        val error = kotlin.runCatching {
            StreamBadgeRulesParser.parse(
                sourceUrl = "https://example.test/empty.json",
                payload = """{"filters":[{"name":"","pattern":"(?i)web"}]}""",
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message.orEmpty().contains("usable filters"))
    }

    private fun badgeImport(
        sourceUrl: String,
        name: String = "BADGE",
        filters: List<StreamBadgeFilter> = listOf(StreamBadgeFilter(name = name, pattern = "(?i)$name")),
        active: Boolean,
    ): StreamBadgeImport =
        StreamBadgeImport(
            sourceUrl = sourceUrl,
            filters = filters,
            isActive = active,
        )
}
