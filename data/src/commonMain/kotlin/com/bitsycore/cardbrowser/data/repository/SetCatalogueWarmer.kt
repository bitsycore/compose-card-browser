package com.bitsycore.cardbrowser.data.repository

import com.bitsycore.cardbrowser.core.provider.ProviderRegistry
import com.bitsycore.cardbrowser.data.settings.PreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Fetches every game's set catalogue once at startup, quietly, in the background.
 *
 * ## Why
 *
 * Opening a game used to mean waiting for its set list, and a set row's symbol could not be drawn
 * until that list arrived -- so the icons appeared a beat after the screen did. The catalogues are
 * the cheapest thing any of these providers serve and they are exactly what the picker leads to, so
 * fetching them before they are asked for turns that wait into an already-populated screen.
 *
 * ## What it actually costs
 *
 * Not free, despite being the light end of the data. Measured: Scryfall's catalogue is about
 * 620 KB, YGOPRODeck's 175 KB, TCGdex's around 35 KB plus a second request for release dates, and
 * the rest are a few kilobytes each -- call it a megabyte across seven games, once.
 *
 * Three things keep that honest:
 *
 * - **Sequential.** Seven catalogues at once is seven simultaneous connections to seven volunteer
 *   APIs for something nobody has asked to see yet. One at a time is slower and rude to nobody.
 * - **Cache-first, like every other read.** `CardRepository.setList` answers from disk when it has
 *   something recent, so on the second launch of a day most of these cost nothing at all. Where a
 *   revalidation does go out, the HTTP cache turns it into an empty `304`.
 * - **Failures are swallowed.** This is a prefetch: nothing on screen depends on it, and a provider
 *   being down must not produce an error the user cannot act on. The real load, when they open the
 *   game, reports properly.
 *
 * It is deliberately *not* extended to card data. A set list is kilobytes; a game's cards are
 * hundreds of requests, which is what the download queue is for and is never something to do behind
 * the user's back.
 */
class SetCatalogueWarmer(
	private val mRepository: CardRepository,
	private val mRegistry: ProviderRegistry,
	private val mPreferences: PreferencesStore,
	private val mFacts: SetFactsWarmer,
	private val mScope: CoroutineScope,
) {

	private var mJob: Job? = null

	/** Starts the sweep. Calling it again while one is running does nothing. */
	fun start() {
		if (mJob?.isActive == true) return
		mJob = mScope.launch {
			mPreferences.load()
			val vPreferences = mPreferences.preferences.value
			val vLanguage = vPreferences.primaryLanguage
			// Hidden games are skipped. Hiding one is a display choice everywhere else in the app,
			// but this is a *prefetch* -- spending someone's bandwidth on a catalogue they have said
			// they do not want on screen is the one place the choice should cost something.
			val vGames = mRegistry.games.filterNot { it.id.value in vPreferences.hiddenGames }

			for (vGame in vGames) {
				try {
					// Collected to completion rather than sampled: the flow emits cache and then
					// network, and it is the network pass that leaves a fresh catalogue on disk.
					mRepository.setList(vGame.id, vLanguage).collect { }
					// And what that catalogue *holds*, which needs no network at all: the set list
					// asked on arrival, and the answer landed late enough to watch the download
					// buttons and the saved marks appear a beat after the rows.
					mFacts.warm(vGame.id, vLanguage)
				} catch (vError: CancellationException) {
					throw vError
				} catch (vError: Exception) {
					// Nothing on screen is waiting for this. The real load will report the failure
					// if the user goes there.
				}
			}
		}
	}

	/** Stops the sweep. Whatever already reached disk stays there. */
	fun cancel() {
		mJob?.cancel()
		mJob = null
	}
}
