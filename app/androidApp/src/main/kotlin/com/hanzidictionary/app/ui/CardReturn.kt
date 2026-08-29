package com.hanzidictionary.app.ui

/** How long a suspended card is still worth offering a way back to. */
const val CARD_RETURN_TTL_MS = 10 * 60 * 1000L

// CardReturn and ReturnToCardFab are replaced by the LookupSheet's return chip
// and the Dictionary tab's return dock. The card-pin concept lives on in HanziApp
// (practiceCardPin) and is used by PracticeScreen directly.
//
// This file is kept to avoid a broken import; all functionality has moved to
// LookupStack (per-tab sheet state) and PracticeScreen's own pin handling.
