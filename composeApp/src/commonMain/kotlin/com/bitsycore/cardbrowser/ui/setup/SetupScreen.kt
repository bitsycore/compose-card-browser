package com.bitsycore.cardbrowser.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.game.GameProfile
import com.bitsycore.cardbrowser.core.model.CardLanguage
import com.bitsycore.cardbrowser.games.altered.AlteredGame
import com.bitsycore.cardbrowser.games.api.GameArt
import com.bitsycore.cardbrowser.games.magic.MagicGame
import com.bitsycore.cardbrowser.games.onepiece.OnePieceGame
import com.bitsycore.cardbrowser.games.pokemon.PokemonGame
import com.bitsycore.cardbrowser.games.riftbound.RiftboundGame
import com.bitsycore.cardbrowser.games.yugioh.YuGiOhGame
import com.bitsycore.cardbrowser.ui.games.GameArtRegistry
import com.bitsycore.cardbrowser.ui.games.GameMark
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import org.koin.compose.koinInject
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

	// Resolved here, not in the picker: a `Content` and everything under it must stay free of Koin
	// or its previews throw. Same hoist as `GameListScreen`, which draws the same marks.
	val vArtRegistry = koinInject<GameArtRegistry>()

	SetupContent(
		state = vState,
		dispatch = viewModel::dispatch,
		artFor = vArtRegistry::forGame,
	)
}

/**
 * The setup, given a state and somewhere to send intents.
 *
 * No view model, no Koin, no coroutines, so every page is previewable.
 *
 * The column is centred and capped rather than filling the window. This is the one screen with no
 * list long enough to justify full width, and in a desktop window a 1600 px line of body text with
 * three controls stranded at the far left reads as an unfinished dialog. A phone is narrower than
 * the cap, so nothing changes there.
 */
@Composable
fun SetupContent(
	state: SetupContract.UiState,
	dispatch: (SetupContract.Intent) -> Unit,
	/**
	 * A game's logo and accent colour, supplied by the caller.
	 *
	 * A parameter rather than a `koinInject` in the tile, so this composable and its previews need
	 * no Koin graph. The default answers `null`, which `GameMark` draws a fallback for.
	 */
	artFor: (GameProfile) -> GameArt? = { null },
) {
	Scaffold { vPadding ->
		Column(
			modifier = Modifier
				.padding(vPadding)
				.fillMaxSize()
				.wrapContentWidth()
				.widthIn(max = 640.dp)
				.padding(horizontal = 24.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Spacer(Modifier.height(24.dp))
			PageDots(current = state.page.ordinal, count = SetupContract.SetupPage.entries.size)
			Spacer(Modifier.height(20.dp))

			// Weighted, so the buttons stay on the bottom edge whatever the page holds and a long
			// game list scrolls under them rather than pushing them off a phone screen.
			Box(Modifier.weight(1f)) {
				when (state.page) {
					SetupContract.SetupPage.GAMES -> GamesPage(state, dispatch, artFor)
					SetupContract.SetupPage.LANGUAGE -> LanguagePage(state, dispatch)
					SetupContract.SetupPage.ABOUT -> AboutPage()
				}
			}

			Spacer(Modifier.height(12.dp))
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.fillMaxWidth(),
			) {
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
private fun GamesPage(
	state: SetupContract.UiState,
	dispatch: (SetupContract.Intent) -> Unit,
	artFor: (GameProfile) -> GameArt?,
) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		PageHeading(
			title = "Which games do you play?",
			body = "Everything else is hidden from the list. Nothing is deleted, and you can " +
				"change this any time from the game list.",
		)
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier.fillMaxWidth(),
		) {
			TextButton(onClick = { dispatch(SetupContract.Intent.AllGamesSelected) }) { Text("All") }
			TextButton(onClick = { dispatch(SetupContract.Intent.NoGamesSelected) }) { Text("None") }
		}
		// Marks rather than a column of checkboxes. Ten wordmarks are recognised at a glance where
		// ten names have to be read, and this is the one moment the user has no idea yet which
		// games the app even covers. Adaptive rather than a fixed column count, so the same grid is
		// two across on a phone and four in a desktop window.
		LazyVerticalGrid(
			// 104dp so a 400px phone gets two across rather than one. Measured by rendering both
			// widths -- see `SetupRenderer`; at 132dp the phone showed a single column of tiles
			// twice the size they needed to be.
			columns = GridCells.Adaptive(minSize = 104.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
			modifier = Modifier.weight(1f),
		) {
			// Keyed on the game id, which is unique by construction -- the routing table names one
			// provider per game. A duplicate key throws in a lazy grid rather than degrading.
			items(state.games, key = { it.id.value }) { vGame ->
				GameChoice(
					game = vGame,
					art = artFor(vGame),
					isSelected = vGame.id.value in state.selected,
					onToggle = { dispatch(SetupContract.Intent.GameToggled(vGame.id.value)) },
				)
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

/**
 * One game, as a mark you tap.
 *
 * Selection is a border and a tick rather than a checkbox: the tile is the target, and a checkbox
 * beside it gives two things to aim at for one decision. The tick sits over the corner of the mark
 * so the tile does not change size when it appears.
 */
@Composable
private fun GameChoice(
	game: GameProfile,
	art: GameArt?,
	isSelected: Boolean,
	onToggle: () -> Unit,
) {
	Column(
		horizontalAlignment = Alignment.CenterHorizontally,
		modifier = Modifier
			.clip(RoundedCornerShape(16.dp))
			.border(
				width = if (isSelected) 2.dp else 1.dp,
				color = if (isSelected) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.outlineVariant
				},
				shape = RoundedCornerShape(16.dp),
			)
			.clickable(onClick = onToggle)
			.padding(vertical = 12.dp, horizontal = 8.dp)
			.fillMaxWidth(),
	) {
		Box(contentAlignment = Alignment.TopEnd) {
			// The picker's own tile, at the same aspect ratio. Shared rather than redrawn: two
			// surfaces with their own idea of a logo backdrop is a bug this codebase has had once.
			GameMark(art = art, width = 84.dp, height = 56.dp)
			if (isSelected) {
				Icon(
					imageVector = Icons.Filled.CheckCircle,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.padding(4.dp).size(20.dp),
				)
			}
		}
		Spacer(Modifier.height(8.dp))
		Text(
			text = game.displayName,
			style = MaterialTheme.typography.labelLarge,
			textAlign = TextAlign.Center,
			// Always two lines' worth, so a row holding "Magic: The Gathering" beside "Altered"
			// has tiles of one height rather than a short one floating beside a tall one.
			minLines = 2,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

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

/**
 * Real profiles, because the picker's whole subject is how a wall of marks reads.
 *
 * A preview has no Koin graph, so the *art* still resolves to `null` and the tiles draw their
 * fallback -- see `SetupRenderer` for the version with logos.
 */
private fun previewGames(): List<GameProfile> = listOf(
	RiftboundGame, PokemonGame, MagicGame, OnePieceGame, AlteredGame, YuGiOhGame,
)

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
