import io
import re


def patch(p, pairs):
	s = io.open(p, encoding="utf-8").read()
	for old, new in pairs:
		assert s.count(old) >= 1, (p, repr(old[:80]), s.count(old))
		s = s.replace(old, new)
	io.open(p, "w", encoding="utf-8", newline="\n").write(s)


# ---------------------------------------------------------------- App.kt
patch("composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/App.kt", [
("							onOpenGame = { vGame -> vBackStack.add(Route.Sets(vGame.id.value)) },",
 "							onOpenGame = { vGame -> vBackStack.add(Route.Sets(vGame.id.value)) },"),
])
s = io.open("composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/App.kt", encoding="utf-8").read()
s = re.sub(
	r"\t*// A route naming a game this build no longer routes falls back rather\n"
	r"\t*// than crashing on a restored back stack\.\n"
	r"\t*game = Game\.entries\.firstOrNull \{ it\.name == vRoute\.game \}\n"
	r"\t*\?: Game\.RIFTBOUND,\n",
	"\t\t\t\t\t\t\t// A route naming a game this build no longer routes resolves to null in the\n"
	"\t\t\t\t\t\t\t// view model, which then has nothing to load. It used to fall back to\n"
	"\t\t\t\t\t\t\t// Riftbound, which silently opened the wrong game on a restored back stack.\n"
	"\t\t\t\t\t\t\tgame = GameId(vRoute.game),\n",
	s,
)
if "import com.bitsycore.cardbrowser.core.model.GameId" not in s:
	s = s.replace(
		"import com.bitsycore.cardbrowser.core.game.GameProfile\n",
		"import com.bitsycore.cardbrowser.core.model.GameId\n",
	)
s = s.replace("import com.bitsycore.cardbrowser.core.game.GameProfile\n", "")
io.open("composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/App.kt", "w", encoding="utf-8", newline="\n").write(s)

# ---------------------------------------------------------------- game picker signature
patch("composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/games/GameListScreen.kt", [
("	onOpenGame: (Game) -> Unit,", "	onOpenGame: (GameProfile) -> Unit,"),
("	onOpenGame: (Game) -> Unit = {},", "	onOpenGame: (GameProfile) -> Unit = {},"),
("import androidx.compose.material.icons.outlined.Style\n",
 "import androidx.compose.material.icons.outlined.Settings\nimport androidx.compose.material.icons.outlined.Style\n"),
])

# ---------------------------------------------------------------- game picker view model
patch("composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/games/GameListViewModel.kt", [
("""		// Ordered as `Game.entries` declares them rather than as the routes happen to be listed, so
		// the picker's order is stable across builds and reorderings of the routing table.
		val vGames = Game.entries.filter { it in mRegistry.games }
		val vSources = vGames.mapNotNull { vGame ->
			mRegistry.resolve(vGame)?.let { vGame to it.displayName }
		}.toMap()""",
 """		// Straight from the registry, which orders them as the routing table lists them. There is
		// no enum to order by any more, and that is the point: the routing table is now the only
		// statement anywhere of which games this build offers.
		val vGames = mRegistry.games
		val vSources = vGames.mapNotNull { vGame ->
			mRegistry.resolve(vGame)?.let { vGame to it.displayName }
		}.toMap()"""),
("				?.let { vName -> vGames.firstOrNull { it.name == vName } }",
 "				?.let { vId -> vGames.firstOrNull { it.id.value == vId } }"),
])

