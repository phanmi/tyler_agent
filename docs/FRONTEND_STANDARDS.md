# Frontend coding and design standards

[Developer guide](DEVELOPMENT.md) · [Backend standards](BACKEND_STANDARDS.md)

These standards apply to frontend implementation and review, including React, TypeScript, CSS, HTTP clients, Electron, and frontend build scripts. **AI coding agents must follow the applicable rules for every new or changed behavior.** Human contributors and reviewers use the same baseline.

Rules describe responsibilities and observable behavior without assigning them to particular application classes or components. Existing code is context, not proof of compliance. Preserve public contracts and keep changes within the task's scope. Record unrelated gaps for follow-up; this document does not authorize a broad refactor or new framework.

**MUST** means required. **SHOULD** means the default, with any departure explained and supported by evidence. An exception must identify the rule, reason, affected scope, verification, and follow-up; an agent must not silently waive a requirement. Current tooling limitations are listed in the [developer guide](DEVELOPMENT.md#frontend-verification-baseline).

## Standards at a glance

| ID | Standard |
|---|---|
| **FE-01 — Component responsibilities** | Organize code by feature. Separate rendering, asynchronous workflows, and HTTP transport when they have independent responsibilities. Extract components and hooks for cohesion and testability, rather than arbitrary file-size limits. |
| **FE-02 — Types and domain models** | Enable strict TypeScript checking. Distinguish API responses, domain models, and editable drafts where their shapes differ. Validate external data at appropriate boundaries. Define date, unit, numeric, and identity conventions. |
| **FE-03 — Asynchronous reads** | Associate requests with a resource key and request identity. Cancel or ignore obsolete requests. Only relevant requests may update data, loading, or errors. Invalidate affected views after successful writes. |
| **FE-04 — Mutations and drafts** | Capture the mutation target and submitted draft. Separate pending and editing state. Prevent conflicting submissions, preserve newer input, and prevent an old mutation from replacing another resource's visible state. |
| **FE-05 — API access** | Use a shared HTTP layer for serialization, error normalization, cancellation, and timeout policy. Preserve HTTP status information. Do not retry writes automatically unless duplicate execution is safe. |
| **FE-06 — Forms and feedback** | Use semantic forms and accessible labels. Distinguish loading, empty, error, and success. Preserve input after failures and provide recovery. Failed loads must never masquerade as successfully loaded empty records. |
| **FE-07 — Accessibility** | Support keyboard operation, accessible names, state indicators, and status announcements. Modals must manage initial focus, contain keyboard focus, prevent background interaction, and restore focus on close. |
| **FE-08 — Styling** | Use design tokens and scoped component styles. Keep resets low in specificity. Define hover, focus, disabled, and error states consistently. Meet WCAG AA contrast requirements and avoid borrowing another feature's CSS classes. |
| **FE-09 — Layout** | Define supported window sizes and zoom levels. Keep essential content and controls reachable through responsive layout, intentional scrolling, or an explicit minimum window size. Avoid silent clipping. |
| **FE-10 — Electron lifecycle and security** | Preserve renderer isolation and narrowly scoped IPC APIs. Validate privileged requests and control navigation. Make startup cancellable, cleanup idempotent, and installer lifecycle handling explicit. |
| **FE-11 — Testing** | Test observable behavior, including failures, delayed and out-of-order responses, navigation during mutations, and destructive actions. Add packaged-app smoke tests for desktop startup or release changes. |
| **FE-12 — CI and change management** | Require type checking, linting, relevant tests, and a production build before merging. Use locked dependencies and an agreed formatter. Keep PRs focused and add regression coverage for recurring behavior. |
| **FE-13 — State and effects** | Give state a clear owner, preserve stable identity, keep rendering pure, and clean up effects and subscriptions. Derive values instead of maintaining competing copies. |
| **FE-14 — Content and privacy** | Treat remote, user, and generated content as untrusted. Protect secrets, validate link destinations, and keep sensitive data out of browser storage, bundles, and diagnostics. |
| **FE-15 — Comments and diagnostics** | Keep comments accurate and explain constraints. Log useful, safe operation context at the handling boundary. Inspect both thrown and caught failures in tests. |

## FE-01 — Component responsibilities

- Group UI, hooks, and feature helpers by responsibility. Promote code to a shared location when it has a real shared contract; do not create a generic abstraction for a single forwarding operation.
- Components MUST describe presentation and user interaction. Extract asynchronous coordination into a hook or focused module when it has an independent lifecycle or meaningful reuse. Keep HTTP serialization and transport out of rendering code.
- Keep props explicit and narrow. Prefer composition and small interfaces over inheritance or configurable components with unrelated modes.
- Keep server-owned business decisions on the backend. Client validation improves feedback but cannot replace backend enforcement. Preserve API and IPC contracts unless changing them is in scope.

## FE-02 — Types and domain models

- Keep strict TypeScript checking enabled. Do not introduce `any`, unchecked assertions, or suppression comments merely to make a check pass. Narrow `unknown` and model nullability deliberately.
- Type assertions do not validate JSON. Validate required shapes and values at network, persisted-state, and IPC boundaries before using them. Reuse existing validation facilities; adding a schema library requires a concrete need.
- Reuse a type when its meaning and shape match. Separate transport DTOs, domain values, and editable drafts when they differ; document conversions and server-normalized values.
- Represent calendar dates as `YYYY-MM-DD` date-only values. Do not convert them through UTC timestamps when that can shift the selected day. For timestamps, document the timezone and display conversion. Test relevant timezone and daylight-saving boundaries.
- Define units, ranges, precision, rounding, and absent-value semantics. Preserve empty numeric input as a draft; do not silently turn it into zero. Reject non-finite values. Coordinate decimal or identifier representations with the API when JavaScript numbers cannot represent them safely.
- Address persisted records by stable IDs. Equal values may belong to different records. Do not use object equality or array position as record identity.

## FE-03 — Asynchronous reads

- A resource key MUST include every input that determines the result, such as a date, filters, or account. Track request identity as well: two requests for the same key can also finish out of order.
- On key changes or unmount, abort work where supported and ignore obsolete completions. Guard **all** state updates, including `catch` and `finally`; an obsolete request must not clear the current request's spinner or error.
- Keep displayed data associated with its key. Clear it on selection changes or clearly label it as stale; do not present the previous resource as the new selection.
- After a successful mutation, invalidate or refresh all affected resource keys, including both locations if a record moves. Prevent a read started before the mutation from overwriting the newer result.
- Test ordering with controlled promises: request A starts, request B starts, B succeeds, then A succeeds or fails. The visible data and status must still belong to B. See React's [effect cleanup and fetch example](https://react.dev/reference/react/useEffect#fetching-data-with-effects).

## FE-04 — Mutations and drafts

- At submission, capture the record ID/resource key and a snapshot of the submitted values. Never derive the completion target from whatever happens to be selected later.
- Track pending operations separately from editable drafts. Either prevent conflicting edits/submissions explicitly or track a draft revision so completion cannot reset newer edits. A disabled button alone does not protect every submission path.
- Apply success, failure, and completion feedback to the captured target. After navigation, update or invalidate that target without replacing the current screen's data, draft, error, or pending state.
- Clear a draft only when the operation succeeded and that draft is still the submitted revision. Preserve input after failure and define what happens to unsaved changes on navigation or dismissal.
- Destructive confirmations MUST identify and capture the target before execution. Cancel must have no write effect. Optimistic updates require scoped rollback that preserves later changes; otherwise wait for confirmation from the server.
- Aborting a write or timing out does not prove the server rolled it back. If the outcome is uncertain, reconcile through a read or offer an explicit recovery step before encouraging a repeat.

## FE-05 — API access

- Use the shared transport boundary and configured/discovered backend address. Do not duplicate request policies in components or hardcode a development port.
- Define JSON/body serialization, headers, empty-body handling, cancellation signals, and timeout behavior centrally. Handle valid empty responses without trying to parse JSON. Preserve operation-specific contracts.
- Normalize HTTP, network, malformed-response, timeout, and cancellation outcomes. Preserve a machine-readable HTTP status and safe error detail; consumers must not parse prose to distinguish failures.
- Expected cancellation SHOULD remain silent in the UI. Other failures must have an observable outcome; never return an empty collection or success to conceal a failure.
- Retry reads only under a bounded, documented policy. Retry writes automatically only with a demonstrated idempotency or deduplication guarantee. UI submit guards alone do not provide that guarantee.

## FE-06 — Forms and feedback

- Use native inputs, buttons, labels, and form submission semantics. Give non-submit buttons an explicit type. Keep Enter and input-method composition behavior appropriate for the control.
- Explain required formats and units. Associate field errors with their inputs, and provide a clear summary or focus destination when submission fails.
- Model loading, valid empty data, error, pending writes, and confirmed success distinctly. A failed read must not enable editing an invented empty record that could overwrite saved data.
- Preserve correctable input and provide retry, correction, or dismissal as appropriate. Announce completion and failure without moving focus unnecessarily. Never show success until the relevant operation is confirmed.
- Distinguish a successful write followed by a failed refresh from a rejected write. Explain that saving succeeded and offer a refresh; do not invite a duplicate submission. Use an appropriate error boundary and recovery UI for render failures; handle asynchronous errors explicitly in their workflow.

## FE-07 — Accessibility

- Use native semantics before ARIA. Provide accessible names for icon-only controls; convey selection, expansion, and current navigation state programmatically as well as visually.
- All actions MUST be keyboard reachable with a visible focus indicator and logical focus order. Do not communicate state through color alone. Announce meaningful asynchronous status changes without excessive repeated announcements.
- A modal MUST have an accessible name, suitable initial focus, contained Tab/Shift+Tab navigation, and an inactive background. Support Escape dismissal where cancellation is allowed and restore focus to the opener or a logical successor if it no longer exists. For destructive actions, prefer initial focus on the safer choice. `aria-modal` alone does not implement these behaviors. Follow the [WAI modal dialog pattern](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/).
- Check calendar/navigation widgets, validation errors, custom title-bar controls, and dialogs with the keyboard. Respect reduced-motion preferences and avoid unnecessary animation.

## FE-08 — Styling

- Use shared tokens for colors, spacing, typography, and states. Scope feature styles with a local convention or CSS modules; share styles through a common component or token rather than importing another feature's class names.
- Keep global resets low in specificity. Avoid broad selectors and `!important` fixes that unintentionally override component or third-party control states.
- Specify hover, focus-visible, selected, disabled, pending, and error states. Check contrast in the actual rendered combinations, including text on tinted backgrounds. WCAG AA text contrast requires at least 4.5:1 for normal text and 3:1 for large text, subject to the standard's exceptions. See [WCAG contrast guidance](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html). Also check applicable non-text control and focus contrast requirements.
- Follow surrounding formatting until an agreed formatter is configured. Avoid unrelated restyling in behavior fixes.

## FE-09 — Layout

- Document supported browser/desktop platforms, minimum usable dimensions, and zoom levels in feature or release acceptance criteria. A default launch size is not an enforced minimum.
- Verify the supported minimum window, typical window, and maximized window at normal and 200% zoom. Add narrower/reflow checks where required by the supported browser experience and accessibility target; a minimum desktop size is not a substitute for those checks.
- Keep primary actions, error text, and dialog controls reachable. Use deliberate scroll containers and flexible sizing. Do not use hidden overflow to conceal unreachable content.
- Check long text, validation messages, empty content, and large lists. Preserve focus and useful scroll position when data updates. Frameless window drag regions must not consume clicks intended for interactive controls.

## FE-10 — Electron lifecycle and security

- Keep context isolation enabled, renderer Node integration disabled, and sandboxing enabled where supported. Do not relax web security to fix networking problems.
- Expose narrow, typed preload operations. Never expose raw IPC, arbitrary channel names, unrestricted filesystem access, or arbitrary shell execution to renderer content. Validate sender/frame identity and payloads in privileged handlers; renderer checks alone are insufficient.
- Restrict navigation, new windows, permissions, and external URL opening to explicit policies. Use an appropriate Content Security Policy and account for development and packaged origins. Follow [Electron's security guidance](https://www.electronjs.org/docs/latest/tutorial/security).
- Startup MUST be cancellable even before readiness resolves. Track owned child processes early; quit/failure must remove their listeners/timers and terminate only those processes. Cleanup must be safe to repeat and must not open a late window after shutdown.
- Handle installer/update lifecycle events before ordinary startup. Verify install, launch, close, restart, and applicable update/uninstall flows with packaged artifacts on the supported OS.
- Preserve dynamic port discovery and the identity of the app-owned backend. Loopback binding and CORS do not authenticate other local processes. Document any authentication requirement separately.

## FE-11 — Testing

**All frontend test files MUST live in the dedicated `frontend/tests/` directory.** This includes component/hook tests, Electron tests, script tests, and test-only helpers, mocks, and fixtures. Do not colocate them with production files in `frontend/src/`, `frontend/electron/`, or `frontend/scripts/`, or scatter them in the frontend root. AI agents must follow this placement rule when adding or moving tests.

- Organize tests by feature or runtime under `tests/`, such as `tests/workout/`, `tests/electron/`, and `tests/scripts/`. Keep feature-specific helpers with their tests; place broadly shared helpers in `tests/helpers/` when needed.
- Use `*.test.ts` / `*.test.tsx` for Vitest tests and `*.test.cjs` for Node's test runner. Import production code from its source location; production code must not import test-only modules.
- Keep test discovery, npm scripts, and TypeScript configuration aligned when moving tests. Type checking must include TypeScript tests and helpers as well as application code. The production build must not import or bundle test modules.

Test observable contracts with deterministic inputs and controlled async completion. Avoid assertions tied only to implementation structure, arbitrary sleeps, live credentials, or real user data.

| Area | Required coverage when affected |
|---|---|
| Reads | Success, empty, malformed data, failure, cancellation, unmount, and out-of-order completion, including repeated requests for one key. |
| Mutations | Exact captured target/payload, double submission, failure preserving the draft, edits or navigation while pending, and reconciliation after uncertain outcomes. |
| Destructive actions | Confirm and cancel, stable ID selection among equal-looking entries, failure, and subsequent refreshed state. |
| Forms and accessibility | Keyboard submission, labels, validation feedback, modal focus containment/restoration, and accessible status. |
| Transport | Status preservation, malformed/empty responses, cancellation/timeout, and any supported retry policy. |
| Desktop | Startup failure/timeout, quitting during startup, child exit, repeat cleanup, and packaged launch/shutdown on the supported OS. |

- For thrown or rejected failures, assert the designated error type/status or contract fields. For caught failures, assert the resulting UI, state, cleanup, and relevant absence of unintended writes; merely asserting no throw is insufficient.
- Prefer component tests driven by accessible roles and user actions. Use integration tests for boundary behavior and end-to-end tests for critical flows. Select a runner through a focused tooling change if none exists; do not pretend a production build tests UI behavior.
- Desktop startup/release changes MUST include packaged-app smoke coverage. Supporting process-unit tests alone cannot verify the installer or packaged lifecycle. Record artifact, OS, steps, and results; automate the smoke path where practical.
- Review layout/keyboard behavior manually where automated checks cannot establish it, and record the tested dimensions/zoom and remaining limitations.

## FE-12 — CI and change management

- Frontend behavior changes MUST pass strict type checking, configured linting, relevant tests, and the production build before merging. Enforce applicable checks in CI and the repository's required status checks. A workflow file alone does not configure branch protection.
- Use the lockfile and reproducible installation (`npm ci`). Do not hand-edit resolved dependency metadata. Explain new dependencies and preserve the shared release-version source.
- Adopt one agreed formatter and lint configuration, including React hook dependency checks. Until they exist, preserve surrounding style, report the missing gates, and track tooling work explicitly. An unavailable check is unverified, not passed.
- Do not disable checks or suppress errors to obtain a green build. Fix within scope or document the concrete blocker. Report commands actually run, results, and limitations.
- Keep PRs focused; preserve APIs, IPC, stored data, and UI behavior unless the task authorizes a change. Add regression tests for behavior fixes. Documentation-only changes need content/link checks rather than new runtime tests.

## FE-13 — State and effects

- Give each piece of state a clear owner. Separate server data, drafts, navigation, and transient UI state. Derive totals and filtered values when practical instead of synchronizing competing state copies.
- Keep rendering pure. Treat props/state as immutable and use functional state updates when the next value depends on the previous value. Use stable keys for editable or reorderable items; generate temporary IDs once when needed.
- Use effects for synchronization with external systems, with complete dependencies and matching cleanup for subscriptions, timers, and requests. Setup/cleanup must tolerate React development remount checks. Do not start user-triggered writes from mount effects. See [React effect lifecycle guidance](https://react.dev/reference/react/useEffect).
- Keep expensive work off critical interaction paths. Measure a demonstrated bottleneck before adding memoization, virtualization, or a global state/cache library; retain correctness and accessible behavior when optimizing.

## FE-14 — Content and privacy

- Render untrusted text through safe text bindings. Raw HTML requires a reviewed sanitization policy. Rich-text/Markdown rendering must disable raw HTML or apply that policy, and validate URL destinations. Never evaluate generated content as code.
- Keep credentials out of source, build-time client variables, URLs, logs, analytics, and persistent browser storage. Minimize how long sensitive drafts stay in memory and clear them once no longer needed.
- Do not expose privileged APIs because content appears to come from a trusted conversation or backend. Apply the same boundary validation to user, remote, and model-generated content.
- Introduce telemetry, persistence, or external resource loading only with an explicit product requirement and documented data handling. Tests must use synthetic data.

## FE-15 — Comments and diagnostics

- Use English for code comments, documentation, logs, and user-facing messages. Follow existing naming and formatting conventions.
- Review the final affected components, hooks, helpers, and surrounding comments. Explain ownership, resource identity, cleanup, and non-obvious failure decisions; remove stale explanations rather than narrating obvious code.
- Diagnostics SHOULD identify the operation, outcome, and safe context such as request identity or HTTP status. Avoid logging raw payloads, secrets, personal data, or full sensitive URLs. Do not emit success before confirmation or log expected cancellation as an unexpected failure.
- Log unexpected failures at the appropriate handling boundary without duplicate stack traces at every layer. A console entry does not replace user-visible recovery. Cover the observable handling of both thrown and caught failures.

## Required AI implementation and review workflow

1. Read this document, applicable repository instructions, the acceptance criteria, and affected callers/tests. Inspect actual scripts and configuration before proposing commands or claiming a capability exists.
2. Identify applicable FE IDs and boundaries: resource keys, stable IDs, draft ownership, public contracts, and privileged operations. State material assumptions and keep unrelated debt separate.
3. Implement the smallest cohesive change. Preserve newer user input and existing data. Update comments and useful diagnostics in the final surrounding code.
4. Inspect each meaningful success and failure path against tests. Include stale responses and mutation/navigation races when applicable. Assert observable handling of expected errors.
5. Run available applicable checks and inspect UI/desktop behavior where needed. Validate documentation links for documentation changes. Report unavailable gates explicitly; never fabricate a command, passing result, or compliance claim.
6. Summarize what changed, verification results, and remaining risks or follow-up. Use the [PR review guidance](../.github/agents/pr-review.agent.md) and [pull request template](../.github/pull_request_template.md).

### Review checklist

- [ ] Applicable FE rules and acceptance criteria are satisfied; departures are explained.
- [ ] Types, dates, units, and stable record identity preserve the contract.
- [ ] Obsolete reads and completed mutations cannot corrupt another resource or newer draft.
- [ ] Failure states preserve input, communicate truthfully, and offer recovery.
- [ ] Keyboard, focus, contrast, and supported layouts were checked where affected.
- [ ] Privileged boundaries, content rendering, and sensitive data remain protected.
- [ ] Comments and diagnostics match the final behavior; meaningful error paths have coverage.
- [ ] Required checks and any verification gaps are reported accurately.
- [ ] All test files, fixtures, and test-only helpers are under `frontend/tests/`; discovery and type checking include the moved or added tests.

## Future additions and exceptions

Add decisions here, then incorporate stable rules into the relevant section.

```text
Rule ID:
Proposed rule or exception:
Reason and evidence:
Scope and compatibility impact:
Verification:
Follow-up issue / owner:
```
