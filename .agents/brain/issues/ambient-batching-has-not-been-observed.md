# Ambient batching has not been observed

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-11
**ID:** OP-3.4 (formerly OPEN-POINTS.md §3.4)

## Problem

Spec §1.3 says exercise data arrives at ~1 Hz with the screen on and batched
while ambient. The matcher is proven to produce identical results either way —
the unit test `batched processing equals streamed processing`. What nobody has
seen is how large the batches actually are on a Pixel Watch 4.

## Impact

Batch size determines how stale the Route page looks mid-run.

## Workaround

Spec §7 says to leave batching at its default until measured; that is still the
position.
