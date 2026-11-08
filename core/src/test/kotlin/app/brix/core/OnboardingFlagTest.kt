package app.brix.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * First-run wizard completion flag (16.09).
 *
 * One error shows the wizard to a user with a configured stream; the opposite
 * hides it from newcomers. Test with real JSON: the scheme relies on kotlinx
 * omitting default values.
 */
class OnboardingFlagTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun roundTrip(s: AppSettings): AppSettings =
        json.decodeFromString<AppSettings>(json.encodeToString(s)).migrate()

    @Test
    fun `файл до появления мастера считается пройденным`() {
        val old = json.decodeFromString<AppSettings>("""{"serverProfiles":[]}""")
        assertEquals(true, old.migrate().onboardingDone)
    }

    @Test
    fun `свежая установка мастер ещё не прошла и это переживает запись`() {
        assertEquals(false, roundTrip(defaultAppSettings()).onboardingDone)
    }

    @Test
    fun `пройденный мастер остаётся пройденным`() {
        val done = defaultAppSettings().copy(onboardingDone = true)
        assertEquals(true, roundTrip(done).onboardingDone)
    }
}
