package com.bitsycore.tcgexplorer.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitsycore.tcgexplorer.ui.preview.PreviewFrame
import java.io.File
import kotlin.math.abs
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/*
A second study: shape, not type.

The first one kept the rounded card and moved the words around. This one asks what a row can *be* --
a stack, a ticket, a spine, a wash -- and whether a set list and a card list want the same thing.
Every variant carries the same facts as the last study so the difference on screen is the form.

Colour is hashed from the set code, the way `setColour` already does it, so a list of these is a
list of distinguishable things rather than one colour repeated.

	./gradlew :composeApp:desktopTest --tests '*RowShapes*'
*/
class RowShapes {

	@Test
	@Ignore("A drawing tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the shape study to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()

		renderToPng(vOut, "shapes-sets", width = 780, height = 1500, density = 1.65f) {
			PreviewFrame {
				Column(Modifier.padding(16.dp)) {
					Label("S1 — stack: a set is a pile of cards, so the row is one")
					StackRow("OGN", "Origins", 352, "Oct 2025")
					Gap()

					Label("S2 — stub: the code is torn off down a perforation")
					StubRow("OGN", "Origins", 352, "Oct 2025")
					Gap()

					Label("S3 — spine: no card, a coloured edge and a hairline. Densest of the six")
					Column {
						SpineRow("VEN", "Vendetta", 358, "Jul 2026")
						SpineRow("UNL", "Unleashed", 280, "May 2026")
						SpineRow("OGN", "Origins", 352, "Oct 2025")
						SpineRow("PR", "Riftbound Promotional Cards", null, null)
					}
					Gap()

					Label("S4 — chamfer: the cut corner of a sleeved card, code in the cut")
					ChamferRow("SFD", "Spiritforged", 288, "Feb 2026")
					Gap()

					Label("S5 — wash: the tint bleeds in from the leading edge, no tile")
					WashRow("OGS", "Origins: Proving Grounds", 24, "Oct 2025")
					Gap()

					Label("S6 — meter: how much of the set is downloaded, drawn behind the words")
					Column {
						MeterRow("VEN", "Vendetta", 358, "Jul 2026", held = 358)
						Spacer(Modifier.height(8.dp))
						MeterRow("UNL", "Unleashed", 280, "May 2026", held = 96)
						Spacer(Modifier.height(8.dp))
						MeterRow("SFD", "Spiritforged", 288, "Feb 2026", held = 0)
					}
				}
			}
		}

		renderToPng(vOut, "shapes-cards", width = 780, height = 1180, density = 1.65f) {
			PreviewFrame {
				Column(Modifier.padding(16.dp)) {
					Label("C1 — bleed: the art is the left edge, not a picture sitting on a card")
					BleedRow("001", "Annie - Fiery Personality", "Epic", "Fury", 5)
					Gap()

					Label("C2 — stripe: rarity as an edge, so a column of rarities is readable")
					Column {
						StripeRow("001", "Annie - Fiery Personality", "Epic", "Fury", 5)
						StripeRow("002", "Firestorm", "Common", "Fury", 2)
						StripeRow("003", "Incinerate", "Rare", "Fury", 3)
					}
					Gap()

					Label("C3 — cost notched into the art, the way the card prints it")
					NotchRow("004", "Master Yi - Wuju Bladesman", "Rare", "Order", 7)
					Gap()

					Label("C4 — hairline: no card at all, art bleeding, maximum density")
					Column {
						HairRow("001", "Annie - Fiery Personality", "Epic", "Fury", 5)
						HairRow("002", "Firestorm", "Common", "Fury", 2)
						HairRow("007a", "Garen, Might of Demacia — Signature Edition", null, "Order", 6)
					}
				}
			}
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
		println("Wrote ${vOut.absolutePath}")
	}
}

// ==================
// MARK: Set shapes
// ==================

/** S1. Two offset layers behind the row, so it reads as a stack seen from above. */
@Composable
private fun StackRow(code: String, name: String, count: Int?, date: String?) {
	val vTint = tintOf(code)
	Box(Modifier.fillMaxWidth().padding(end = 10.dp, bottom = 10.dp)) {
		// The sheets underneath. Progressively dimmer, so depth reads without a shadow.
		Box(
			Modifier.matchRowSize().offset(x = 10.dp, y = 10.dp)
				.clip(RoundedCornerShape(12.dp))
				.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
		)
		Box(
			Modifier.matchRowSize().offset(x = 5.dp, y = 5.dp)
				.clip(RoundedCornerShape(12.dp))
				.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
		)
		Row(
			Modifier.fillMaxWidth()
				.clip(RoundedCornerShape(12.dp))
				.background(MaterialTheme.colorScheme.surfaceVariant)
				.padding(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Tile(code, vTint)
			Spacer(Modifier.width(12.dp))
			Facts(name, count, code)
			DateRail(date)
		}
	}
}

/** S2. A ticket: the code is on a stub, cut off down a perforation. */
@Composable
private fun StubRow(code: String, name: String, count: Int?, date: String?) {
	val vTint = tintOf(code)
	Row(
		Modifier.fillMaxWidth()
			.clip(RoundedCornerShape(12.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.height(72.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			Modifier.width(66.dp).fillMaxHeight().background(vTint.copy(alpha = 0.22f)),
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = code.take(6).uppercase(),
				fontSize = 12.sp,
				fontWeight = FontWeight.Bold,
				fontFamily = FontFamily.Monospace,
				color = vTint,
			)
		}
		// The perforation. Drawn rather than a divider, because a dashed line is the thing that
		// makes a stub read as torn rather than as two panels.
		Canvas(Modifier.width(1.dp).fillMaxHeight()) {
			drawLine(
				color = Color.Black.copy(alpha = 0.35f),
				start = Offset(0f, 6f),
				end = Offset(0f, size.height - 6f),
				strokeWidth = size.width,
				pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
			)
		}
		Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
			Facts(name, count, code)
			DateRail(date)
		}
	}
}

/** S3. No card: a coloured spine, a hairline under it, and nothing else. */
@Composable
private fun SpineRow(code: String, name: String, count: Int?, date: String?) {
	val vTint = tintOf(code)
	Column {
		Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(vTint))
			Spacer(Modifier.width(12.dp))
			Facts(name, count, code)
			DateRail(date)
		}
		Box(
			Modifier.fillMaxWidth().height(1.dp)
				.background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
		)
	}
}

