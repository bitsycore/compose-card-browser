package com.bitsycore.cardbrowser.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.model.Availability
import com.bitsycore.cardbrowser.core.model.CardOrientation
import com.bitsycore.cardbrowser.core.model.CardPrinting
import com.bitsycore.cardbrowser.ui.common.CardImage
import com.bitsycore.cardbrowser.ui.common.ErrorState
import com.bitsycore.cardbrowser.ui.common.LoadingState
import com.bitsycore.lib.pulse.compose.collectAsStateWithLifecycle
import com.bitsycore.lib.pulse.compose.collectEffect
import org.koin.compose.viewmodel.koinViewModel

/**
 * One printing, in full.
 *
 * The sections below the image are ordered by how certain they are: what the provider stated, then
 * what it stated partially, then what it did not state at all. The last group is still shown --
 * silence about Korean is not the same as there being no Korean printing, and the screen says which
 * it is.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CardDetailScreen(
	cardId: String,
	setId: String?,
	onBack: () -> Unit,
	viewModel: CardDetailViewModel = koinViewModel(),
) {
	val vState by viewModel.collectAsStateWithLifecycle()
	val vSnackbarHost = remember { SnackbarHostState() }

	LaunchedEffect(cardId) {
		viewModel.dispatch(CardDetailContract.Intent.Load(cardId, setId))
	}

	viewModel.collectEffect { vEffect ->
		when (vEffect) {
			is CardDetailContract.Effect.LinkFailed ->
				vSnackbarHost.showSnackbar("Could not open a browser for that link.")
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(vState.card?.displayName ?: "Card") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.Outlined.ArrowBack, contentDescription = "Back to cards")
					}
				},
			)
		},
		snackbarHost = { SnackbarHost(vSnackbarHost) },
	) { vPadding ->
		val vCard = vState.card
		when {
			vState.isLoading -> LoadingState(Modifier.padding(vPadding))
			vCard == null -> ErrorState(
				error = vState.error
					?: com.bitsycore.cardbrowser.core.provider.ProviderError.Unknown("Card not found"),
				onRetry = null,
				modifier = Modifier.padding(vPadding),
			)
			else -> CardDetailContent(
				state = vState,
				card = vCard,
				onZoomToggle = { viewModel.dispatch(CardDetailContract.Intent.ZoomToggled(it)) },
				onLanguageSelected = { viewModel.dispatch(CardDetailContract.Intent.LanguageSelected(it)) },
				onFinishSelected = { viewModel.dispatch(CardDetailContract.Intent.FinishSelected(it)) },
				onOpenCardmarket = { viewModel.dispatch(CardDetailContract.Intent.OpenCardmarket) },
				modifier = Modifier.padding(vPadding),
			)
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardDetailContent(
	state: CardDetailContract.UiState,
	card: CardPrinting,
	onZoomToggle: (Boolean) -> Unit,
	onLanguageSelected: (com.bitsycore.cardbrowser.core.model.CardLanguage) -> Unit,
	onFinishSelected: (com.bitsycore.cardbrowser.core.model.Finish) -> Unit,
	onOpenCardmarket: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 20.dp)
			.padding(bottom = 32.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		ZoomableCardImage(card = card, onZoomToggle = onZoomToggle)

		Spacer(Modifier.height(16.dp))

		Text(card.displayName, style = MaterialTheme.typography.headlineSmall)
		Text(
			text = "${card.setName} · ${card.collectorNumber}",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		Spacer(Modifier.height(12.dp))

		// Stats and classification, each chip omitted when the provider did not state it.
		FlowRow(
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			card.classification.rarity?.let { StatChip(it) }
			card.classification.type?.let { StatChip(it) }
			card.classification.supertype?.let { StatChip(it) }
			card.classification.domains.forEach { StatChip(it) }
			card.attributes.energy?.let { StatChip("$it energy") }
			card.attributes.might?.let { StatChip("$it might") }
			card.attributes.power?.let { StatChip("$it power") }
			if (card.artwork.treatment != com.bitsycore.cardbrowser.core.model.ArtworkTreatment.STANDARD) {
				StatChip(card.artwork.treatment.displayName)
			}
		}

		card.text.rules?.let { vRules ->
			Spacer(Modifier.height(16.dp))
			Text(vRules, style = MaterialTheme.typography.bodyMedium)
		}

		card.text.flavour?.let { vFlavour ->
			Spacer(Modifier.height(10.dp))
			Text(
				text = vFlavour,
				style = MaterialTheme.typography.bodySmall,
				fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		card.artwork.artist?.let { vArtist ->
			Spacer(Modifier.height(10.dp))
			Text(
				text = "Art by $vArtist",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		// ============
		//  Language

		SectionDivider()
		SectionTitle("Language")

		state.languageResolution?.let { vResolution ->
			val vShown = vResolution.shown
			if (vShown == null) {
				Note("This card database does not state a printing language for this card.")
			} else if (vResolution.isFallback) {
				// The line that keeps the app honest about French.
				Note(
					"You prefer ${vResolution.requested.displayName}. This database has only " +
						"${vShown.displayName} for this card, so the text and image shown are " +
						"${vShown.displayName}. That does not mean a " +
						"${vResolution.requested.displayName} printing does not exist.",
				)
			}
		}

		Spacer(Modifier.height(8.dp))
		FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			state.languageOptions.forEach { vOption ->
				AvailabilityChip(
					label = vOption.language.displayName,
					availability = vOption.availability,
					isSelected = vOption.isSelected,
					onClick = if (vOption.isSelectable) {
						{ onLanguageSelected(vOption.language) }
					} else {
						null
					},
				)
			}
		}

		// ============
		//  Finish

		SectionDivider()
		SectionTitle("Finish")

		if (state.finishOptions.isEmpty()) {
			Note(
				if (state.providerStatesFinishes) {
					"No finish is recorded for this card."
				} else {
					"This card database does not record finishes, so none can be offered. " +
						"Foil and non-foil versions may still exist."
				},
			)
		} else {
			Spacer(Modifier.height(8.dp))
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				state.finishOptions.forEach { vOption ->
					AvailabilityChip(
						label = vOption.finish.displayName,
						availability = vOption.availability,
						isSelected = vOption.isSelected,
						onClick = if (vOption.isSelectable) { { onFinishSelected(vOption.finish) } } else null,
					)
				}
			}
		}

		// ============
		//  Artwork

		state.artworkNote?.let { vNote ->
			SectionDivider()
			SectionTitle("Other artwork")
			Note(vNote)
		}

		// ============
		//  Cardmarket

		state.cardmarketLink?.let { vLink ->
			SectionDivider()
			Spacer(Modifier.height(4.dp))
			OutlinedButton(onClick = onOpenCardmarket, modifier = Modifier.fillMaxWidth()) {
				Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
				Spacer(Modifier.size(8.dp))
				Text(vLink.label)
			}
			Note(
				when (vLink) {
					is com.bitsycore.cardbrowser.core.cardmarket.CardmarketLink.Product ->
						"Opens this card's Cardmarket page. It does not place an order."
					is com.bitsycore.cardbrowser.core.cardmarket.CardmarketLink.CardSearch ->
						"Opens a Cardmarket search for \"${vLink.terms}\" in ${vLink.expansion}. " +
							"This database does not map cards to Cardmarket products, so this is a " +
							"search rather than an exact product page. It does not place an order."
					is com.bitsycore.cardbrowser.core.cardmarket.CardmarketLink.ExpansionSingles ->
						"Opens the ${vLink.expansion} singles listing. It does not place an order."
					is com.bitsycore.cardbrowser.core.cardmarket.CardmarketLink.GameHome ->
						"Opens Cardmarket's Riftbound section. It does not place an order."
				},
			)
		}

		state.attribution?.let { vAttribution ->
			SectionDivider()
			Note(vAttribution)
		}
	}
}

/**
 * The large image, pinch- and tap-to-zoom.
 *
 * Loads the full-resolution URL rather than the grid's thumbnail: this is the one place where the
 * full image is worth its bytes, and loading it here rather than in the grid is what keeps
 * scrolling a set cheap.
 */
