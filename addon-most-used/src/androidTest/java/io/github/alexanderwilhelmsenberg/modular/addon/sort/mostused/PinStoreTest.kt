package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PinStoreTest {
    private val first = AppKeyParcel("example.app", "example.app.First", 10)
    private val second = AppKeyParcel("example.app", "example.app.First", 20)

    @Test fun persistsOrderRevisionAndExactProfilesByInstance() {
        val directory = freshDirectory()
        try {
            val store = PinStore(directory)
            val saved = PinState(3, listOf(second, first))
            store.write("home-one", saved)
            assertEquals(saved, PinStore(directory).read("home-one"))
            assertEquals(PinState(), store.read("home-two"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun malformedFileDoesNotBecomeAnEmptyPinList() {
        val directory = freshDirectory()
        try {
            val store = PinStore(directory)
            store.write("home", PinState(1, listOf(first)))
            val savedFile = directory.resolve("featured_pins").listFiles()!!.single()
            savedFile.writeText("{broken")
            assertThrows(IOException::class.java) { store.read("home") }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun freshDirectory(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cache, "pin-store-test-${System.nanoTime()}").apply { mkdirs() }
    }
}
