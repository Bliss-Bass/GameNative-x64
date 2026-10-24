package app.gamenative.ui.util

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Exposes the `testTag`s below this node as view resource ids, so `uiautomator dump` (and the
 * adb-driven Linux test suite in scripts/linux-test) can find controls by a stable id instead of
 * by text or position. Dialogs and dropdown menus are separate windows and need their own.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.automationIds(): Modifier = semantics { testTagsAsResourceId = true }
