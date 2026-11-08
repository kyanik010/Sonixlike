package com.example

import com.example.model.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RadioStationRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repository: RadioStationRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("radio_repo_test").toFile()
        repository = RadioStationRepository(tempDir)
    }

    @Test
    fun accountKey_isDeterministicAndDoesNotContainCredentials() {
        val server = "http://iptv.provider.com:8080"
        val user = "premium_user"
        val key = repository.accountKey(server, user)

        // 64 hex characters (SHA-256)
        assertEquals(64, key.length)
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertFalse(key.contains("user"))
        assertFalse(key.contains("iptv"))

        val secondKey = repository.accountKey(server, user)
        assertEquals(key, secondKey)

        val diffKey = repository.accountKey(server, "other_user")
        assertNotEquals(key, diffKey)
    }

    @Test
    fun multiAccountIsolation_storesAndLoadsSeparately() {
        val serverA = "http://server-a.com"
        val userA = "user_a"
        val stationsA = listOf(
            RadioStation("1", "ALWAN 1", "صوتيات ألوان", "ALWAN"),
            RadioStation("2", "beIN SPORTS 1", "beIN Audio", "BEIN_SPORTS")
        )

        val serverB = "http://server-b.com"
        val userB = "user_b"
        val stationsB = listOf(
            RadioStation("99", "beIN SPORTS MAX", "beIN Feeds", "BEIN_SPORTS")
        )

        repository.saveStations(serverA, userA, stationsA)
        repository.saveStations(serverB, userB, stationsB)

        // Account A stations
        val loadedA = repository.loadStations(serverA, userA)
        assertEquals(2, loadedA.size)
        assertEquals("ALWAN 1", loadedA[0].name)
        assertEquals("beIN SPORTS 1", loadedA[1].name)

        // Account B stations
        val loadedB = repository.loadStations(serverB, userB)
        assertEquals(1, loadedB.size)
        assertEquals("beIN SPORTS MAX", loadedB[0].name)

        // Clear Account A only
        repository.clearAccount(serverA, userA)
        assertTrue(repository.loadStations(serverA, userA).isEmpty())
        assertEquals(1, repository.loadStations(serverB, userB).size)

        // Clear all
        repository.clearAll()
        assertTrue(repository.loadStations(serverB, userB).isEmpty())
    }

    @Test
    fun serializationAndDeserialization_preservesSpecialCharacters() {
        val server = "http://server-test.com"
        val user = "test_user"
        val stations = listOf(
            RadioStation(
                id = "555",
                name = "ALWAN 1 \"VIP\" & Special",
                categoryName = "صوتيات ألوان مباشرة",
                group = "ALWAN",
                containerExtension = "ts",
                number = 1
            )
        )

        repository.saveStations(server, user, stations)
        // Clear memory cache by creating fresh repository instance pointing to the same folder
        val freshRepo = RadioStationRepository(tempDir)
        val loaded = freshRepo.loadStations(server, user)

        assertEquals(1, loaded.size)
        assertEquals("555", loaded[0].id)
        assertEquals("ALWAN 1 \"VIP\" & Special", loaded[0].name)
        assertEquals("صوتيات ألوان مباشرة", loaded[0].categoryName)
        assertEquals("ALWAN", loaded[0].group)
    }
}
