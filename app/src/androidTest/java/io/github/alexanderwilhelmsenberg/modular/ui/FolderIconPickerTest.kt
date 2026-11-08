package io.github.alexanderwilhelmsenberg.modular.ui

import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.alexanderwilhelmsenberg.modular.organization.FolderDefinition
import io.github.alexanderwilhelmsenberg.modular.organization.OrganizationSnapshot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FolderIconPickerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun selectionSavesWithFolderAndCancelDiscardsDraft() {
        var saved = FolderDefinition("folder-1", "My folder")
        rule.setContent {
            var open by remember { mutableStateOf(true) }
            if (open) FolderEditor(
                folder = saved,
                isNew = false,
                snapshot = OrganizationSnapshot(folders = listOf(saved)),
                catalog = emptyList(),
                mostUsedEnabled = false,
                initialConditions = emptyList(),
                smartSettings = { _, _, _, _ -> },
                onSave = { folder, _ -> saved = folder; Result.success(Unit) },
                onDelete = null,
                onDismiss = { open = false },
            ) else TextButton(onClick = { open = true }, modifier = Modifier.testTag("reopen-folder-editor")) {
                Text("Edit folder")
            }
        }

        assertEquals("folder", FolderIcons.resolve("future-icon").id)
        rule.onNodeWithTag("folder-icon-choose").performScrollTo().performClick()
        capturePicker()
        rule.onNodeWithTag("folder-icon-option:music").performScrollTo().performClick()
        rule.onNodeWithTag("folder-icon-preview").assertTextEquals("Music")
        rule.onNodeWithTag("folder-save").performClick()
        rule.runOnIdle { assertEquals("music", saved.iconId) }

        rule.onNodeWithTag("reopen-folder-editor").performClick()
        rule.onNodeWithTag("folder-icon-choose").performScrollTo().performClick()
        rule.onNodeWithTag("folder-icon-option:music").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("folder-icon-option:games").performScrollTo().performClick()
        rule.onNodeWithTag("folder-icon-preview").assertTextEquals("Games")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("reopen-folder-editor").performClick()
        rule.onNodeWithTag("folder-icon-preview").assertTextEquals("Music")
        rule.runOnIdle { assertEquals("music", saved.iconId) }
    }

    private fun capturePicker() {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = context.resources.configuration
        val night = if ((config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            "dark" else "light"
        val font = String.format(Locale.ROOT, "%.1f", config.fontScale)
        val file = File(requireNotNull(context.getExternalFilesDir("organization-screenshots")),
            "organization-folder-icon-picker-${config.screenWidthDp}dp-$night-font$font.png")
        FileOutputStream(file).use { output ->
            check(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val automation = instrumentation.uiAutomation
        val destinationDir = "/sdcard/Pictures/ModularOrganization"
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val destination = "$destinationDir/${file.name}"
        shell("mkdir -p $destinationDir")
        shell("cp ${file.absolutePath} $destination")
        check(shell("stat -c %s $destination").toLongOrNull() == file.length())
    }
}
