package dev.sam.historian

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {

    // --- parsing ---

    @Test
    fun `parses plain key value pairs`() {
        val parsed = Settings.parse("GOOGLE_API_KEY=abc123\nHISTORIAN_REPO=/tmp/repo")

        assertEquals(mapOf("GOOGLE_API_KEY" to "abc123", "HISTORIAN_REPO" to "/tmp/repo"), parsed)
    }

    @Test
    fun `ignores blank lines and comments`() {
        val parsed = Settings.parse(
            """
            # a comment

            GOOGLE_API_KEY=abc123
               # an indented comment
            """.trimIndent()
        )

        assertEquals(mapOf("GOOGLE_API_KEY" to "abc123"), parsed)
    }

    @Test
    fun `tolerates an export prefix so a sourceable file also parses`() {
        assertEquals(mapOf("KEY" to "v"), Settings.parse("export KEY=v"))
    }

    @Test
    fun `strips matching quotes`() {
        assertEquals(mapOf("K" to "a b"), Settings.parse("""K="a b""""))
        assertEquals(mapOf("K" to "a b"), Settings.parse("K='a b'"))
    }

    @Test
    fun `keeps a hash inside a quoted value but drops a trailing comment otherwise`() {
        assertEquals(mapOf("K" to "pa#ss"), Settings.parse("""K="pa#ss""""))
        assertEquals(mapOf("K" to "value"), Settings.parse("K=value # trailing note"))
    }

    @Test
    fun `keeps an equals sign inside the value`() {
        assertEquals(mapOf("K" to "a=b=c"), Settings.parse("K=a=b=c"))
    }

    @Test
    fun `allows an empty value`() {
        assertEquals(mapOf("K" to ""), Settings.parse("K="))
    }

    @Test
    fun `skips malformed lines instead of failing`() {
        val parsed = Settings.parse("no equals sign here\n=novalue\nBAD KEY=x\nGOOD=y")

        assertEquals(mapOf("GOOD" to "y"), parsed)
    }

    @Test
    fun `a later duplicate wins within one file`() {
        assertEquals(mapOf("K" to "second"), Settings.parse("K=first\nK=second"))
    }

    // --- precedence ---

    @Test
    fun `the real environment beats the file`() {
        val settings = Settings(
            fileValues = mapOf("GOOGLE_API_KEY" to "from-file"),
            system = { if (it == "GOOGLE_API_KEY") "from-env" else null },
        )

        assertEquals("from-env", settings["GOOGLE_API_KEY"])
    }

    @Test
    fun `the file is used when the environment has nothing`() {
        val settings = Settings(mapOf("GOOGLE_API_KEY" to "from-file")) { null }

        assertEquals("from-file", settings["GOOGLE_API_KEY"])
    }

    @Test
    fun `a blank environment value does not mask the file`() {
        val settings = Settings(mapOf("K" to "from-file")) { "   " }

        assertEquals("from-file", settings["K"])
    }

    @Test
    fun `an unknown key is null`() {
        assertNull(Settings()["NOPE"])
    }

    @Test
    fun `require explains how to fix a missing key`() {
        val error = assertFailsWith<IllegalStateException> {
            Settings { null }.require("GOOGLE_API_KEY", "Get a key from aistudio.")
        }

        assertTrue(error.message!!.contains("GOOGLE_API_KEY"))
        assertTrue(error.message!!.contains("Get a key from aistudio."))
    }

    // --- file discovery ---

    @Test
    fun `the nearest file wins over the fallback`() {
        val near = tempEnv("K=near\nONLY_FAR=")
        val far = tempEnv("K=far\nONLY_FAR=present")

        val settings = Settings.load(listOf(near, far))

        assertEquals("near", settings["K"])
        assertEquals("present", settings["ONLY_FAR"], "keys absent nearby still fall through")
    }

    @Test
    fun `missing files are skipped`() {
        val real = tempEnv("K=v")

        val settings = Settings.load(listOf(File("/no/such/.env"), real))

        assertEquals("v", settings["K"])
    }

    @Test
    fun `no files at all is not an error`() {
        assertNull(Settings.load(listOf(File("/no/such/.env")))["K"])
    }

    @Test
    fun `candidate paths cover the working directory and the config directory`() {
        val paths = Settings.candidatePaths(
            workingDir = File("/work"),
            home = File("/home/sam"),
            xdgConfigHome = null,
        )

        assertEquals(
            listOf("/work/.env", "/home/sam/.config/git-repo-historian/.env"),
            paths.map { it.path },
        )
    }

    @Test
    fun `XDG_CONFIG_HOME is honoured when set`() {
        val paths = Settings.candidatePaths(
            workingDir = File("/work"),
            home = File("/home/sam"),
            xdgConfigHome = "/custom/config",
        )

        assertEquals("/custom/config/git-repo-historian/.env", paths[1].path)
    }

    private fun tempEnv(contents: String): File {
        val dir = createTempDirectory("settings-fixture").toFile()
        return File(dir, ".env").apply { writeText(contents) }
    }
}
