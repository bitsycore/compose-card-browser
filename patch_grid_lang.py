import io


def patch(p, pairs):
	s = io.open(p, encoding="utf-8").read()
	for old, new in pairs:
		assert s.count(old) == 1, (p, repr(old[:90]), s.count(old))
		s = s.replace(old, new)
	io.open(p, "w", encoding="utf-8", newline="\n").write(s)


C = "composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/cards/CardGridContract.kt"
patch(C, [
("""		/** Restored when coming back from detail, so the grid returns to where it was. */
		val firstVisibleIndex: Int = 0,
	) {""",
"""		/** Restored when coming back from detail, so the grid returns to where it was. */
		val firstVisibleIndex: Int = 0,
		/**
		 * Which edition of the set is on screen.
		 *
		 * Seeded from the user's preference and changeable here, because the set is the natural
		 * place to change it: the detail screen could already switch language, but the only way to
		 * browse a set in another one was to change the global preference and come back.
		 */
		val language: CardLanguage? = null,
		/** Every language the source behind this set can be asked for. Empty until resolved. */
		val availableLanguages: Set<CardLanguage> = emptySet(),
		/** The language to fall back to when a switch turns out to be impossible. */
		val previousLanguage: CardLanguage? = null,
		/** True while a chosen language is being fetched, so the control can show it is busy. */
		val isChangingLanguage: Boolean = false,
	) {

		/**
		 * The languages worth offering, in the app's preference order.
		 *
		 * A single-language source gets no control at all: a menu with one item that is already
		 * selected is furniture.
		 */
		val languageOptions: List<CardLanguage>
			get() = if (availableLanguages.size <= 1) {
				emptyList()
			} else {
				CardLanguage.PREFERENCE_ORDER.filter { it in availableLanguages }
			}"""),
("""		data class CapabilitiesResolved(
			val supportedFilters: Set<com.bitsycore.cardbrowser.core.provider.CardFilterField>,
			/** Which game's words the filter sheet should use. See [GameVocabulary]. */
			val game: GameProfile,
		) : Intent
	}

	sealed interface Effect""",
"""		data class CapabilitiesResolved(
			val supportedFilters: Set<com.bitsycore.cardbrowser.core.provider.CardFilterField>,
			/** Which game's words the filter sheet should use. See [GameVocabulary]. */
			val game: GameProfile,
			val languages: Set<CardLanguage>,
			val language: CardLanguage?,
		) : Intent

		/** The user picked another edition of this set. */
		data class LanguageSelected(val language: CardLanguage) : Intent

		/**
		 * The chosen language could not be shown, so the previous one is restored.
		 *
		 * Not every source can answer for every set. TCGdex keys each locale by its own set ids --
		 * the English `base1` is `PMCG1` in Japanese and absent from Korean -- so there is no
		 * Korean edition of an English Pokémon set to fetch. Leaving the user on an empty grid
		 * would be worse than not offering the switch.
		 */
		data object LanguageUnavailable : Intent
	}

	sealed interface Effect {

		/** Shown when a chosen language has nothing for this set, so the tap is not silently lost. */
		data class LanguageUnavailable(val language: CardLanguage, val reason: String) : Effect
	}"""),
("""		is Intent.CapabilitiesResolved -> state.copy(
			supportedFilters = intent.supportedFilters,
			game = intent.game,
		)""",
"""		is Intent.CapabilitiesResolved -> state.copy(
			supportedFilters = intent.supportedFilters,
			game = intent.game,
			availableLanguages = intent.languages,
			// Only seeded, never overwritten: a resolve that lands after the user has already
			// chosen must not undo their choice.
			language = state.language ?: intent.language,
		)

		// The cards on screen are kept while the new edition loads. Blanking the grid to a spinner
		// makes a switch that turns out to be impossible look like one that destroyed the set.
		is Intent.LanguageSelected -> if (intent.language == state.language) {
			state
		} else {
			state.copy(
				language = intent.language,
				previousLanguage = state.language,
				isChangingLanguage = true,
				isLoading = true,
				error = null,
				requestGeneration = state.requestGeneration + 1,
			)
		}

		Intent.LanguageUnavailable -> state.copy(
			language = state.previousLanguage ?: state.language,
			previousLanguage = null,
			isChangingLanguage = false,
			isLoading = false,
		)"""),
])

s = io.open(C, encoding="utf-8").read()
if "import com.bitsycore.cardbrowser.core.model.CardLanguage" not in s:
	s = s.replace(
		"import com.bitsycore.cardbrowser.core.game.GameProfile",
		"import com.bitsycore.cardbrowser.core.game.GameProfile\nimport com.bitsycore.cardbrowser.core.model.CardLanguage",
	)
io.open(C, "w", encoding="utf-8", newline="\n").write(s)

# Loaded must clear the in-flight flag, wherever it is handled.
s = io.open(C, encoding="utf-8").read()
old = "		is Intent.Loaded -> if (intent.generation != state.requestGeneration) {"
assert s.count(old) == 1
s = s.replace(old, "		is Intent.Loaded -> if (intent.generation != state.requestGeneration) {")
io.open(C, "w", encoding="utf-8", newline="\n").write(s)

print("contract ok")
