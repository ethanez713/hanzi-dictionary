# Does a newer Ollama reuse the prompt prefix? No — and it changes the scores

The scorer sends `_PROMPT_HEAD + sentence` on every call. The head (rubric, rules, six worked
examples) is 445 tokens and byte-identical across all 57,337 sentences; the sentence is ~15.
On the installed **0.24.0** that head is re-evaluated in full every time: 0.65 s of prompt
eval against 0.04 s of generation, ~95% of the cost. A prompt with no head answers in 0.32 s,
so working prefix reuse would be worth about 2.8x.

**0.33.3** was the candidate, on the strength of v0.30.8 *"Improved prompt caching by
decoupling it from context shift for better KV cache reuse"*, plus v0.33.3's new
`prompt_eval_cached_count` field to measure it with. Tested side-by-side, 0.33.3 unpacked
under `~/.local/ollama-test` on port 11435 against the same model blob, one server at a time
so neither contended for the 6 GB card.

|                          | 0.24.0        | 0.33.3        |
| ------------------------ | ------------- | ------------- |
| `prompt_eval_cached_count` | absent      | **0**         |
| s/sentence, 200 sentences, 2 workers | 0.90 | 0.913 |
| agreement with the corpus cache      | **200/200** | **191/200** |

`cached=0` is the whole answer: 0.33.3 has the reporting field and it says nothing was
reused. Independent `/api/generate` calls that merely share a prefix are not what v0.30.8
fixed — that was multi-turn context shift within a conversation. `/api/chat` with the head as
a system message was tried too and is slightly *worse* (0.87-0.96 s), because the chat
template adds tokens. So there is no 2.8x on offer at any version tested, and no reason to
upgrade for speed.

**The 9 disagreements are why this was worth an experiment rather than an upgrade.** 0.24
reproduces its own cached scores 200/200, so it is deterministic and the drift is entirely
0.33.3's. Every disagreement runs the same way — a short sentence scored 5 falls to 1:
`您有什么？`, `工作。`, `谢谢？`, `我是朋友。`. At 4.5% that would have quietly binned several
hundred perfectly good short sentences, exactly the ones a beginner needs, and `load_cache`
keys on the model name only — it cannot see a runtime change, so the corpus would have ended
up scored two ways with no record of which.

Ollama's version is therefore part of the scoring regime, like the model name. Upgrading it
invalidates the cache the same way swapping models does, and the 400-sentence validation in
REVIEW-400.md would have to be re-run to re-establish precision.
