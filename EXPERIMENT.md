# Transcription quality experiment

This branch is an **experimental desktop build**, not a release candidate.
It does not change `main`, `stable`, AUR, or the Android app.

## Changes

- Defaults to **Parakeet English v2**; v3 multilingual remains available.
- Learns narrow contextual corrections from explicit user fixes. For example,
  `after root` → `after rot`, not every `root` → `rot`.
- Removes clear local stutters and explicit `no/sorry` self-corrections without
  a network request. Intentional emphasis is preserved.
- Fixes mixed punctuation such as `name.,` locally.
- Adds an optional conservative AI prompt with vocabulary and cursor context.
  Network AI tidy is **off by default** because it does not meet the latency
  target on long clips.

## Measured results

The private benchmark has 506 recordings, including 20 user-corrected clips.
WER is aggregate normalized word error rate. Latency below is recognition plus
local cleanup, not the older DSP stage.

| Variant | Original 17 corrected | Expanded 20 corrected | Median latency |
|---|---:|---:|---:|
| Current v3 + local cleanup | 5.215% | 4.762% (18 clips) | 0.133 s |
| English v2 + local cleanup | 2.761% | 3.351% | 0.134 s |
| English v2 + optional LLM prompt | 2.147% | 2.835% | 0.160 s polish alone |
| English v2 + fitted model-aware local corrections | **0.000%** | **0.000%** | **0.134 s** |
| English v2 + leave-one-out corrections | 2.761% | 3.351% | 0.134 s |
| Whisper large-v3-turbo | 2.147% | 1.852% (18 clips) | 1.249 s — rejected |

The fitted local candidate changes 15 of 506 clips and none of the 101 accepted
reference clips. Three additional changes are clear rambler repairs:
`game game`, `it it`, and `up up`.

**The 0% result is fitted personalization, not a general accuracy claim.** The
correction rules were learned from the same 20 references. Leave-one-out is the
more honest generalization check and remains 3.351%, about 36% below the original
17-clip baseline rather than 90%. A larger set of future edits is needed before
claiming that the app generally makes 90% fewer errors.

Whisper Turbo is slightly more accurate than English v2 alone but nearly ten
times slower, so it is not part of the final candidate. Running the LLM prompt
across the corpus reached 0.482 s median polish time and 1.018 s p95 before ASR,
so it is optional rather than part of the speed-constrained result.

## Private personalized dictionary

The measured fitted result uses
`/mnt/RAID1/Aidan/squelch/wispr-flow-comparison/config/dictionary-model-aware.json`.
It was generated only from Aidan's edits and alternative local recognizers;
Wispr was not used as a label. It is intentionally not committed to the public
repository. Review it before copying it over `~/.config/squelch/dictionary.json`.
The package build also places a read-only copy at
`/usr/share/doc/nullspace/experiment-dictionary.json` for comparison.

The source-side learner handles future edits in the background. It creates
contextual entries instead of risky global replacements. The offline
model-aware import was run by the private comparison harness.

## Tests

```
python3 tests/test_dictation.py
```

The tests cover model defaults, opt-in AI tidy, punctuation, disfluency,
correction learning, and the anti-summary guard. The full private replay is in
`/mnt/RAID1/Aidan/squelch/wispr-flow-comparison/`, with
`polish-final-candidate.jsonl` and `model-scores.json` containing the final
candidate and comparisons.

## Packaging and installation

The experimental Nullspace package is built from `desktop-v1.1.6` plus this
Squelch branch. It is **not installed automatically**. Do not install the
standalone `squelch` package on a machine running Nullspace; use the Nullspace
package built for this experiment.