# ---------------------------------------------------------------- card detail
patch("composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/detail/CardDetailContract.kt", [
("""		fun cardmarketLinkFor(card: CardPrinting): CardmarketLink? =
			CardmarketLinkBuilder.linkFor(card, set)""",
 """		fun cardmarketLinkFor(card: CardPrinting): CardmarketLink? =
			game?.let { CardmarketLinkBuilder.linkFor(card, set, it) }"""),
("			val vWords = (card.game?.vocabulary ?: GameVocabulary())",
 "			val vWords = game?.vocabulary ?: GameVocabulary()"),
("				vWords.energy?.let { vLabel -> fact(vLabel, card.attributes.cost?.toString()) }\n"
 "				fact(\"Might\", card.attributes.primary?.toString())\n"
 "				fact(\"Power\", card.attributes.secondary?.toString())",
 "				vWords.cost?.let { vLabel -> fact(vLabel, card.attributes.cost?.toString()) }\n"
 "				vWords.primaryStat?.let { vLabel -> fact(vLabel, card.attributes.primary?.toString()) }\n"
 "				vWords.secondaryStat?.let { vLabel -> fact(vLabel, card.attributes.secondary?.toString()) }"),
("		/** The source's own name, for the details table. `null` before the load completes. */\n"
 "		val providerDisplayName: String? = null,",
 "		/** The source's own name, for the details table. `null` before the load completes. */\n"
 "		val providerDisplayName: String? = null,\n"
 "		/**\n"
 "		 * The game these cards belong to, and everything the app knows about it.\n"
 "		 *\n"
 "		 * Its vocabulary is what labels the stat rows, and its Cardmarket segment is what decides\n"
 "		 * whether there is a marketplace link at all. `null` until the load resolves a provider.\n"
 "		 */\n"
 "		val game: GameProfile? = null,"),
("			val providerLanguages: Set<CardLanguage> = emptySet(),\n"
 "			val providerDisplayName: String? = null,",
 "			val providerLanguages: Set<CardLanguage> = emptySet(),\n"
 "			val providerDisplayName: String? = null,\n"
 "			val game: GameProfile? = null,"),
("			providerLanguages = intent.providerLanguages,\n"
 "			providerDisplayName = intent.providerDisplayName,",
 "			providerLanguages = intent.providerLanguages,\n"
 "			providerDisplayName = intent.providerDisplayName,\n"
 "			game = intent.game,"),
("import com.bitsycore.cardbrowser.core.game.GameVocabulary",
 "import com.bitsycore.cardbrowser.core.game.GameProfile\nimport com.bitsycore.cardbrowser.core.game.GameVocabulary"),
])

patch("composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/detail/CardDetailScreen.kt", [
("			val vWords = remember(card.game) { (card.game?.vocabulary ?: GameVocabulary()) }",
 "			val vWords = state.game?.vocabulary ?: GameVocabulary()"),
("""				card.attributes.cost?.let { vEnergy ->
					StatChip(vWords.energy?.let { "$it $vEnergy" } ?: "$vEnergy")
				}
				card.attributes.primary?.let { StatChip("Might $it") }
				card.attributes.secondary?.let { StatChip("Power $it") }""",
 """				card.attributes.cost?.let { vCost ->
					StatChip(vWords.cost?.let { "$it $vCost" } ?: "$vCost")
				}
				card.attributes.primary?.let { vStat ->
					StatChip(vWords.primaryStat?.let { "$it $vStat" } ?: "$vStat")
				}
				card.attributes.secondary?.let { vStat ->
					StatChip(vWords.secondaryStat?.let { "$it $vStat" } ?: "$vStat")
				}"""),
])

# ---------------------------------------------------------------- detail view model
patch("composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/detail/CardDetailViewModel.kt", [
("""		// The game comes from the card's own id. Every id here is source-qualified, so it names the
		// provider that issued it, and the routing table turns that into a game -- which is why no
		// game argument travels through navigation alongside the card id.
		val vGame = mRegistry.gameFor(vCardId.provider) ?: Game.RIFTBOUND""",
 """		// The game comes from the card's own id. Every id here is source-qualified, so it names the
		// provider that issued it, and that provider names its game -- which is why no game argument
		// travels through navigation alongside the card id.
		//
		// A card id naming a provider this build does not register has no game, and there is nothing
		// to load. Returning beats guessing at one.
		val vGame = mRegistry.gameFor(vCardId.provider) ?: return"""),
("				.cardDetail(id = vCardId, game = vGame, setId = vSetId)",
 "				.cardDetail(id = vCardId, game = vGame.id, setId = vSetId)"),
("			mRepository.setList(vGame).first().value?.firstOrNull { it.id == vSetId }",
 "			mRepository.setList(vGame.id).first().value?.firstOrNull { it.id == vSetId }"),
("					providerDisplayName = vProvider?.displayName,",
 "					providerDisplayName = vProvider?.displayName,\n					game = vGame,"),
("			.cards(setId = setId, game = game, query = CardQuery())",
 "			.cards(setId = setId, game = game.id, query = CardQuery())"),
("			.cards(setId = vSetId, game = vCard.game, query = CardQuery(), language = language)",
 "			.cards(setId = vSetId, game = vCard.game, query = CardQuery(), language = language)"),
])

print("ui pass C done")