@Composable
private fun ZoomableCardImage(card: CardPrinting, onZoomToggle: (Boolean) -> Unit) {
	var vScale by remember { mutableFloatStateOf(1f) }
	var vOffsetX by remember { mutableFloatStateOf(0f) }
	var vOffsetY by remember { mutableFloatStateOf(0f) }

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.widthIn(max = 420.dp)
			.aspectRatio(
				if (card.orientation == CardOrientation.LANDSCAPE) 1039f / 744f else 744f / 1039f,
			)
			.clip(RoundedCornerShape(12.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.pointerInput(card.id) {
				detectTransformGestures { _, vPan, vZoom, _ ->
					// Clamped so the image cannot be shrunk to nothing or zoomed past usefulness.
					vScale = (vScale * vZoom).coerceIn(1f, 4f)
					if (vScale > 1f) {
						vOffsetX += vPan.x
						vOffsetY += vPan.y
					} else {
						vOffsetX = 0f
						vOffsetY = 0f
					}
					onZoomToggle(vScale > 1f)
				}
			}
			.clickable {
				// A double-tap-free reset: tapping a zoomed image puts it back, which is the
				// gesture people try first when they are lost.
				if (vScale > 1f) {
					vScale = 1f
					vOffsetX = 0f
					vOffsetY = 0f
					onZoomToggle(false)
				}
			},
	) {
		CardImage(
			artwork = card.artwork,
			contentDescription = card.artwork.accessibilityText ?: card.displayName,
			useThumbnail = false,
			contentScale = ContentScale.Fit,
			modifier = Modifier
				.fillMaxSize()
				.graphicsLayer(
					scaleX = vScale,
					scaleY = vScale,
					translationX = vOffsetX,
					translationY = vOffsetY,
				),
		)
	}
}

/** A plain fact chip. */
@Composable
private fun StatChip(label: String) {
	Surface(
		color = MaterialTheme.colorScheme.secondaryContainer,
		shape = RoundedCornerShape(20.dp),
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSecondaryContainer,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
		)
	}
}

