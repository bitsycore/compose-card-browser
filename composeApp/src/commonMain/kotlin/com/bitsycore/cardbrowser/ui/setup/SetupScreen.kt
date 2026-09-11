package com.bitsycore.cardbrowser.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import org.koin.compose.viewmodel.koinViewModel

/**
 * The first-launch setup, bound to its view model.
 *
 * Navigation is an effect, like every other screen here: the host replaces the flow rather than
 * stacking on it, so Back from the game list cannot walk into setup again.
 */
@Composable
fun SetupScreen(
	onDone: () -> Unit,
	viewModel: SetupViewModel = koinViewModel(),
) {
	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			SetupContract.Effect.Done -> onDone()
		}
	}
	val vState by viewModel.collectAsStateWithLifecycle()

	SetupContent(state = vState, dispatch = viewModel::dispatch)
}

/**
 * The setup, given a state and somewhere to send intents.
 *
 * No view model, no Koin, no coroutines, so every page is previewable. Game art is deliberately
 * *not* resolved here -- the picker shows names, because a wall of ten logos is the thing this
 * screen exists to spare someone.
 */
@Composable
fun SetupContent(
	state: SetupContract.UiState,
	dispatch: (SetupContract.Intent) -> Unit,
) {
	Scaffold { vPadding ->
		Column(Modifier.padding(vPadding).fillMaxSize().padding(horizontal = 24.dp)) {
			Spacer(Modifier.height(24.dp))
			PageDots(current = state.page.ordinal, count = SetupContract.SetupPage.entries.size)
			Spacer(Modifier.height(20.dp))

			// Weighted, so the buttons stay on the bottom edge whatever the page holds and a long
			// game list scrolls under them rather than pushing them off a phone screen.
			Box(Modifier.weight(1f)) {
				when (state.page) {
					SetupContract.SetupPage.GAMES -> GamesPage(state, dispatch)
					SetupContract.SetupPage.LANGUAGE -> LanguagePage(state, dispatch)
					SetupContract.SetupPage.ABOUT -> AboutPage()
				}
			}

			Spacer(Modifier.height(12.dp))
			Row(verticalAlignment = Alignment.CenterVertically) {
				if (state.page.ordinal > 0) {
					TextButton(onClick = { dispatch(SetupContract.Intent.WentBack) }) { Text("Back") }
				} else {
					// Only on the first page. Past it the user is clearly engaged, and an escape
					// hatch beside "Next" invites a misclick that discards what they just chose.
					TextButton(
						onClick = { dispatch(SetupContract.Intent.Skipped) },
						enabled = !state.isSaving,
					) { Text("Skip") }
				}
				Spacer(Modifier.weight(1f))
				Button(
					onClick = {
						dispatch(
							if (state.isLastPage) {
								SetupContract.Intent.Finished
							} else {
								SetupContract.Intent.Advanced
							},
						)
					},
					enabled = state.canAdvance && !state.isSaving,
				) { Text(if (state.isLastPage) "Start browsing" else "Next") }
			}
			Spacer(Modifier.height(20.dp))
		}
	}
}

// ==================
// MARK: Pages
// ==================

@Composable
private fun GamesPage(state: SetupContract.UiState, dispatch: (SetupContract.Intent) -> Unit) {
	Column {
		PageHeading(
			title = "Which games do you play?",
			body = "Everything else is hidden from the list. Nothing is deleted, and you can " +
				"change this any time from the game list.",
		)
		Spacer(Modifier.height(8.dp))
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			TextButton(onClick = { dispatch(SetupContract.Intent.AllGamesSelected) }) { Text("All") }
			TextButton(onClick = { dispatch(SetupContract.Intent.NoGamesSelected) }) { Text("None") }
		}
		LazyColumn(Modifier.weight(1f)) {
			items(state.games, key = { it.id.value }) { vGame ->
				val vChecked = vGame.id.value in state.selected
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier
						.fillMaxWidth()
						.clickable { dispatch(SetupContract.Intent.GameToggled(vGame.id.value)) },
				) {
					Checkbox(
						checked = vChecked,
						onCheckedChange = { dispatch(SetupContract.Intent.GameToggled(vGame.id.value)) },
					)
					Spacer(Modifier.width(4.dp))
					Text(vGame.displayName, style = MaterialTheme.typography.bodyLarge)
				}
			}
		}
		// Said where it happens rather than as a disabled button with no explanation.
		if (state.selected.isEmpty()) {
			Text(
				text = "Pick at least one — the app has nothing to show otherwise.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
		}
	}
}

