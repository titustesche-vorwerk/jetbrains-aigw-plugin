package dev.aigw.sandbox.sbx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvConfigTest {
    private fun fixture(name: String): String =
        EnvConfigTest::class.java.getResource("/fixtures/$name")!!.readText()

    private val ws = "https://example.cloud.databricks.com"
    private val files = listOf("sbxenv.yaml", "sandbox_config/sbxenv.ug-claude.yaml")

    @Test
    fun readsArgsBlockOfProjectEnv() {
        assertEquals(setOf("agent", "sandbox"), declaredArgs(fixture("sbxenv.yaml")))
        assertEquals(setOf("ugWorkspace"), declaredArgs(fixture("sbxenv.ug-claude.yaml")))
        assertEquals(emptySet<String>(), declaredArgs("name: x\nenv:\n  A: b\n"))
        assertEquals(emptySet<String>(), declaredArgs("args: {}\nname: x\n"))
        assertEquals(emptySet<String>(), declaredArgs("args:\nname: x\n"))
    }

    @Test
    fun commentsAndBlankLinesDoNotEndBlock() {
        val y = "args:\n  a:\n    default: 1\n# col0 comment\n\n  # indented comment\n  b:\n    x: y\nname: z\n  c: no\n"
        assertEquals(setOf("a", "b"), declaredArgs(y))
    }

    @Test
    fun addsSandboxArgWhenDeclared() {
        val argv = envArgv("run", files, "ug-claude-myproject", listOf("ugWorkspace=$ws"),
            setOf("agent", "sandbox", "ugWorkspace"), detach = true)
        assertEquals(
            listOf("env", "run", "--detach", "sbxenv.yaml", "sandbox_config/sbxenv.ug-claude.yaml",
                "--name", "ug-claude-myproject", "--env-arg", "ugWorkspace=$ws",
                "--env-arg", "sandbox=ug-claude-myproject"),
            argv,
        )
    }

    @Test
    fun noInputIsGlobalFlagBeforeEnv() {
        val argv = envArgv("run", files, "n", emptyList(), emptySet(), detach = true, noInput = true)
        assertEquals(listOf("--no-input", "env", "run", "--detach"), argv.take(4))
    }

    @Test
    fun doesNotAddSandboxWhenNotDeclared() {
        val argv = envArgv("run", files, "n", emptyList(), setOf("agent"))
        assertFalse(argv.any { it.startsWith("sandbox=") })
    }

    @Test
    fun doesNotDuplicateExplicitSandboxArg() {
        val argv = envArgv("run", files, "n", listOf("sandbox=custom"), setOf("sandbox"))
        assertEquals(listOf("sandbox=custom"), argv.filter { it.startsWith("sandbox=") })
    }

    @Test
    fun rmVerbHasNoDetach() {
        val argv = envArgv("rm", files, "n", emptyList(), setOf("sandbox"), detach = true, force = true)
        assertFalse("--detach" in argv)
        assertEquals("rm", argv[1])
    }

    @Test
    fun rmWithForceEmitsForce() {
        assertEquals(
            listOf("env", "rm", "sbxenv.yaml", "--name", "n", "--force", "--env-arg", "a=b"),
            envArgv("rm", listOf("sbxenv.yaml"), "n", listOf("a=b"), emptySet(), force = true),
        )
    }

    @Test
    fun forceAppliesToRunAndBlanksDropped() {
        val argv = envArgv("run", listOf("a.yaml", " ", ""), "n", listOf("", "k=v"), emptySet(), force = true)
        assertEquals(listOf("env", "run", "a.yaml", "--name", "n", "--force", "--env-arg", "k=v"), argv)
        assertTrue(argv.none { it == "sbx" })
    }

    // ---- finding 9: ugWorkspace prefilled from the workspace host ----

    @Test
    fun addsUgWorkspaceFromHostWhenDeclaredAndUnset() {
        val argv = envArgv("run", files, "n", emptyList(), setOf("sandbox", "ugWorkspace"), workspaceHost = "example.cloud.databricks.com")
        assertEquals(listOf("sandbox=n", "ugWorkspace=$ws"), argv.filterIndexed { i, _ -> i > 0 && argv[i - 1] == "--env-arg" })
    }

    @Test
    fun ugWorkspaceKeepsSchemeAndDropsTrailingSlash() {
        val argv = envArgv("run", files, "n", emptyList(), setOf("ugWorkspace"), workspaceHost = " $ws/ ")
        assertEquals(listOf("--env-arg", "ugWorkspace=$ws"), argv.takeLast(2))
    }

    @Test
    fun explicitUgWorkspaceWins() {
        val argv = envArgv("run", files, "n", listOf("ugWorkspace=https://other"), setOf("ugWorkspace"), workspaceHost = ws)
        assertEquals(listOf("ugWorkspace=https://other"), argv.filter { it.startsWith("ugWorkspace=") })
    }

    @Test
    fun noUgWorkspaceWhenNotDeclaredOrNoHost() {
        assertFalse(envArgv("run", files, "n", emptyList(), setOf("sandbox"), workspaceHost = ws).any { it.startsWith("ugWorkspace=") })
        assertFalse(envArgv("run", files, "n", emptyList(), setOf("ugWorkspace"), workspaceHost = " ").any { it.startsWith("ugWorkspace=") })
        assertFalse(envArgv("run", files, "n", emptyList(), setOf("ugWorkspace")).any { it.startsWith("ugWorkspace=") })
    }
}
