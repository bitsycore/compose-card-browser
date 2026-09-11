import io


def edit(path, pairs):
	s = io.open(path, encoding='utf-8').read()
	for old, new, tag in pairs:
		assert s.count(old) == 1, (path, tag)
		s = s.replace(old, new, 1)
	io.open(path, 'w', encoding='utf-8', newline='\n').write(s)


# ==================
# MARK: Re-running setup becomes a navigation, not a flag plus a pop
# ==================

edit('composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/settings/SettingsContract.kt', [
	('''		data object NavigateBack : Effect''',
	 '''		data object NavigateBack : Effect

		/**
		 * Open the first-launch flow again.
		 *
		 * A navigation, not a flag. This used to clear `hasCompletedSetup` and navigate *back*,
		 * leaving a `LaunchedEffect` watching that flag to push the flow -- two asynchronous things
		 * mutating one back stack with nothing ordering them. See `App()`.
		 */
		data object OpenSetup : Effect''', 'effect'),
])

edit('composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/settings/SettingsViewModel.kt', [
	('''			SettingsContract.Intent.RerunSetup -> {
				// Only the flag. The choices themselves stay in force until the flow writes new
				// ones, so backing out of a re-run leaves everything as it was.
				mPreferences.update { it.copy(hasCompletedSetup = false) }
				emitEffect(SettingsContract.Effect.NavigateBack)
			}''',
	 '''			// Just go there. Nothing is written: the choices stay in force until the flow writes
			// new ones, so backing out of a re-run leaves everything as it was.
			//
			// This used to clear `hasCompletedSetup` and emit `NavigateBack`, and rely on a
			// `LaunchedEffect` elsewhere to notice the flag and push the flow. Two asynchronous
			// mutations of one back stack, in no particular order -- and when the pop won, it
			// removed the entry the flag had just pushed. Worse, the flag was *already* false by
			// then, so tapping again wrote the same value, the `StateFlow` did not emit, and the
			// flow could not be opened again at all until the app was restarted.
			SettingsContract.Intent.RerunSetup -> emitEffect(SettingsContract.Effect.OpenSetup)''', 'intent'),
])

edit('composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/settings/SettingsScreen.kt', [
	('''fun SettingsScreen(
	onBack: () -> Unit,
	viewModel: SettingsViewModel = koinViewModel(),
) {''',
	 '''fun SettingsScreen(
	onBack: () -> Unit,
	onOpenSetup: () -> Unit,
	viewModel: SettingsViewModel = koinViewModel(),
) {''', 'signature'),
	('''			SettingsContract.Effect.NavigateBack -> onBack()''',
	 '''			SettingsContract.Effect.NavigateBack -> onBack()
			SettingsContract.Effect.OpenSetup -> onOpenSetup()''', 'collect'),
])


# ==================
# MARK: The back stack cannot be emptied, and setup is pushed once
# ==================

edit('composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/App.kt', [
	('''		// Setup first, and only on a fresh install. Read from the same `StateFlow` the theme
		// uses, so re-running it from Settings puts the flow back on screen without a relaunch.
		val vBackStack = remember { mutableStateListOf<Route>(Route.Games) }
		LaunchedEffect(vPrefs.hasCompletedSetup) {
			if (!vPrefs.hasCompletedSetup && vBackStack.lastOrNull() != Route.Setup) {
				vBackStack.add(Route.Setup)
			}
		}''',
	 '''		// Setup on a fresh install, and only there. Re-running it from Settings is an ordinary
		// navigation now -- see `SettingsContract.Effect.OpenSetup` -- because having this effect
		// serve both meant a flag and a pop racing over the same list.
		val vBackStack = remember { mutableStateListOf<Route>(Route.Games) }
		LaunchedEffect(vPrefs.hasCompletedSetup) {
			// `!in`, not "is not on top". Pushing a second copy of a route that is already on the
			// stack gives `NavDisplay` two entries with one key, and popping one of them is then
			// a guess about which.
			if (!vPrefs.hasCompletedSetup && Route.Setup !in vBackStack) {
				vBackStack.add(Route.Setup)
			}
		}''', 'effect'),

	('''			onBack = { vBackStack.removeLastOrNull() },
			entryDecorators = listOf(''',
	 '''			onBack = { vBackStack.popRoute() },
			entryDecorators = listOf(''', 'navdisplay back'),

	('''							onDone = { vBackStack.remove(Route.Setup) },''',
	 '''							onDone = { vBackStack.popRoute(Route.Setup) },''', 'setup done'),

	('''					SettingsScreen(onBack = { vBackStack.removeLastOrNull() })''',
	 '''					SettingsScreen(
						onBack = { vBackStack.popRoute() },
						onOpenSetup = { vBackStack.add(Route.Setup) },
					)''', 'settings entry'),
])

s = io.open('composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/App.kt', encoding='utf-8').read()
assert s.count('onBack = { vBackStack.removeLastOrNull() },') == 6, s.count('onBack = { vBackStack.removeLastOrNull() },')
s = s.replace('onBack = { vBackStack.removeLastOrNull() },', 'onBack = { vBackStack.popRoute() },')

HELPERS = '''
/**
 * Pops the top route, unless it is the only one left.
 *
 * `NavDisplay` throws `IllegalArgumentException: NavDisplay backstack cannot be empty` rather than
 * degrading, so an empty stack is a crash on the next recomposition -- and it was reachable. Every
 * screen's back handler called `removeLastOrNull`, which will happily take the last entry, and the
 * re-run-setup path could pop twice for one tap.
 *
 * Refusing is the right answer rather than pushing a home route back on: the root of this stack is
 * the game picker, and "back from the first screen" is the platform's business -- on Android it
 * leaves the app, on desktop it does nothing.
 */
private fun SnapshotStateList<Route>.popRoute() {
	if (size > 1) removeAt(lastIndex)
}

/**
 * Removes [route] wherever it is, unless it is the only one left.
 *
 * By identity rather than by position, because the flow that uses it can be finished from anywhere
 * the stack happens to be -- it is pushed over the picker on a first launch and over Settings on a
 * re-run, and it must come off in both cases without assuming which.
 */
private fun SnapshotStateList<Route>.popRoute(route: Route) {
	if (size > 1) remove(route)
}
'''

assert s.rstrip().endswith('}'), 'unexpected end of App.kt'
s = s.rstrip() + '\n' + HELPERS
assert 'import androidx.compose.runtime.snapshots.SnapshotStateList' not in s
s = s.replace('import androidx.compose.runtime.mutableStateListOf',
              'import androidx.compose.runtime.mutableStateListOf\n'
              'import androidx.compose.runtime.snapshots.SnapshotStateList', 1)
io.open('composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/App.kt', 'w',
        encoding='utf-8', newline='\n').write(s)

print('navigation fixed')
