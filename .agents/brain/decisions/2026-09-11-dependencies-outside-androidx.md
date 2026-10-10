# ADR: Dependencies outside AndroidX — the transport outranks the count

**Date:** 2026-09-11
**Status:** Accepted
**ID:** A.7 (formerly SPECIFICATION.md Appendix A)

## Context

NFR-8 allows exactly one third-party dependency outside AndroidX,
kotlinx.serialization. The Wearable Data Layer, which §4 selects as the
watch↔phone transport, ships in `com.google.android.gms:play-services-wearable`
and has no AndroidX equivalent. The two requirements are not simultaneously
satisfiable.

## Decision

The transport was kept, since the robustness it provides is explicitly
load-bearing for US-2.

Both Android modules therefore carry two non-AndroidX dependencies:
kotlinx.serialization and play-services-wearable. `:core`, where the rule
matters most, has exactly one and no Android dependency at all.
`kotlinx-coroutines-play-services` was deliberately *not* added; the handful of
`Task` awaits use `Tasks.await` on an IO dispatcher instead.

`kotlinx-coroutines-android` is declared by both Android modules and is not
counted, which is a judgement rather than an oversight: it is the coroutines
runtime the language's own `suspend` functions need, and `androidx.compose.ui`
pulls it in transitively regardless, so it is in the APK whether this project
names it or not. Declaring it makes the version explicit in the catalog instead
of inherited from whichever AndroidX artifact happens to win.

## Consequences

A third name appears in `:wear`'s dependency block since the module was first
compiled: `com.google.guava:guava`, `compileOnly`. Health Services returns a
`ListenableFuture` from every `*Async` method but declares Guava at runtime
scope, so the type is missing from the compile classpath while the
implementation is already in the APK. It is a declaration of something that was
always shipping, not a new dependency, and it contributes nothing to the
artifact — but the count is now two declared plus one compile-time-only, and
saying otherwise would be the kind of accounting this record exists to prevent.
See open point §1.1.

Later additions held the line: everything A.11 added is AndroidX, and A.29's
network access uses the platform's `HttpURLConnection`. Declining
`kotlinx-coroutines-play-services` is also one of the reasons a Google Drive
backup was declined (A.10).