/** S4. A sleeved card's cut corner, with the code sitting in the cut. */
@Composable
private fun ChamferRow(code: String, name: String, count: Int?, date: String?) {
	val vTint = tintOf(code)
	Row(
		Modifier.fillMaxWidth()
			.clip(CHAMFER)
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(52.dp)) {
			Text(
				text = code.take(6).uppercase(),
				fontSize = 12.sp,
				fontWeight = FontWeight.Bold,
				fontFamily = FontFamily.Monospace,
				color = vTint,
			)
		}
		Spacer(Modifier.width(8.dp))
		Facts(name, count, code)
		DateRail(date)
	}
}

/** S5. The tint bleeds in from the leading edge and fades out; no tile at all. */
@Composable
private fun WashRow(code: String, name: String, count: Int?, date: String?) {
	val vTint = tintOf(code)
	Row(
		Modifier.fillMaxWidth()
			.clip(RoundedCornerShape(12.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.background(
				Brush.horizontalGradient(
					0f to vTint.copy(alpha = 0.34f),
					0.45f to Color.Transparent,
				),
			)
			.padding(14.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = code.take(6).uppercase(),
			fontSize = 13.sp,
			fontWeight = FontWeight.Bold,
			fontFamily = FontFamily.Monospace,
			color = vTint,
		)
		Spacer(Modifier.width(14.dp))
		Facts(name, count, code)
		DateRail(date)
	}
}

/** S6. The row's own background says how much of the set is on the device. */
@Composable
private fun MeterRow(code: String, name: String, count: Int?, date: String?, held: Int) {
	val vTint = tintOf(code)
	val vFraction = if (count == null || count == 0) 0f else (held.toFloat() / count).coerceIn(0f, 1f)
	Box(
		Modifier.fillMaxWidth()
			.clip(RoundedCornerShape(12.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant),
	) {
		// Behind the words rather than under them: a progress bar is another row of furniture, and
		// this is the row itself saying how full it is.
		Box(Modifier.fillMaxHeight().fillMaxWidth(vFraction).background(vTint.copy(alpha = 0.20f)))
		Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
			Tile(code, vTint)
			Spacer(Modifier.width(12.dp))
			Facts(name, count, code)
			Text(
				text = when {
					vFraction >= 1f -> "all"
					vFraction <= 0f -> date ?: "—"
					else -> "$held/$count"
				},
				style = MaterialTheme.typography.labelMedium,
				fontWeight = if (vFraction > 0f) FontWeight.Medium else FontWeight.Normal,
				color = if (vFraction > 0f) vTint else MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

// ==================
// MARK: Card shapes
// ==================

/** C1. The art is the row's leading edge: no inset, no second rounding. */
@Composable
private fun BleedRow(number: String, name: String, rarity: String?, domain: String, cost: Int) {
	Row(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.height(68.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(50.dp).fillMaxHeight().background(artPlaceholder()))
		Spacer(Modifier.width(12.dp))
		CardFacts(number, name, rarity, domain, cost, Modifier.weight(1f))
		Spacer(Modifier.width(12.dp))
	}
}

/** C2. Rarity as a vertical stripe, so a column of them can be read down the list. */
@Composable
private fun StripeRow(number: String, name: String, rarity: String?, domain: String, cost: Int) {
	Row(
		Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.height(62.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(5.dp).fillMaxHeight().background(rarityColour(rarity)))
		Spacer(Modifier.width(10.dp))
		Box(Modifier.width(34.dp).height(48.dp).clip(RoundedCornerShape(3.dp)).background(artPlaceholder()))
		Spacer(Modifier.width(10.dp))
		CardFacts(number, name, rarity, domain, cost, Modifier.weight(1f))
		Spacer(Modifier.width(10.dp))
	}
}

/** C3. The cost sits on the art, where the card prints it. */
@Composable
private fun NotchRow(number: String, name: String, rarity: String?, domain: String, cost: Int) {
	Row(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.height(68.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(52.dp).fillMaxHeight()) {
			Box(Modifier.fillMaxSize().background(artPlaceholder()))
			Box(
				Modifier.align(Alignment.TopStart).offset(x = 6.dp, y = 6.dp)
					.size(22.dp).clip(RoundedCornerShape(11.dp))
					.background(MaterialTheme.colorScheme.primary),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = "$cost",
					fontSize = 11.sp,
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onPrimary,
				)
			}
		}
		Spacer(Modifier.width(12.dp))
		CardFacts(number, name, rarity, domain, cost = null, modifier = Modifier.weight(1f))
		Spacer(Modifier.width(12.dp))
	}
}

/** C4. No card, no padding to speak of: as many rows on screen as will fit. */
@Composable
private fun HairRow(number: String, name: String, rarity: String?, domain: String, cost: Int) {
	Column {
		Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.width(30.dp).height(42.dp).clip(RoundedCornerShape(2.dp)).background(artPlaceholder()))
			Spacer(Modifier.width(10.dp))
			Box(Modifier.width(38.dp)) { Mono2(number) }
			Text(
				text = name,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				maxLines = 1,
				modifier = Modifier.weight(1f),
			)
			if (rarity != null) {
				Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(rarityColour(rarity)))
				Spacer(Modifier.width(8.dp))
			}
			Text(
				text = "$cost",
				style = MaterialTheme.typography.labelMedium,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.primary,
			)
		}
		Box(
			Modifier.fillMaxWidth().height(1.dp)
				.background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
		)
	}
}

// ==================
// MARK: Parts
// ==================

/** Name over a facts line. Shared so the six shapes differ only in shape. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Facts(
	name: String,
	count: Int?,
	code: String,
) {
	Column(Modifier.weight(1f)) {
		Text(
			text = name,
			style = MaterialTheme.typography.titleSmall,
			fontWeight = FontWeight.Medium,
			maxLines = 1,
		)
		Spacer(Modifier.height(3.dp))
		Row(verticalAlignment = Alignment.CenterVertically) {
			if (count != null) {
				Text(
					text = "$count",
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.primary,
				)
				Quiet(" cards · ")
			} else {
				Quiet("size unknown · ")
			}
			Mono2(code)
		}
	}
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DateRail(date: String?) {
	Spacer(Modifier.width(8.dp))
	Text(
		text = date ?: "—",
		style = MaterialTheme.typography.labelMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant
			.copy(alpha = if (date == null) 0.5f else 1f),
	)
}

@Composable
private fun CardFacts(
	number: String,
	name: String,
	rarity: String?,
	domain: String,
	cost: Int?,
	modifier: Modifier = Modifier,
) {
	Column(modifier) {
		Row(verticalAlignment = Alignment.Bottom) {
			Mono2(number)
			Spacer(Modifier.width(8.dp))
			Text(
				text = name,
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Medium,
				maxLines = 1,
			)
		}
		Spacer(Modifier.height(3.dp))
		Row(verticalAlignment = Alignment.CenterVertically) {
			if (rarity != null) {
				Text(
					text = rarity,
					fontSize = 11.sp,
					fontWeight = FontWeight.Medium,
					color = rarityColour(rarity),
				)
				Quiet(" · ")
			}
			Quiet(domain)
			if (cost != null) {
				Quiet(" · ")
				Text(
					text = "$cost",
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.primary,
				)
				Quiet(" energy")
			}
		}
	}
}

@Composable
private fun Tile(code: String, tint: Color) = Box(
	modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.22f)),
	contentAlignment = Alignment.Center,
) {
	Text(
		text = code.take(6).uppercase(),
		fontSize = 11.sp,
		fontWeight = FontWeight.SemiBold,
		color = tint,
	)
}

@Composable
private fun Quiet(text: String) = Text(
	text = text,
	style = MaterialTheme.typography.labelMedium,
	color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun Mono2(text: String) = Text(
	text = text,
	style = MaterialTheme.typography.labelMedium,
	fontFamily = FontFamily.Monospace,
	color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun Label(text: String) = Text(
	text = text,
	style = MaterialTheme.typography.labelSmall,
	color = MaterialTheme.colorScheme.primary,
	modifier = Modifier.padding(bottom = 6.dp),
)

@Composable
private fun Gap() = Spacer(Modifier.height(20.dp))

@Composable
private fun artPlaceholder(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)

/** The stack's lower sheets need the row's size without knowing it; 62dp is the row at this type. */
private fun Modifier.matchRowSize(): Modifier = this.fillMaxWidth().height(62.dp)

/** One corner cut, like a sleeved card's. */
private val CHAMFER: Shape = GenericShape { size, _ ->
	val vCut = size.height * 0.42f
	moveTo(vCut, 0f)
	lineTo(size.width - 12f, 0f)
	quadraticTo(size.width, 0f, size.width, 12f)
	lineTo(size.width, size.height - 12f)
	quadraticTo(size.width, size.height, size.width - 12f, size.height)
	lineTo(12f, size.height)
	quadraticTo(0f, size.height, 0f, size.height - 12f)
	lineTo(0f, vCut)
	close()
}

/**
 * A colour per set code, stable across runs.
 *
 * The same trick `setColour` uses in the real list: a hash into a fixed wheel, so two sets are
 * reliably different and the same set is reliably itself.
 */
private fun tintOf(code: String): Color {
	val vWheel = listOf(
		Color(0xFF8B7BF0), Color(0xFF4FA3E3), Color(0xFF46B98A),
		Color(0xFFD99A3C), Color(0xFFD1657A), Color(0xFF6FBF5E),
	)
	return vWheel[abs(code.hashCode()) % vWheel.size]
}

private fun rarityColour(rarity: String?): Color = when (rarity) {
	"Common" -> Color(0xFF8A8A97)
	"Rare" -> Color(0xFF4FA3E3)
	"Epic" -> Color(0xFFB07BF0)
	else -> Color(0xFF6B6B77)
}
