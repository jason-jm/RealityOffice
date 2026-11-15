package com.therealityteam.aeropages.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.ProofIssue
import com.therealityteam.aeropages.platform.SystemSpelling

/**
 * Review → Proofing over [texts] — a document's paragraphs, a deck's boxes and notes — numbered as
 * [onJump] and [apply] take them: the device's spell checker is asked once it opens, its typos listed
 * first with their suggestions, [ownIssues] (AeroDesk's own checks, looked at afresh) after. A suggestion
 * goes in through [apply] (true when it did: the text there has not changed since), the later typos of
 * its text moved by the change in length; a word ignored is left alone for the rest of the session.
 * [where] names where an issue is (a paragraph's number when not given).
 */
@Composable
internal fun ProofingSession(
    texts: List<String>,
    ownIssues: () -> List<ProofIssue>,
    onJump: (Int) -> Unit,
    apply: (ProofIssue, String) -> Boolean,
    onDismiss: () -> Unit,
    where: ((ProofIssue) -> String)? = null,
) {
    val context = LocalContext.current
    var typos by remember { mutableStateOf<List<ProofIssue>?>(null) }
    var spelling by remember { mutableStateOf(true) }
    var ignored by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) {
        val found = SystemSpelling.check(context, texts)
        spelling = found != null
        typos = found?.flatMapIndexed { i, list ->
            list.mapNotNull { t ->
                val w = texts[i].substring(t.start.coerceIn(0, texts[i].length), (t.start + t.length).coerceIn(0, texts[i].length)).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ProofIssue(i, Strings.get(R.string.txt_proof_misspelled, w), w, t.start, t.suggestions)
            }
        } ?: emptyList()
    }
    ProofingDialog(
        issues = typos.orEmpty().filter { it.word !in ignored } + ownIssues(),
        checking = typos == null,
        spellingAvailable = spelling,
        onJump = onJump,
        onApply = { issue, s ->
            val word = issue.word ?: return@ProofingDialog
            if (apply(issue, s)) {
                // The fixed word goes; later words of its text move by the change in length.
                val shift = s.length - word.length
                typos = typos?.mapNotNull { o -> when {
                    o === issue -> null
                    o.index == issue.index && o.start > issue.start -> ProofIssue(o.index, o.message, o.word, o.start + shift, o.suggestions)
                    else -> o
                } }
            }
        },
        onIgnore = { w -> ignored = ignored + w },
        onDismiss = onDismiss,
        where = where ?: { Strings.get(R.string.label_paragraph_n, it.index + 1) },
    )
}
