package com.bitsycore.cardbrowser.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.ui.common.AppIcons
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws every icon, so a generated file can be looked at rather than trusted.
 *
 * The generator copies path data it never parses, onto a 960 grid with a negative origin that has
 * to be translated back. Both are the kind of thing that produces a blank square or a glyph drawn
 * off the edge of its box -- neither of which stops anything compiling.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests '*IconRenderer*'
 * ```
 */
class IconRenderer {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNG.")
	fun `writes every icon to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()
		val vIcons: List<Pair<String, ImageVector>> = listOf(
			"ArrowBack" to AppIcons.ArrowBack,
			"ArrowDownward" to AppIcons.ArrowDownward,
			"ArrowDropDown" to AppIcons.ArrowDropDown,
			"ArrowUpward" to AppIcons.ArrowUpward,
			"BrokenImage" to AppIcons.BrokenImage,
			"Check" to AppIcons.Check,
			"CheckCircle" to AppIcons.CheckCircle,
			"CheckCircleFilled" to AppIcons.CheckCircleFilled,
			"ChevronLeft" to AppIcons.ChevronLeft,
			"ChevronRight" to AppIcons.ChevronRight,
			"CloudDownload" to AppIcons.CloudDownload,
			"CloudOff" to AppIcons.CloudOff,
			"Close" to AppIcons.Close,
			"Delete" to AppIcons.Delete,
			"Description" to AppIcons.Description,
			"Download" to AppIcons.Download,
			"DownloadDone" to AppIcons.DownloadDone,
			"DragHandle" to AppIcons.DragHandle,
			"ErrorOutline" to AppIcons.ErrorOutline,
			"FilterList" to AppIcons.FilterList,
			"GridView" to AppIcons.GridView,
			"Inbox" to AppIcons.Inbox,
			"KeyboardArrowRight" to AppIcons.KeyboardArrowRight,
			"MoreVert" to AppIcons.MoreVert,
			"OpenInNew" to AppIcons.OpenInNew,
			"Refresh" to AppIcons.Refresh,
			"Search" to AppIcons.Search,
			"SearchOff" to AppIcons.SearchOff,
			"StarBorder" to AppIcons.StarBorder,
			"StarFilled" to AppIcons.StarFilled,
			"Storage" to AppIcons.Storage,
			"Style" to AppIcons.Style,
			"TravelExplore" to AppIcons.TravelExplore,
			"Tune" to AppIcons.Tune,
			"Visibility" to AppIcons.Visibility,
			"VisibilityOff" to AppIcons.VisibilityOff,
		)

		renderToPng(vOut, "icons", width = 900, height = 1060, density = 1.65f) {
			Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
				vIcons.chunked(4).forEach { vRow ->
					Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
						vRow.forEach { (vName, vIcon) ->
							Column(
								horizontalAlignment = Alignment.CenterHorizontally,
								modifier = Modifier.size(width = 132.dp, height = 56.dp),
							) {
								Icon(vIcon, contentDescription = null, modifier = Modifier.size(28.dp))
								Text(vName, style = MaterialTheme.typography.labelSmall)
							}
						}
					}
				}
			}
		}

		assertTrue(File(vOut, "icons.png").length() > 0, "nothing was rendered")
	}
}
