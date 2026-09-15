package com.bitsycore.tcgexplorer.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitsycore.tcgexplorer.ui.preview.PreviewFrame
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/*
A study of the list rows, drawn rather than described.

Not production code and not a preview of it: each variant is self-contained here so the real screens
are untouched while a shape is being chosen. Once one is picked it moves into `SetListScreen` and
`CardGridScreen` and this file goes.

The brief: more facts per row, using vertical space, a smaller name, weight and colour to separate
them -- for a set, the card count, the release date, the code and the languages.

	./gradlew :composeApp:desktopTest --tests '*RowStudy*'
*/
class RowStudy {

	@Test
	@Ignore("A drawing tool, not a check. Remove the annotation to write the PNGs.")
	fun `writes the row study to build slash render`() {
		val vOut = File("build/render")
		vOut.mkdirs()

		renderToPng(vOut, "rows-sets", width = 760, height = 1180, density = 1.65f) {
			PreviewFrame {
				Column(Modifier.padding(16.dp)) {
					Label("Now — one line, name at bodyLarge")
					SetRowToday()
					Gap()

					Label("A — leading code tile, facts as a typed line")
					SetRowA()
					Gap()

					Label("B — the count as the number, everything else beneath it")
					SetRowB()
					Gap()

					Label("C — two tiers, a right rail for the date")
					SetRowC()
					Gap()

					Label("C, the awkward cases: no count, no date, four languages")
					SetRowC(code = "PR", name = "Riftbound Promotional Cards", count = null, date = null, languages = listOf())
					Spacer(Modifier.height(8.dp))
					SetRowC(code = "sv08", name = "Surging Sparks", count = 252, date = "8 Nov 2024", languages = listOf("EN", "FR", "DE", "JA"))
				}
			}
		}

		renderToPng(vOut, "rows-cards", width = 760, height = 980, density = 1.65f) {
			PreviewFrame {
				Column(Modifier.padding(16.dp)) {
					Label("Now — name at bodyLarge, number and chips below")
					CardRowToday()
					Gap()

					Label("A — number leads in mono, name smaller, stats tinted")
					CardRowA()
					Gap()

					Label("B — number as a left rail, two tiers of fact")
					CardRowB()
					Gap()

					Label("B, a long name and no rarity")
					CardRowB(
						number = "007a",
						name = "Garen, Might of Demacia — Signature Edition",
						rarity = null,
					)
				}
			}
		}

		assertTrue(vOut.listFiles().orEmpty().any { it.length() > 0 }, "nothing was rendered")
		println("Wrote ${vOut.absolutePath}")
	}
}

// ==================
// MARK: Set rows
// ==================

