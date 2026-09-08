package com.bitsycore.cardbrowser.ui.common

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * The [SharedTransitionScope] the whole app is drawn inside, or `null` outside one.
 *
 * A composition local rather than a parameter threaded through every screen: the scope belongs to
 * the navigation host, and the only things that care are two image call sites four levels down.
 * Nullable so a `@Preview` or a test renders normally instead of crashing.
 */
val LocalSharedTransitionScope: ProvidableCompositionLocal<SharedTransitionScope?> =
	compositionLocalOf { null }

/**
 * Marks a card's artwork as the same element on both sides of a navigation.
 *
 * Applied to the tile in the grid and to the large image in detail with the same key, so opening a
 * card grows it out of the tile that was tapped and going back drops it home again, instead of the
 * two screens cross-fading over each other.
 *
 * The key is the printing's source-qualified id, which is unique across the whole app -- two
 * printings of the same card are separate elements, as they should be, because they are separate
 * pictures.
 *
 * Silently does nothing when either scope is missing. That is the honest fallback: no shared
 * transition is a plain cross-fade, which is what the app did before and is nobody's emergency.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCardArt(cardId: String): Modifier {
	val vSharedScope = LocalSharedTransitionScope.current ?: return this
	// Provided by NavDisplay to every entry's content. Read only from inside one -- which both call
	// sites are -- so the local always has a value here.
	val vAnimatedScope = LocalNavAnimatedContentScope.current

	return with(vSharedScope) {
		this@sharedCardArt.sharedElement(
			sharedContentState = rememberSharedContentState(key = "card-art:$cardId"),
			animatedVisibilityScope = vAnimatedScope,
		)
	}
}
