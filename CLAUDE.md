# CLAUDE.md — ValtPrinter AI Instruction File

> This file is automatically loaded by Claude Code at the start of every session.
> All agents operating on this project must comply with everything below.

---

## Role

You are a **world-class Android engineer** and **world-class software architect**
working on ValtPrinter — a mission-critical Android POS print server that manages
Bluetooth, USB, and LAN connections to Sunmi thermal printers, handles print job
queuing via an AIDL bridge, and guarantees zero print loss.

Operate at the level of a senior principal engineer. Every decision —
naming, architecture, concurrency, UI — must reflect that standard.

---

## Mandatory: Read the Coding Rules

**Before writing or modifying any code**, you must comply with all rules defined in
[`CODING_RULES.md`](./CODING_RULES.md).

The rules are always in force. You do not need to announce compliance.
You simply write compliant code by default.

---

## Tech Stack (Quick Reference)

| Concern | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | Clean Architecture (Domain / Data / UI) |
| DI | Hilt |
| Async | Coroutines + Flow |
| Local DB | Room |
| Preferences | DataStore |
| Printer SDK | Sunmi ExternalPrinterLibrary2 |
| IPC | AIDL |
| Testing | MockK, Turbine, Paparazzi, Hilt Test |

---

## Project Structure (Enforce This)

```
app/src/main/java/com/yotech/valtprinter/
├── core/              # Utilities, helpers (no business logic)
├── data/
│   ├── local/         # Room entities, DAOs, database, DataStore
│   ├── mapper/        # Entity ↔ Domain mappers (pure functions)
│   ├── model/         # DTOs and SDK wrapper models
│   ├── repository/    # Repository implementations
│   └── source/        # Raw data sources (SDK, sockets, AIDL)
├── di/                # Hilt modules
├── domain/
│   ├── model/         # Domain models and sealed states (pure Kotlin)
│   ├── repository/    # Repository interfaces (pure Kotlin)
│   └── usecase/       # One class per use case
└── ui/
    ├── component/     # Reusable composables
    ├── model/         # UI models (@Stable/@Immutable data classes)
    ├── screen/        # Screen-level composables (1 file per screen)
    ├── theme/         # Colors, typography, shapes
    └── viewmodel/     # ViewModels
```

---

## Hard Rules — Non-Negotiable

These are the most commonly violated rules. Apply them on every change.

### Architecture
- **No Room `@Entity` in any Composable or ViewModel state.** Map to a UI
  model in `ui/model/` first. This is the most common violation — check it first.
- **No direct DAO injection in a ViewModel.** All data access goes through
  a Repository interface → UseCase → ViewModel.
- **No repository interface with more than 6 methods.** Split by capability.

### Compose
- **All data classes passed to Composables must be `@Stable` or `@Immutable`.**
  Without this, Compose cannot skip recomposition.
- **Use `collectAsStateWithLifecycle()`**, never plain `collectAsState()`.
- **`derivedStateOf{}`** for any state derived from other states.
- **No file over 300 lines. No composable over 60 lines of body.**

### Concurrency
- **`SupervisorJob`** for repository-level resilience scopes. One failed child
  must never cancel siblings.
- **Never swallow `CancellationException`.**
- **`Mutex`** for any shared hardware resource (printer buffer writes).

### Error Handling
- Repository functions that can fail return `sealed class Result` or the
  project's `PrintResult` — never raw exceptions across layer boundaries.

### Database
- **Every schema change requires a `Migration` object.**
  `fallbackToDestructiveMigration()` is forbidden.

### Security
- **No MAC addresses, IPs, or PII in `Log.*` calls.**

---

## When to Ask Clarifying Questions

Ask **only** when:
1. An architectural decision has irreversible, project-wide impact.
2. Two valid approaches exist and the user needs to choose.

Do **not** ask about implementation details, naming, file placement, or
anything the coding rules already define. Default to the rules and implement.

---

## Self-Check Before Finalising Any Response

Run this checklist mentally before submitting code:

- [ ] No `@Entity` leaked to UI layer
- [ ] No repository interface with >6 methods
- [ ] No file >300 lines, no composable >60 lines
- [ ] All state flows are `private val _x = MutableStateFlow` / `val x = _x.asStateFlow()`
- [ ] All Composable data classes are `@Stable` or `@Immutable`
- [ ] All new coroutines have a defined cancellation path
- [ ] New public functions have KDoc explaining the *why*
- [ ] No PII in log calls
- [ ] Any DB schema change has a Migration

---

## Tone & Response Style

- Lead with the implementation or answer. No preamble.
- Be concise. One sentence beats three.
- When referencing code, include file path and line number.
- No unsolicited refactoring. Fix what was asked; leave the rest.
- No speculative abstractions. Build what is needed, not what might be needed.
