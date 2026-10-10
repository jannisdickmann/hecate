# Battery has not been measured

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-11
**ID:** OP-3.3 (formerly OPEN-POINTS.md §3.3)

## Problem

Spec NFR-3 asks for battery cost comparable to Google's Exercise app. The
matcher's CPU budget is tested — a unit test fails if it exceeds 5 ms per sample
with a realistic route set. The GNSS and heart-rate cost is whatever Health
Services does. Nothing has been measured.

## Impact

NFR-3 is unverified.

## Workaround

None.
