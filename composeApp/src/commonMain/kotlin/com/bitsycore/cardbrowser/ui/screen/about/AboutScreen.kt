package com.bitsycore.cardbrowser.ui.screen.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.ui.component.AppIcons
import com.bitsycore.cardbrowser.ui.component.arrowScroll
import com.bitsycore.cardbrowser.ui.preview.PreviewFrame
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import org.koin.compose.viewmodel.koinViewModel

/** What this app is, where its data comes from, and whose the cards are. See [AboutContract]. */
@Composable
fun AboutScreen(
	onBack: () -> Unit,
	viewModel: AboutViewModel = koinViewModel(),
) {
	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			AboutContract.Effect.NavigateBack -> onBack()
		}
	}
	val vState by viewModel.collectAsStateWithLifecycle()

	AboutContent(state = vState, dispatch = viewModel::dispatch)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutContent(
	state: AboutContract.UiState,
	dispatch: (AboutContract.Intent) -> Unit,
) {
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("About") },
				navigationIcon = {
					IconButton(onClick = { dispatch(AboutContract.Intent.BackPressed) }) {
						Icon(AppIcons.ArrowBack, contentDescription = "Back")
					}
				},
			)
		},
	) { vPadding ->
		// Prose, so the arrows scroll it -- focus traversal alone leaves the stretches between the
		// few links unreachable from a keyboard.
		val vScroll = rememberScrollState()
		Column(
			modifier = Modifier
				.padding(vPadding)
				.fillMaxSize()
				.verticalScroll(vScroll)
				.arrowScroll(vScroll)
				.padding(horizontal = 20.dp, vertical = 8.dp),
		) {
			Text("Toploader", style = MaterialTheme.typography.headlineSmall)
			Text(
				text = "The TCG Browser",
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.primary,
			)
			Text(
				text = if (state.version.isBlank()) "" else "Version ${state.version}",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(16.dp))
			Text(
				text = "A browser for trading card games. Pick a game, browse its sets, read a card.",
				style = MaterialTheme.typography.bodyMedium,
			)

			// ============
			//  What it is not

			// Each of these is a thing somebody goes looking for, fails to find, and concludes is
			// broken. Saying so once is cheaper than answering it one person at a time.
			Heading("What it does not do")

			Point(
				"No accounts, no collection tracking, no deckbuilding.",
				"There is nothing to sign in to, and nothing about you is sent anywhere.",
			)
			Point(
				"No prices.",
				"Some sources publish them; they carry no currency and no timestamp, so they are " +
					"not shown. A card links out to Cardmarket and TCGplayer where its game has them.",
			)
			Point(
				"It does not merge sources.",
				"One source answers for a game, and its answer is the answer. Failing over to " +
					"another would quietly change what every card id means.",
			)

			// ============
			//  What it does

			Heading("What it does")

			Point(
				"It works offline.",
				"Every set you open or download is kept on the device until you delete it. " +
					"Storage says what is there, and lets you remove a game, a language or one set.",
			)
			Point(
				"It says what it does not know.",
				"A language is listed as confirmed only where a source served it. A partial set is " +
					"labelled partial. \"Could not check\" and \"there are none\" are drawn differently.",
			)

			// ============
			//  Sources

			Heading("Where the card data comes from")

			Text(
				text = "Public community databases, one per game. This app is not affiliated with " +
					"any of them, and none of them is affiliated with a publisher.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(12.dp))

			state.sources.forEach { vSource ->
				SourceRow(source = vSource, onOpenLink = {
					dispatch(AboutContract.Intent.LinkOpened(it))
				})
			}

			// ============
			//  Whose the cards are

			Heading("Trademarks and card content")

			Text(
				text = "Card names, card text, artwork, set names and game names are the property " +
					"of their respective publishers. They are shown here for reference only. This " +
					"app is an unofficial, unaffiliated fan project, claims no ownership of any of " +
					"it, and is not endorsed by any publisher.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(8.dp))
			Text(
				text = "Card images are loaded from each source and remain the property of their " +
					"publisher or photographer.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(32.dp))
		}
	}
}

/** A rule and a heading, so the page reads as sections rather than one wall. */
@Composable
private fun Heading(title: String) {
	Spacer(Modifier.height(24.dp))
	HorizontalDivider()
	Spacer(Modifier.height(12.dp))
	Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
	Spacer(Modifier.height(10.dp))
}

/** One plain statement and the sentence that qualifies it. */
@Composable
private fun Point(title: String, body: String) {
	Column(Modifier.padding(bottom = 12.dp)) {
		Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
		Text(
			text = body,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/** One source: its name, the games it answers for, its own wording, and a way to it. */
@Composable
private fun SourceRow(
	source: AboutContract.SourceCredit,
	onOpenLink: (String) -> Unit,
) {
	Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
		Row {
			Text(
				text = source.name,
				style = MaterialTheme.typography.bodyLarge,
				fontWeight = FontWeight.Medium,
				modifier = Modifier.weight(1f),
			)
		}
		Text(
			// The whole explanation of what a source name means: which games it answers for.
			text = source.games.joinToString(", "),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.primary,
		)
		Spacer(Modifier.height(4.dp))
		Text(
			// The source's own required wording, unedited.
			text = source.text,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		source.url?.let { vUrl ->
			TextButton(
				onClick = { onOpenLink(vUrl) },
				contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
			) { Text(vUrl, style = MaterialTheme.typography.labelMedium) }
		}
	}
}

@Preview
@Composable
private fun AboutPreview() = PreviewFrame {
	AboutContent(
		state = AboutContract.UiState(
			version = "1.0.0",
			sources = listOf(
				AboutContract.SourceCredit(
					name = "Riftcodex",
					games = listOf("Riftbound"),
					text = "Card data from Riftcodex, an unofficial fan project not affiliated " +
						"with Riot Games.",
					url = "https://riftcodex.com",
				),
				AboutContract.SourceCredit(
					name = "TCGdex",
					games = listOf("Pokémon"),
					text = "Pokémon card data from TCGdex, a community project not affiliated " +
						"with Nintendo, Creatures or GAME FREAK.",
					url = "https://tcgdex.dev",
				),
			),
		),
		dispatch = {},
	)
}
