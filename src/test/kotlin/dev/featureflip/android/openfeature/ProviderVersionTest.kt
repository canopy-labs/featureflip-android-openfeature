package dev.featureflip.android.openfeature

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderVersionTest {
    @Test
    fun `generated version is the project version`() {
        assertThat(PROVIDER_VERSION).isEqualTo(System.getProperty("featureflip.providerVersion"))
        assertThat(PROVIDER_VERSION).matches("\\d+\\.\\d+\\.\\d+")
    }
}