/** What ships today: one subtitle line, everything the same size and colour. */
@Composable
private fun SetRowToday() = StudyCard {
	Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Monogram("OGN")
		Spacer(Modifier.width(12.dp))
		Column(Modifier.weight(1f)) {
			Text("Origins", style = MaterialTheme.typography.bodyLarge)
			Text(
				text = "OGN · 352 cards · Oct 2025",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/**
 * A: the code becomes the leading tile, and the facts become a typed line.
 *
 * The count is the number a reader scans for, so it carries weight and the accent; the unit next to
 * it does not. The date stays muted because it is context rather than an answer.
 */
@Composable
private fun SetRowA() = StudyCard {
	Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Monogram("OGN")
		Spacer(Modifier.width(12.dp))
		Column(Modifier.weight(1f)) {
			Text(
				text = "Origins",
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Medium,
			)
			Spacer(Modifier.height(3.dp))
			Row(verticalAlignment = Alignment.CenterVertically) {
				Value("352")
				Unit_(" cards")
				Dot()
				Muted("Oct 2025")
				Dot()
				Mono("OGN")
				Spacer(Modifier.width(6.dp))
				Pin("EN")
			}
		}
	}
}

/**
 * B: the count is the loudest thing on the row and the name sits under it.
 *
 * Reads well when scanning for size, badly when scanning for a name -- which is the wrong way round
 * for a set list. Drawn because it is the obvious reading of "use verticality" and it is worth
 * seeing why it does not work.
 */
@Composable
private fun SetRowB() = StudyCard {
	Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Column(
			Modifier.width(56.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Text(
				text = "352",
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.primary,
			)
			Unit_("cards")
		}
		Spacer(Modifier.width(12.dp))
		Column(Modifier.weight(1f)) {
			Text("Origins", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
			Spacer(Modifier.height(3.dp))
			Row(verticalAlignment = Alignment.CenterVertically) {
				Mono("OGN")
				Dot()
				Muted("Oct 2025")
				Spacer(Modifier.width(6.dp))
				Pin("EN")
			}
		}
	}
}

/**
 * C: two tiers, with the date on a right rail.
 *
 * The name owns the first line at a smaller size than today. The second is the facts, ordered by
 * how often they are wanted: how many cards, then which code, then which languages. The date goes
 * right because it is what the list is *sorted* by -- a column of dates down one edge is readable
 * as a column, which a date buried mid-sentence is not.
 */
@Composable
private fun SetRowC(
	code: String = "OGN",
	name: String = "Origins",
	count: Int? = 352,
	date: String? = "Oct 2025",
	languages: List<String> = listOf("EN"),
) = StudyCard {
	Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Monogram(code)
		Spacer(Modifier.width(12.dp))
		Column(Modifier.weight(1f)) {
			Text(
				text = name,
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Medium,
			)
			Spacer(Modifier.height(3.dp))
			Row(verticalAlignment = Alignment.CenterVertically) {
				if (count != null) {
					Value("$count")
					Unit_(" cards")
				} else {
					// Absent, not zero: the source states no size. Saying nothing is the honest
					// shape and it also keeps the line from starting with a dot.
					Unit_("size unknown")
				}
				Dot()
				Mono(code)
				if (languages.isNotEmpty()) {
					Spacer(Modifier.width(6.dp))
					languages.take(3).forEach {
						Pin(it)
						Spacer(Modifier.width(3.dp))
					}
					if (languages.size > 3) Unit_("+${languages.size - 3}")
				}
			}
		}
		Spacer(Modifier.width(8.dp))
		// The sort key, in a column of its own.
		Text(
			text = date ?: "—",
			style = MaterialTheme.typography.labelMedium,
			color = if (date == null) {
				MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
		)
	}
}

// ==================
// MARK: Card rows
// ==================

@Composable
private fun CardRowToday() = StudyCard {
	Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Thumb()
		Spacer(Modifier.width(12.dp))
		Column(Modifier.weight(1f)) {
			Text("Annie - Fiery Personality", style = MaterialTheme.typography.bodyLarge)
			Spacer(Modifier.height(2.dp))
			Row {
				Muted("001/352")
				Spacer(Modifier.width(4.dp))
				Chip("Epic", MaterialTheme.colorScheme.tertiary)
				Spacer(Modifier.width(4.dp))
				Chip("Fury", MaterialTheme.colorScheme.error)
				Spacer(Modifier.width(4.dp))
				Chip("Unit", MaterialTheme.colorScheme.outline)
			}
		}
	}
}

/** A: the collector number leads, in mono, because it is what a list is scanned by. */
@Composable
private fun CardRowA() = StudyCard {
	Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Thumb()
		Spacer(Modifier.width(12.dp))
		Column(Modifier.weight(1f)) {
			Row(verticalAlignment = Alignment.Bottom) {
				Mono("001")
				Unit_("/352")
				Spacer(Modifier.width(8.dp))
				Text(
					text = "Annie - Fiery Personality",
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Medium,
				)
			}
			Spacer(Modifier.height(3.dp))
			Row(verticalAlignment = Alignment.CenterVertically) {
				Chip("Epic", MaterialTheme.colorScheme.tertiary)
				Spacer(Modifier.width(4.dp))
				Chip("Fury", MaterialTheme.colorScheme.error)
				Spacer(Modifier.width(4.dp))
				Unit_("Unit · Legend")
			}
		}
	}
}

/**
 * B: the number is a left rail of its own, and the row gets two tiers under the name.
 *
 * The rail is what makes a long list scannable -- every number starts at the same x, so the eye
 * runs down one column instead of hunting along each row.
 */
@Composable
private fun CardRowB(
	number: String = "001",
    name: String = "Annie - Fiery Personality",
	rarity: String? = "Epic",
) = StudyCard {
	Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Thumb()
		Spacer(Modifier.width(10.dp))
		Box(Modifier.width(40.dp)) { Mono(number) }
		Spacer(Modifier.width(6.dp))
		Column(Modifier.weight(1f)) {
			Text(
				text = name,
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Medium,
				maxLines = 1,
			)
			Spacer(Modifier.height(3.dp))
			Row(verticalAlignment = Alignment.CenterVertically) {
				if (rarity != null) {
					Chip(rarity, MaterialTheme.colorScheme.tertiary)
					Spacer(Modifier.width(4.dp))
				}
				Chip("Fury", MaterialTheme.colorScheme.error)
				Spacer(Modifier.width(6.dp))
				Unit_("Unit · 5 energy")
			}
		}
	}
}