/**
 * A chip whose appearance encodes whether the thing is offered, ruled out, or simply unknown.
 *
 * Three states rather than two, because a greyed-out chip that means "we have no idea" and a
 * greyed-out chip that means "this does not exist" must not look the same.
 */
@Composable
private fun AvailabilityChip(
	label: String,
	availability: Availability,
	isSelected: Boolean,
	onClick: (() -> Unit)?,
) {
	val vSuffix = when (availability) {
		Availability.AVAILABLE -> ""
		Availability.UNAVAILABLE -> " — not printed"
		Availability.UNKNOWN -> " — unknown"
	}
	AssistChip(
		onClick = onClick ?: {},
		enabled = onClick != null,
		label = { Text(label + vSuffix) },
		colors = if (isSelected) {
			androidx.compose.material3.AssistChipDefaults.assistChipColors(
				containerColor = MaterialTheme.colorScheme.primaryContainer,
				labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
			)
		} else {
			androidx.compose.material3.AssistChipDefaults.assistChipColors()
		},
	)
}

@Composable
private fun SectionDivider() {
	Spacer(Modifier.height(20.dp))
	HorizontalDivider()
	Spacer(Modifier.height(12.dp))
}

@Composable
private fun SectionTitle(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.titleSmall,
		modifier = Modifier.fillMaxWidth(),
	)
}

@Composable
private fun Note(text: String) {
	Spacer(Modifier.height(6.dp))
	Text(
		text = text,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.fillMaxWidth(),
	)
}
