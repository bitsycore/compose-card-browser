import io

P = 'composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/detail/CardDetailScreen.kt'
s = io.open(P, encoding='utf-8').read()

# The per-card composable is already called CardDetailContent; free the name for the screen body.
s = s.replace('CardDetailContent(', 'CardDetailPage(')
assert 'private fun CardDetailPage(' in s

old_head = '''@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
\tcardId: String,
\tsetId: String?,
\tonBack: () -> Unit,
\t// The arguments go in at construction so the view model can seed its state from the browse
\t// session before the first frame, rather than being told to load after one has already been drawn.
\tviewModel: CardDetailViewModel = koinViewModel { parametersOf(CardDetailArgs(cardId, setId)) },
) {
\tval vState by viewModel.collectAsStateWithLifecycle()
\tval vSnackbarHost = remember { SnackbarHostState() }
'''
new_head = '''@Composable
fun CardDetailScreen(
\tcardId: String,
\tsetId: String?,
\tonBack: () -> Unit,
\t// The arguments go in at construction so the view model can seed its state from the browse
\t// session before the first frame, rather than being told to load after one has already been drawn.
\tviewModel: CardDetailViewModel = koinViewModel { parametersOf(CardDetailArgs(cardId, setId)) },
) {
\tval vState by viewModel.collectAsStateWithLifecycle()
\tval vSnackbarHost = remember { SnackbarHostState() }

\tviewModel.collectEffect { vEffect ->
\t\twhen (vEffect) {
\t\t\tis CardDetailContract.Effect.LinkFailed ->
\t\t\t\tvSnackbarHost.showSnackbar("Could not open a browser for that link.")
\t\t}
\t}

\tCardDetailContent(
\t\tstate = vState,
\t\tdispatch = viewModel::dispatch,
\t\tonBack = onBack,
\t\tsnackbarHostState = vSnackbarHost,
\t)
}

/**
 * The card detail screen, given a state and somewhere to send intents.
 *
 * @param snackbarHostState hoisted so the screen can post to it from an effect. A preview passes a
 *   fresh one and never uses it
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailContent(
\tstate: CardDetailContract.UiState,
\tdispatch: (CardDetailContract.Intent) -> Unit,
\tonBack: () -> Unit = {},
\tsnackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
\tval vState = state
\tval vSnackbarHost = snackbarHostState
'''
assert old_head in s, 'screen head not found'
s = s.replace(old_head, new_head, 1)

# The effect belongs to the screen, not the content.
old_effect = '''\tviewModel.collectEffect { vEffect ->
\t\twhen (vEffect) {
\t\t\tis CardDetailContract.Effect.LinkFailed ->
\t\t\t\tvSnackbarHost.showSnackbar("Could not open a browser for that link.")
\t\t}
\t}

\t// `enterAlways` rather than'''
new_effect = '''\t// `enterAlways` rather than'''
assert old_effect in s, 'effect block not found'
s = s.replace(old_effect, new_effect, 1)

s = s.replace('viewModel.dispatch(', 'dispatch(')

s = s.rstrip('\n') + '''

// ==================
// MARK: Previews
// ==================

private fun previewDetailState(
\tcards: List<CardPrinting> = PreviewData.CARDS,
\tcurrentIndex: Int = 0,
\tisLoading: Boolean = false,
\trequestedLanguage: CardLanguage = CardLanguage.ENGLISH,
) = CardDetailContract.UiState(
\tcards = cards,
\tcurrentIndex = currentIndex,
\tset = PreviewData.ORIGINS,
\tisLoading = isLoading,
\trequestedLanguage = requestedLanguage,
\tattribution = "Card data from Riftcodex, an unofficial fan project not affiliated with Riot Games.",
)

@Preview
@Composable
private fun CardDetailPreview() = PreviewFrame {
\tCardDetailContent(state = previewDetailState(), dispatch = {})
}

@Preview
@Composable
private fun CardDetailFrenchFallbackPreview() = PreviewFrame {
\t// The state the whole language model exists for: French asked for, English shown, and the
\t// screen saying so rather than pretending the record is French.
\tCardDetailContent(
\t\tstate = previewDetailState(currentIndex = 5, requestedLanguage = CardLanguage.FRENCH),
\t\tdispatch = {},
\t)
}

@Preview
@Composable
private fun CardDetailSingleCardPreview() = PreviewFrame(isDark = false) {
\t// One card, so no preview strip and no position counter.
\tCardDetailContent(
\t\tstate = previewDetailState(cards = listOf(PreviewData.card())),
\t\tdispatch = {},
\t)
}

@Preview
@Composable
private fun CardDetailLoadingPreview() = PreviewFrame {
\tCardDetailContent(
\t\tstate = previewDetailState(cards = emptyList(), isLoading = true),
\t\tdispatch = {},
\t)
}
'''
for old, new in [
    ('import com.bitsycore.cardbrowser.ui.common.LoadingState',
     'import com.bitsycore.cardbrowser.ui.common.LoadingState\nimport com.bitsycore.cardbrowser.ui.preview.PreviewData\nimport com.bitsycore.cardbrowser.ui.preview.PreviewFrame'),
    ('import androidx.compose.ui.text.font.FontStyle',
     'import androidx.compose.ui.text.font.FontStyle\nimport androidx.compose.ui.tooling.preview.Preview'),
]:
    assert old in s, old
    s = s.replace(old, new, 1)

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('carddetail previews ok')