// ==================
// MARK: Parts
// ==================

@Composable
private fun StudyCard(content: @Composable () -> Unit) {
	Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) { content() }
}

@Composable
private fun Label(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(bottom = 4.dp),
	)
}

@Composable
private fun Gap() = Spacer(Modifier.height(18.dp))

/** The number a reader is scanning for: weight and the accent colour. */
@Composable
private fun Value(text: String) = Text(
	text = text,
	style = MaterialTheme.typography.bodyMedium,
	fontWeight = FontWeight.SemiBold,
	color = MaterialTheme.colorScheme.primary,
)

/** The word attached to a number, and every other quiet thing. */
@Composable
private fun Unit_(text: String) = Text(
	text = text,
	style = MaterialTheme.typography.labelMedium,
	color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun Muted(text: String) = Text(
	text = text,
	style = MaterialTheme.typography.bodySmall,
	color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Codes and collector numbers: monospaced, so a column of them lines up. */
@Composable
private fun Mono(text: String) = Text(
	text = text,
	style = MaterialTheme.typography.labelMedium,
	fontFamily = FontFamily.Monospace,
	color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun Dot() = Text(
	text = " · ",
	style = MaterialTheme.typography.labelMedium,
	color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
)

/** A language: short, boxed, and the same width whatever it says. */
@Composable
private fun Pin(text: String) = Box(
	modifier = Modifier
		.clip(RoundedCornerShape(3.dp))
		.background(MaterialTheme.colorScheme.surfaceVariant)
		.padding(horizontal = 4.dp, vertical = 1.dp),
) {
	Text(
		text = text,
		fontSize = 10.sp,
		fontWeight = FontWeight.Medium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun Chip(text: String, tint: Color) = Box(
	modifier = Modifier
		.clip(RoundedCornerShape(4.dp))
		.background(tint.copy(alpha = 0.18f))
		.padding(horizontal = 5.dp, vertical = 1.dp),
) {
	Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = tint)
}

@Composable
private fun Monogram(code: String) = Box(
	modifier = Modifier
		.size(38.dp)
		.clip(RoundedCornerShape(8.dp))
		.background(MaterialTheme.colorScheme.surfaceVariant),
	contentAlignment = Alignment.Center,
) {
	Text(
		// Six, matching the real `SetMonogram`. Three truncated "sv08" to "SV0".
		text = code.take(6).uppercase(),
		fontSize = 11.sp,
		fontWeight = FontWeight.SemiBold,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun Thumb() = Box(
	modifier = Modifier
		.height(52.dp)
		.width(37.dp)
		.clip(RoundedCornerShape(4.dp))
		.background(MaterialTheme.colorScheme.surfaceVariant),
)
