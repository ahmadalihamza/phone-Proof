package pk.phoneproof.app

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pk.phoneproof.app.data.LocalInspection
import pk.phoneproof.diagnostics.*
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Real Activity/navigation/Room/component tests in the isolated verification package.
 * Input automation checks software behavior, never physical panel quality. No account
 * is created and no pre-existing data, endpoint or credentials are changed.
 */
@RunWith(AndroidJUnit4::class)
class InspectionUxSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: InspectionViewModel
    private var baselineIds: Set<String> = emptySet()
    private val createdIds = mutableSetOf<String>()

    @Before fun requireIsolatedSignedOutSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("UI tests only write fixtures in the isolated .verification package", context.packageName.endsWith(".verification"))
        check(!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) { "Unlock the physical phone before running UI acceptance." }
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[InspectionViewModel::class.java] }
        assumeTrue("Existing signed-in sessions are preserved; run UI tests signed out", vm.api.vault.snapshot().tokens == null)
        baselineIds = runBlocking(Dispatchers.IO) { vm.db.dao().inspections().map { it.id }.toSet() }
    }

    @After fun removeOnlyExactTestOwnedInspections() {
        if(!::vm.isInitialized)return
        if(createdIds.isEmpty())return
        check(createdIds.none { it in baselineIds }) { "Refusing to delete a pre-existing inspection" }
        compose.runOnIdle { vm.stop(); vm.selected.value = null }
        compose.waitUntil(10_000) { vm.busy.value == null }
        runBlocking(Dispatchers.IO) {
            for(id in createdIds) {
                // Exact parameterized keys only; foreign keys remove this fixture's results/report.
                vm.db.openHelper.writableDatabase.delete("inspections", "id = ?", arrayOf(id))
            }
            assertEquals(baselineIds, vm.db.dao().inspections().map { it.id }.toSet())
        }
    }

    @Test fun homeShowsThreeDestinationsAndClearActions() {
        tab("Inspect").assertIsSelected()
        tab("Reports").assertExists()
        tab("Account").assertExists()
        compose.onAllNodes(tabRole()).assertCountEquals(3)
        compose.onNodeWithText("Start inspection").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Scan another phone").assertIsDisplayed()
        compose.onNodeWithText("Device info").assertDoesNotExist()
        assertConcisePrimaryCopy()
        saveScreenshot("home")
    }

    @Test fun localAndCloudViewsRespectOwnershipAndDiscardAccountFormDrafts() {
        val privateModel = "OTHER_ACCOUNT_PRIVATE_UI_FIXTURE"
        val fixtureId = UUID.randomUUID().toString()
        val profile = CapabilityProfile(36, emptyMap(), emptyList(), emptyMap(), mapOf("model" to privateModel))
        runBlocking(Dispatchers.IO) {
            vm.db.dao().create(LocalInspection(fixtureId, Instant.now().toString(), DiagnosticRegistry.json.encodeToString(profile),
                ownerId = UUID.randomUUID().toString(), serverUrl = "https://private-ui-fixture.example.invalid/api/v1/"))
        }
        createdIds.add(fixtureId)
        compose.waitUntil(5_000) { vm.inspections.value.any { it.id == fixtureId } }
        assertFalse(vm.canView(runBlocking(Dispatchers.IO) { requireNotNull(vm.db.dao().inspection(fixtureId)) }))
        tab("Reports").performClick()
        tab("On phone").assertIsSelected()
        compose.onNodeWithText("On this phone").assertIsDisplayed()
        compose.onNodeWithText(privateModel).assertDoesNotExist()
        tab("Cloud").performClick()
        compose.onNodeWithText("Sign in to view your reports.").assertIsDisplayed()
        assertConcisePrimaryCopy()

        tab("Account").performClick()
        compose.onNode(hasSetTextAction() and hasText("Email")).performTextInput("ux-smoke@example.invalid")
        compose.onNode(hasText("Sign in") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("Password")).performTextInput("smoke-only-password")
        compose.onNode(hasText("Sign in") and hasClickAction()).assertIsEnabled()
        compose.runOnIdle {
            val activity = compose.activity
            activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        tab("Inspect").performClick()
        compose.onNodeWithText("Start inspection").assertIsDisplayed()
        compose.onNodeWithText(privateModel).assertDoesNotExist()
        tab("Account").performClick()
        compose.onNodeWithText("ux-smoke@example.invalid").assertDoesNotExist()
        for(label in listOf("Email", "Password"))compose.onNode(hasSetTextAction() and hasText(label))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertNull(vm.api.vault.snapshot().tokens)
    }

    @Test fun stagesKeepGuidedChecksUnverifiedAndPartialReportSurvivesRecreation() {
        val id = startInspection()
        compose.onNodeWithText("Check this phone").assertIsDisplayed()
        compose.onNodeWithText("stages reviewed", substring = true).assertExists()
        compose.onNodeWithText("Continue · Display").assertIsDisplayed()
        compose.onNodeWithText("Checks").assertDoesNotExist()
        compose.onNodeWithText("Device info").assertDoesNotExist()
        compose.onNodeWithText("Build fingerprint").assertDoesNotExist()
        assertConcisePrimaryCopy()
        saveScreenshot("inspection-stages")

        val rows = results(id)
        val guided = vm.definitions().filter { it.weight > 0 && it.mode != "AUTOMATIC" }.map { it.id }.toSet()
        assertTrue(guided.contains("display.white"))
        assertFalse("Unviewed display inspection must remain unrecorded", rows.any { it.checkId == "display.white" })
        assertTrue("Device self-checks must not invent measured manual outcomes", rows.filter { it.checkId in guided }
            .all { it.status in setOf(DiagnosticStatus.NOT_SUPPORTED, DiagnosticStatus.NOT_ACCESSIBLE) })

        compose.onNodeWithText("Save partial report").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { vm.db.dao().report(id) != null } && vm.busy.value == null }
        tab("Reports").performClick()
        tab("On phone").assertIsSelected()
        compose.onAllNodesWithText("View summary").onFirst().performClick()
        val localLabel = compose.onNodeWithText("Saved on this phone · Not server verified")
        compose.waitUntil(10_000) { localLabel.isDisplayed() }
        localLabel.assertIsDisplayed()
        compose.onNodeWithText("Share local PDF").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").performScrollTo().performClick()
        val savedHash = runBlocking(Dispatchers.IO) { requireNotNull(vm.db.dao().report(id)).payloadHash }
        compose.runOnIdle { vm.selected.value = null }
        compose.onAllNodesWithText("Resume").onFirst().performClick()
        compose.waitUntil(5_000) { vm.selected.value?.id == id }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Continue · Display").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            vm = ViewModelProvider(compose.activity)[InspectionViewModel::class.java]
            assertEquals(id, vm.selected.value?.id)
        }
        assertEquals(savedHash, runBlocking(Dispatchers.IO) { requireNotNull(vm.db.dao().report(id)).payloadHash })
        assertEquals(baselineIds.size + 1, runBlocking(Dispatchers.IO) { vm.db.dao().inspections().size })
        assertNull(vm.api.vault.snapshot().tokens)
    }

    @Test fun fullscreenPatternsAndTouchRestoreWindowAndNeverPassUnconfirmedObservations() {
        val id = startInspection()
        val normalWindow = windowState()
        compose.onNodeWithText("Continue · Display").performClick()
        compose.onNodeWithText("Start display inspection").performClick()
        repeat(10) { patternIndex ->
            compose.onNodeWithTag("display-pattern").assertIsDisplayed()
            assertFullscreen("display-pattern")
            compose.onAllNodes(hasText("Next pattern") and hasAnyAncestor(hasTestTag("display-inspection"))).assertCountEquals(0)
            compose.mainClock.advanceTimeBy(850)
            compose.waitForIdle()
            if(patternIndex == 8)assertEquals("Minimum pattern must request actual window dimming", 0.01f, dialogBrightness(), 0.001f)
            if(patternIndex == 9)assertEquals("Maximum pattern must request actual window brightness", 1f, dialogBrightness(), 0.001f)
            compose.onNodeWithTag("display-pattern").performClick()
        }
        compose.onNodeWithText("Display observations").assertIsDisplayed()
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, dialogBrightness(), 0.001f)
        // Rendering all patterns alone is insufficient. Both inspector confirmations stay unchecked.
        compose.onNodeWithText("Save observations").performScrollTo().performClick()
        compose.waitUntil(10_000) { results(id).count { it.checkId.startsWith("display.") && it.evidence["workflow"] == "fullscreen_display_v1" } == 17 && vm.busy.value == null }
        assertTrue(results(id).filter { it.checkId.startsWith("display.") && it.evidence["workflow"] == "fullscreen_display_v1" }
            .all { it.status == DiagnosticStatus.INCONCLUSIVE })
        compose.onNodeWithTag("display-inspection").assertDoesNotExist()
        assertEquals(normalWindow, windowState())

        compose.onNodeWithTag("inspection-list").performScrollToNode(hasText("Touchscreen"))
        compose.onNodeWithText("Touchscreen").performClick()
        compose.onNodeWithText("Start touch examination").performClick()
        compose.onNodeWithTag("touch-surface").assertIsDisplayed()
        assertFullscreen("touch-surface")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Touch controls").assertIsDisplayed()
        compose.onNodeWithText("Review partial observations").performScrollTo().performClick()
        compose.onNodeWithText("Save observations").performScrollTo().performClick()
        compose.waitUntil(10_000) { results(id).count { it.checkId.startsWith("touch.") && it.evidence["workflow"] == "fullscreen_touch_v1" } == 14 && vm.busy.value == null }
        assertTrue("Unperformed touch gestures and untouched tiles must remain unverified", results(id)
            .filter { it.checkId.startsWith("touch.") && it.evidence["workflow"] == "fullscreen_touch_v1" }
            .all { it.status == DiagnosticStatus.INCONCLUSIVE })
        compose.onNodeWithTag("touch-inspection").assertDoesNotExist()
        assertEquals(normalWindow, windowState())
    }

    private fun startInspection(): String {
        compose.onNodeWithText("Start inspection").performClick()
        compose.waitUntil(20_000) { vm.selected.value?.id?.let { it !in baselineIds } == true }
        val id = requireNotNull(vm.selected.value).id
        createdIds.add(id)
        compose.waitUntil(90_000) { vm.busy.value == null }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Continue · Display").fetchSemanticsNodes().isNotEmpty() }
        return id
    }

    private fun results(id: String) = runBlocking(Dispatchers.IO) { vm.db.dao().results(id) }
        .map { DiagnosticRegistry.json.decodeFromString<DiagnosticResult>(it.payload) }

    private data class WindowState(val brightness: Float, val flags: Int, val cutoutMode: Int, val barsVisible: Boolean?)
    private fun windowState(): WindowState {
        var state: WindowState? = null
        compose.runOnIdle {
            val window = compose.activity.window
            state = WindowState(window.attributes.screenBrightness, window.attributes.flags, window.attributes.layoutInDisplayCutoutMode,
                ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.systemBars()))
        }
        return requireNotNull(state)
    }

    private fun dialogBrightness(): Float {
        var brightness: Float? = null
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            brightness = WindowInspector.getGlobalWindowViews().lastOrNull { it !== decor && it.isAttachedToWindow && it.isShown }
                ?.let { (it.layoutParams as? WindowManager.LayoutParams)?.screenBrightness }
        }
        return requireNotNull(brightness) { "Expected an actual fullscreen inspection dialog window" }
    }

    private fun assertFullscreen(tag: String) {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val screen = compose.activity.windowManager.currentWindowMetrics.bounds
        assertEquals("Fullscreen surface must begin at the window's left edge", 0f, bounds.left, 1f)
        assertEquals("Fullscreen surface must begin at the window's top edge", 0f, bounds.top, 1f)
        assertEquals("Fullscreen surface must include the window width", screen.width().toFloat(), bounds.width, 1f)
        assertEquals("Fullscreen surface must include the window height", screen.height().toFloat(), bounds.height, 1f)
    }

    private fun tabRole() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private fun tab(label: String) = compose.onNode(tabRole() and hasText(label))

    private fun assertConcisePrimaryCopy() {
        fun texts(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap { texts(it) }
        val text = texts(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        assertTrue("Primary screens should not contain paragraph-length text blocks", text.none { it.length > 180 || "\n\n" in it })
        val technical = setOf("USER_CONFIRMED", "GUIDED_AUTOMATIC", "NOT_ACCESSIBLE", "PLATFORM_MEASUREMENT")
        assertTrue("Implementation statuses belong in optional details", text.none { value -> technical.any { it in value } })
    }

    private fun saveScreenshot(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ux-smoke").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
