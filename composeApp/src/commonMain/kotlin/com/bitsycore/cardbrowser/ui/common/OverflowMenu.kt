package com.bitsycore.cardbrowser.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The three places this app can go that are not a card: settings, storage, downloads.
 *
 * One menu rather than a row of icons. The bars it sits in already carry actions that belong to
 * what is on screen -- download these sets, search them, reorder them -- and three more for
 * *leaving* crowded them out. A gear, a downloads button and a storage entry with no home is four
 * taps' worth of chrome for something used occasionally.
 *
 * @param activeDownloads how many jobs are running. Shown as a badge on the menu itself, because
 *   folding the downloads button in would otherwise have taken the only at-a-glance sign that
 *   something is being fetched. Zero draws no badge
 */
@Composable
fun AppOverflowMenu(
	onOpenSettings: () -> Unit,
	onOpenStorage: () -> Unit,
	onOpenDownloads: () -> Unit,
	activeDownloads: Int = 0,
) {
	var vOpen by remember { mutableStateOf(false) }

	BadgedBox(badge = { if (activeDownloads > 0) Badge { Text(activeDownloads.toString()) } }) {
		IconButton(onClick = { vOpen = true }) {
			Icon(
				imageVector = Icons.Outlined.MoreVert,
				contentDescription = if (activeDownloads > 0) {
					"More, $activeDownloads downloads in progress"
				} else {
					"More"
				},
			)
		}
	}
	DropdownMenu(expanded = vOpen, onDismissRequest = { vOpen = false }) {
		DropdownMenuItem(
			text = { Text("Settings") },
			onClick = {
				vOpen = false
				onOpenSettings()
			},
		)
		DropdownMenuItem(
			text = { Text("Manage storage") },
			onClick = {
				vOpen = false
				onOpenStorage()
			},
		)
		DropdownMenuItem(
			// The count again, where there is room to say what it means.
			text = {
				Text(if (activeDownloads > 0) "Downloads ($activeDownloads running)" else "Downloads")
			},
			onClick = {
				vOpen = false
				onOpenDownloads()
			},
		)
	}
}