@Composable
private fun LanguagePage(state: SetupContract.UiState, dispatch: (SetupContract.Intent) -> Unit) {
	Column(Modifier.verticalScroll(rememberScrollState())) {
		PageHeading(
			title = "Which language do you read cards in?",
			// The honest caveat, on the screen that sets the expectation. Most sources serve one
			// language, and the app falls back rather than showing an empty set.
			body = "Cards are shown in this language where the source has them. Many sources only " +
				"publish English, and a set with nothing in your language opens in one it does have.",
		)
		Spacer(Modifier.height(8.dp))
		for (vLanguage in state.languages) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.fillMaxWidth()
					.clickable { dispatch(SetupContract.Intent.LanguagePicked(vLanguage)) },
			) {
				RadioButton(
					selected = vLanguage == state.language,
					onClick = { dispatch(SetupContract.Intent.LanguagePicked(vLanguage)) },
				)
				Spacer(Modifier.width(4.dp))
				Text(vLanguage.displayName, style = MaterialTheme.typography.bodyLarge)
			}
		}
	}
}

@Composable
private fun AboutPage() {
	Column(Modifier.verticalScroll(rememberScrollState())) {
		PageHeading(
			title = "What this app is",
			body = "A browser for trading card games. Pick a game, browse its sets, and read a card.",
		)
		Spacer(Modifier.height(16.dp))
		// Stated up front because each one is a thing someone will otherwise go looking for and
		// conclude is broken. Saying so once here is cheaper than a support answer per user.
		AboutPoint(
			"No accounts, no collection tracking, no deckbuilding.",
			"There is nothing to sign in to and nothing is sent anywhere.",
		)
		AboutPoint(
			"No prices.",
			"Some sources publish them; they carry no currency or timestamp, so they are not " +
				"shown. Links out to Cardmarket and TCGplayer are on each card.",
		)
		AboutPoint(
			"Card data comes from public community databases.",
			"One per game, credited on every screen that uses it. This app is not affiliated " +
				"with any publisher.",
		)
		AboutPoint(
			"It works offline once a set is downloaded.",
			"Browsing caches as you go; Settings has the storage limits and a way to clear them.",
		)
	}
}

// ==================
// MARK: Parts
// ==================

@Composable
private fun PageHeading(title: String, body: String) {
	Column {
		Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
		Spacer(Modifier.height(6.dp))
		Text(
			text = body,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun AboutPoint(title: String, body: String) {
	Column(Modifier.padding(bottom = 14.dp)) {
		Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
		Text(
			text = body,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/** Where you are in three pages, without a number to read. */
@Composable
private fun PageDots(current: Int, count: Int) {
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		repeat(count) { vIndex ->
			Box(
				Modifier
					.size(if (vIndex == current) 10.dp else 8.dp)
					.clip(CircleShape)
					.background(
						if (vIndex == current) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.surfaceVariant
						},
					),
			)
		}
	}
}

// ==================
// MARK: Previews
// ==================

private fun previewGames(): List<GameProfile> = emptyList()

@Preview
@Composable
private fun SetupGamesPreview() = PreviewFrame {
	SetupContent(
		state = SetupContract.UiState(games = previewGames(), selected = setOf("magic")),
		dispatch = {},
	)
}

@Preview
@Composable
private fun SetupLanguagePreview() = PreviewFrame {
	SetupContent(
		state = SetupContract.UiState(
			page = SetupContract.SetupPage.LANGUAGE,
			language = CardLanguage.FRENCH,
		),
		dispatch = {},
	)
}

@Preview
@Composable
private fun SetupAboutPreview() = PreviewFrame(isDark = false) {
	SetupContent(
		state = SetupContract.UiState(page = SetupContract.SetupPage.ABOUT),
		dispatch = {},
	)
}
