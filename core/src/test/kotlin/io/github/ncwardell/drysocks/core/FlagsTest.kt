package io.github.ncwardell.drysocks.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FlagsTest {
    @Test
    fun `codes become flags, and the rest a globe`() {
        assertThat(flag("DE")).isEqualTo("🇩🇪")
        assertThat(flag("nl")).isEqualTo("🇳🇱")
        assertThat(flag("ZZ")).isEqualTo("🌐")
        assertThat(flag("EU")).isEqualTo("🌐")
        assertThat(flag(null)).isEqualTo("🌐")
        assertThat(flag("USA")).isEqualTo("🌐")
    }
}
