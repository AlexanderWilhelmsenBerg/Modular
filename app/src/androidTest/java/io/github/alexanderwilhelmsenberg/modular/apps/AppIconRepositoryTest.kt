package io.github.alexanderwilhelmsenberg.modular.apps

import android.content.Context
import android.os.Process
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import io.github.alexanderwilhelmsenberg.modular.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AppIconRepositoryTest {
    @Test
    fun iconUsesExactActivityAndProfileAndCachesResult() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serial = context.getSystemService(UserManager::class.java)
            .getSerialNumberForUser(Process.myUserHandle())
        val key = AppKey(context.packageName, MainActivity::class.java.name, serial)
        val repository = AppIconRepository.shared(context)

        withTimeout(10_000) {
            val first = withContext(Dispatchers.Main) { repository.load(key, 320, 96, 1) }
            assertNotNull(first)
            val cached = repository.load(key, 320, 96, 1)
            assertSame(first, cached)
            assertNull(repository.load(key.copy(activityName = "missing.Activity"), 320, 96, 1))
            assertNull(repository.load(key.copy(profileSerial = Long.MAX_VALUE), 320, 96, 1))
            assertNotNull(repository.load(key, 320, 96, 2))
        }
    }
}
