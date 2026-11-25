package dev.aigw.sandbox.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AigwProjectSettingsTest {
    @Test
    fun defaultsSandboxNameFromDir() {
        assertEquals("ug-claude-myproject", AigwProjectSettings.effectiveSandboxName("", "myproject"))
        assertEquals("ug-claude-myproject", AigwProjectSettings.effectiveSandboxName("   ", "myproject"))
        assertEquals("custom", AigwProjectSettings.effectiveSandboxName(" custom ", "myproject"))
    }

    @Test
    fun effectiveEnvFilesDefaultsWhenEmpty() {
        assertEquals(listOf("sbxenv.yaml"), AigwProjectSettings.effectiveEnvFiles(emptyList()))
        assertEquals(listOf("sbxenv.yaml"), AigwProjectSettings.effectiveEnvFiles(listOf("", "  ")))
    }

    @Test
    fun effectiveEnvFilesKeepsConfigured() {
        assertEquals(listOf("a.yaml", "b.yaml"), AigwProjectSettings.effectiveEnvFiles(listOf("a.yaml", "b.yaml")))
    }

    @Test
    fun newProjectStateHasEmptyEnvFilesAndDefaultEffective() {
        val state = ProjectState()
        assertEquals(emptyList<String>(), state.envFiles.toList())
        assertEquals(listOf("sbxenv.yaml"), AigwProjectSettings.effectiveEnvFiles(state.envFiles))
    }

    @Test
    fun effectivePortFallsBack() {
        assertEquals(8020, AigwAppSettings.effectivePort(0))
        assertEquals(8020, AigwAppSettings.effectivePort(70000))
        assertEquals(9000, AigwAppSettings.effectivePort(9000))
    }
}
