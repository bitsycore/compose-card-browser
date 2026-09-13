package com.bitsycore.cardbrowser.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.bitsycore.cardbrowser.games.riftbound.RiftboundArt
import org.jetbrains.compose.resources.painterResource
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Does `Modifier.dropShadow` follow a logo's letterforms, or its bounding box?
 *
 * Written to answer that rather than argue it. `dropShadow` is the better API on every axis the
 * halo cares about -- one draw, a real blur, exact radius, colour, spread and offset -- so the only
 * question that matters is what shape it blurs. `SimpleDropShadowNode` builds a `DropShadowPainter`
 * from the `Shape` it is given and the draw size, and never reads the content, which predicts a
 * blurred rectangle behind a wordmark that is mostly transparent.
 *
 * **Answered: the bounding box.** `probe-dropshadow.png` is a solid black rectangle with blurred
 * edges sitting behind the wordmark -- which is precisely the plate the halo was brought in to
 * replace. The second row in the same picture is the mark with no shadow at all, where "LEAGUE OF
 * LEGENDS" disappears into the white, so both ends of the problem are in one file.
 *
 * Kept because the question is a reasonable one to ask again: `dropShadow` is one draw against the
 * halo's forty, a real blur against a stack of copies, and takes an exact radius, colour, spread
 * and offset. Every one of those is better. The geometry is the only thing that is not, and it is
 * the only thing that matters here.
 *
 * Kept as a tool, like the renderers beside it -- `@Ignore`d, so remove the annotation to run it.
 */
class DropShadowProbe {

	@Test
	@Ignore("A rendering tool, not a check. Remove the annotation to write the PNG.")
	fun `writes a dropShadow logo to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()
		renderToPng(vOut, "probe-dropshadow", width = 400, height = 240, isDark = false) {
			Column {
				LogoWithDropShadow()
				LogoWithoutShadow()
			}
		}
		println("Wrote ${vOut.absolutePath}")
	}

	@Composable
	private fun LogoWithDropShadow() {
		Box(Modifier.padding(16.dp)) {
			Image(
				painter = painterResource(RiftboundArt.logo),
				contentDescription = null,
				contentScale = ContentScale.Fit,
				modifier = Modifier
					.size(width = 160.dp, height = 46.dp)
					.dropShadow(
						shape = RectangleShape,
						shadow = Shadow(
							radius = 6.dp,
							color = Color.Black,
							offset = DpOffset(0.dp, 3.dp),
						),
					),
			)
		}
	}

	@Composable
	private fun LogoWithoutShadow() {
		Box(Modifier.padding(16.dp)) {
			Image(
				painter = painterResource(RiftboundArt.logo),
				contentDescription = null,
				contentScale = ContentScale.Fit,
				modifier = Modifier.size(width = 160.dp, height = 46.dp),
			)
		}
	}
}
