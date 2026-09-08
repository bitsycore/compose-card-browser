package com.bitsycore.cardbrowser.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.core.provider.ProviderError

/**
 * The states every data screen in this app has to be able to show.
 *
 * Gathered in one file so the set list, the card grid and the detail screen do not each invent
 * their own wording for "offline" or their own retry button.
 */

/** A centred spinner, for when there is nothing at all to draw yet. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
	Column(
		modifier = modifier.fillMaxSize(),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		CircularProgressIndicator()
	}
}

/**
 * A dead end with an explanation and, where retrying could help, a button.
 *
 * The button is shown only for [ProviderError.isTransient]: offering "try again" for a malformed
 * response invites the user to repeat something that cannot work.
 */
@Composable
fun ErrorState(
	error: ProviderError,
	onRetry: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier.fillMaxSize().padding(32.dp),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Icon(
			imageVector = Icons.Outlined.CloudOff,
			contentDescription = null,
			modifier = Modifier.size(40.dp),
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Spacer(Modifier.height(12.dp))
		Text(
			text = error.userMessage(),
			style = MaterialTheme.typography.bodyMedium,
			textAlign = TextAlign.Center,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (onRetry != null && error.isTransient) {
			Spacer(Modifier.height(8.dp))
			TextButton(onClick = onRetry) { Text("Try again") }
		}
	}
}

/** Nothing matched, or nothing exists. Distinct from an error, and worded so. */
@Composable
fun EmptyState(
	message: String,
	modifier: Modifier = Modifier,
	action: (@Composable () -> Unit)? = null,
) {
	Column(
		modifier = modifier.fillMaxSize().padding(32.dp),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Icon(
			imageVector = Icons.Outlined.Inbox,
			contentDescription = null,
			modifier = Modifier.size(40.dp),
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Spacer(Modifier.height(12.dp))
		Text(
			text = message,
			style = MaterialTheme.typography.bodyMedium,
			textAlign = TextAlign.Center,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (action != null) {
			Spacer(Modifier.height(8.dp))
			action()
		}
	}
}

/**
 * A strip above content saying it is cached, partial, or both, with a retry when a refresh failed.
 *
 * This is where the app is honest. A grid of 200 cards out of a 352-card set says so here rather
 * than looking exactly like a complete one.
 */
@Composable
fun NoticeBanner(
	text: String,
	icon: ImageVector = Icons.Outlined.Storage,
	onAction: (() -> Unit)? = null,
	actionLabel: String = "Retry",
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
		color = MaterialTheme.colorScheme.surfaceVariant,
		shape = RoundedCornerShape(8.dp),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				modifier = Modifier.size(16.dp),
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.size(8.dp))
			Text(
				text = text,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.weight(1f),
			)
			if (onAction != null) {
				TextButton(onClick = onAction) { Text(actionLabel) }
			}
		}
	}
}

/**
 * A provider error in words a user can act on.
 *
 * Deliberately does not print the exception. "The provider returned 503" is the log's business.
 */
fun ProviderError.userMessage(): String = when (this) {
	is ProviderError.Offline -> "No connection. Anything already downloaded is still available."
	is ProviderError.Timeout -> "The card database did not answer in time."
	is ProviderError.RateLimited -> "The card database asked us to slow down. Try again shortly."
	is ProviderError.ServerError -> "The card database is having trouble. Try again shortly."
	is ProviderError.BadRequest -> "The card database rejected that request."
	is ProviderError.MalformedResponse -> "The card database sent something this app could not read."
	is ProviderError.Unknown -> "Something went wrong loading cards."
}
