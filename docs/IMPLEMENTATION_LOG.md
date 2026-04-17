# Copilot Studio Connector — Implementation Log

_Last updated: 2026-04-17 (v1 complete — post-deployment fixes done)_

---

## Project Identity

| Field | Value |
|-------|-------|
| Maven artifactId | `m365copilot-connector` |
| Version | `1.5.20.33` |
| Java | 17 |
| Package | `org.forgerock.openicf.connectors.m365copilot` |
| Bundle-SymbolicName | `org.forgerock.openicf.connectors.m365copilot-connector` |
| ConnectorBundle-Name | `org.forgerock.openicf.connectors.m365copilot-connector` |
| ConnectorBundle-FrameworkVersion | `1.5` (literal) |

---

## Status: v1 COMPLETE

All source files are implemented and verified against production. The post-implementation fix backlog (OPENICF-5001 through 5015) is fully closed as of 2026-04-17. Two items remain open by design:

- **OPENICF-5012** — deferred, pending IH governance decision on `AgentDialog` handling
- **OPENICF-5009** — Nice to Fix, filter pushdown; deferred

The design specification (`copilot-studio-connector-design.md`) is finalized at v1.2.

---

## Naming Conventions

All class names follow the M365 Copilot connector prefix pattern exactly.

| Class | Package sub-path | Notes |
|-------|-----------------|-------|
| `M365CopilotConnector` | (root) | @ConnectorClass, thin dispatcher |
| `M365CopilotConfiguration` | (root) | AbstractConfiguration + StatefulConfiguration |
| `M365CopilotClient` | `client` | HTTP client, token cache, Dataverse API + OData pagination, unauthenticated SAS fetch |
| `M365CopilotCrudService` | `operations` | searchAgents, searchTools, searchKnowledgeBases, searchIdentityBindings |
| `M365CopilotConstants` | `utils` | All OC names, attribute names, YAML discriminators |
| `M365CopilotFilterTranslator` | `utils` | EqualsFilter on __UID__/__NAME__ → UID string |
| `TokenResponse` | `utils` | OAuth2 token response parser |
| `ODataPagedResponse` | `client` | Wraps value[] + @odata.nextLink |
| `BotDescriptor` | `client` | Maps bot entity JSON → ConnectorObject (__ACCOUNT__); owner attrs from owninguser expansion |
| `BotComponentDescriptor` | `client` | Maps botcomponent JSON → ConnectorObject (agentTool / agentKnowledgeBase); SnakeYAML classification + line-scanner fallback |
| `CopilotIdentityBindingDescriptor` | `client` | Maps inventory JSON binding → ConnectorObject (agentIdentityBinding) |

---

## Fix Backlog — Complete

All changes are bracketed by `// OPENICF-XXXX begin` / `// OPENICF-XXXX end` comments (IDs ≥ 5001).

### Must Fix

| # | Ticket | Issue | Status |
|---|--------|-------|--------|
| 1 | OPENICF-5001 | `useManagedIdentity=true` passes validation but fails at runtime — removed property | ✅ Done 2026-04-16 |
| 2 | OPENICF-5002 | `searchIdentityBindings()` returns empty silently when inventory unconfigured — escalate to WARN | ✅ Done 2026-04-16 |
| 3 | OPENICF-5003 | No retry / Retry-After handling on 429/503/504 in `M365CopilotClient.execute()` | ✅ Done 2026-04-16 |
| 4 | OPENICF-5004 | Long-lived pooled instances serve stale cached data — dropped `PoolableConnector` | ✅ Done 2026-04-16 |
| 5 | OPENICF-5005 | No `identityBindingScanEnabled` toggle | ✅ Done 2026-04-16 |
| 6 | OPENICF-5006 | YAML heuristic parsing safety net | ✅ Closed 2026-04-16 — superseded by OPENICF-5011 |
| 7 | OPENICF-5007 | `connectedAgents` attribute rename to `connectedAgentReferences` | ✅ Done 2026-04-16 |
| 8 | OPENICF-5008 | No ICF paging | ✅ Done 2026-04-16 |
| 10 | OPENICF-5010 | Extract real target-bot identifier for `CONNECTED_AGENT` components | ✅ Done 2026-04-16 |
| 11 | OPENICF-5011 | Replace line-scanner YAML classification with SnakeYAML | ✅ Done 2026-04-16 |
| 12 | OPENICF-5012 | Handle `kind: AgentDialog` components | Open (deferred — needs IH decision) |
| 13 | OPENICF-5013 | Filter published agents only with `includeUnpublishedAgents` toggle | ✅ Done 2026-04-16 |
| 14 | OPENICF-5014 | SAS URL inventory fetch sent Dataverse Bearer token — Azure Blob rejected with HTTP 400 | ✅ Done 2026-04-17 |
| 15 | OPENICF-5015 | Owner attributes sourced from inventory JSON; moved to Dataverse `owninguser` expansion | ✅ Done 2026-04-17 |

### Nice to Fix

| # | Ticket | Issue | Status |
|---|--------|-------|--------|
| 9 | OPENICF-5009 | Filter pushdown is UID-only | Open (deferred) |

---

## Decisions Log

Ordered newest first.

### 2026-04-17 — OPENICF-5015 closed: owner attributes moved from inventory to Dataverse owninguser expansion

**Trigger.** `identityBindingScanEnabled=false` was configured at IH (identity binding scan not yet needed), but `toolsInventoryUrl` was set. The connector was fetching the inventory on every `__ACCOUNT__` search to populate owner attributes (`ownerPrincipalId`, `ownerDisplayName`, etc.), even though the flag was explicitly set to suppress inventory access. This was semantically wrong: the flag was supposed to gate all inventory reads, not just identity binding scans.

**Root cause.** The OPENICF-INV-001 implementation added `client.fetchInventoryJson()` calls to `searchAgentByUid` and `searchAllAgents` to populate owner attributes. These calls ran unconditionally regardless of `identityBindingScanEnabled`. The `agents[]` array in the inventory JSON was the source for owner data.

**Question: are owner attributes available directly from Dataverse?** Yes. The Dataverse `/bots` endpoint supports `$expand=owninguser(...)`, which joins the `systemuser` table inline. This provides `fullname`, `domainname`, `azureactivedirectoryobjectid`, and `internalemailaddress` in the same response as the bot row — no separate call, no additional permissions beyond the existing System Administrator role.

**Which owner ID is correct for IGA?** `azureactivedirectoryobjectid` (the AAD object ID), not `systemuserid` (the Dataverse-internal GUID). These are different for real users. The AAD object ID is the canonical IGA correlation key — it is what Graph, HR feeds, and other governed systems use to identify users. The inventory JSON was already using the AAD object ID; the Dataverse expansion provides the same value directly.

**Empty string handling.** SYSTEM-owned bots return `domainname: ""` and `internalemailaddress: null` from Dataverse. The connector suppresses both empty-string and null values for `ownerUserPrincipalName` and `ownerMail`. SYSTEM-owned agents emit only `ownerDisplayName: "SYSTEM"` with no other owner attributes.

**What was removed.** `buildAgentInventoryIndex()` on `BotDescriptor`, `addTextAttr()`/`addIntAttr()` helpers, the `agentInventory` parameter on `toConnectorObject()`, the inventory fetch blocks in `searchAgentByUid` and `searchAllAgents`. All connection reference attributes (`connectionReferenceId`, `connectionReferenceDisplayName`, etc.) and `ownerPrincipalType` were also removed — these were inventory-sourced and have no Dataverse equivalent worth surfacing.

**`agents[]` array in inventory.** Still present in the inventory JSON schema (the offline job produces it) but no longer read by the connector. The `identityBindings[]` array is the only section the connector reads.

**Files changed.** `M365CopilotClient.java` (`_ownerid_value` added to `BOTS_SELECT`, `BOTS_EXPAND` constant, `$expand` on `listAllBots()` and `getBot()`), `BotDescriptor.java` (4 owner fields, `fromJson()` reads `owninguser` node, `toConnectorObject()` emits directly, parameter and index removed), `M365CopilotCrudService.java` (inventory fetch removed from agent paths), `M365CopilotConnector.java` (connection reference schema attributes and `ownerPrincipalType` removed, 4 owner attributes retained), `M365CopilotConstants.java` (connection reference constants and `ATTR_OWNER_PRINCIPAL_TYPE` removed). All bracketed `// OPENICF-5015 begin/end`.

---

### 2026-04-17 — OPENICF-5014 closed: SAS URL inventory fetch rejected by Azure Blob

**Symptom.** HTTP 400 `InvalidAuthenticationInfo` from Azure Blob Storage when the connector fetched the inventory JSON. Error message: "Authentication information is not given in the correct format. Check the value of Authorization header."

**Root cause.** `fetchInventoryJson()` called `executeGet(cfg.getToolsInventoryUrl())`, which routed through `sendWithDataverseHeaders()`, adding `Authorization: Bearer <dataverse_token>`. Azure Blob Storage rejects any request that carries an `Authorization` header alongside a SAS signature in the query string — the two auth mechanisms are mutually exclusive.

**Fix.** Added `executeGetUnauthenticated(String url)` — a plain GET with only `Accept: application/json`, no Authorization header, delegating to the same `execute()` retry loop. `fetchInventoryJson()` calls this for URL-based inventory; the file-path branch reads from disk and is unaffected.

**Files changed.** `M365CopilotClient.java` only. Two bracketed regions: call site in `fetchInventoryJson()` and new `executeGetUnauthenticated()` method. All bracketed `// OPENICF-5014 begin/end`.

---

### 2026-04-16 — OPENICF-5008 closed: ICF paging over cached bulk data

**Scope.** Full-scan paths only across all four object classes. UID lookups are unaffected.

**Mechanism.** `pageSize(OperationOptions)` reads `options.getPageSize()`, returning 0 if unset. `pageOffset(OperationOptions)` parses the cookie as a decimal integer offset, returning 0 on null/empty/unparseable. When `pageSize == 0`, behavior is unchanged — full candidate list streamed as before. When `pageSize > 0`, the candidate list is sliced with `subList(offset, min(offset + pageSize, size))`.

**Cookie encoding.** Opaque decimal integer string. `nextCookie = String.valueOf(offset + pageSize)` when more results remain; null when the page exhausts the list. `remaining = max(0, total - (offset + pageSize))`.

**`SearchResultsHandler` guard.** `emitSearchResult()` instanceof-gates before calling `handleResult()`. When IDM does not pass a `SearchResultsHandler`, the call is a no-op.

**`searchAllAgents` restructure.** The `OPENICF-5013` inline skip loop was converted to a pre-filter that builds a `candidates` list before the page window is applied. Required so the page window is computed over the published set, not the raw Dataverse set.

**Only `M365CopilotCrudService.java` changed.** All bracketed `// OPENICF-5008 begin/end`.

---

### 2026-04-16 — OPENICF-5010 closed: connected-agent target bot resolution

**Two new attributes emitted on `__ACCOUNT__`:**
- `connectedAgentTargetSchemaName` — raw `action.botSchemaName` from the TaskDialog YAML.
- `connectedAgentTargetBotId` — resolved botid from an in-memory `schemaname → botid` index. Present only when resolution succeeds.

**Why emit both.** `connectedAgentTargetBotId` is what IGA needs for end-to-end relationship traversal. `connectedAgentTargetSchemaName` is the fallback for the unresolved case (cross-environment sub-agent references).

**Files changed.** `M365CopilotConstants.java`, `BotComponentDescriptor.java`, `BotDescriptor.java`, `M365CopilotCrudService.java`, `M365CopilotConnector.java`. All bracketed `// OPENICF-5010 begin/end`.

---

### 2026-04-16 — OPENICF-5011 closed: SnakeYAML parser swap

**Why the swap.** Production YAML samples revealed a concrete ordering hazard: `action.historyType.kind` appears two lines after `action.kind`. The line-scanner's `extractActionKind` was correct only because Microsoft emits `action.kind` first. Path-based lookup via a parsed map is unambiguous regardless of emission order.

**Library.** `org.yaml:snakeyaml:2.2`. `SafeConstructor` prevents arbitrary Java object instantiation from server-controlled YAML. Added to `pom.xml` as compile-scope dependency and to `Embed-Dependency`. Bundle size addition: ~360KB.

**Fallback.** If SnakeYAML throws (e.g. unquoted `@`), a line-scanner fallback extracts `kind` and `action.kind` for classification. A WARN is logged; attributes requiring full map access are left null.

**Files changed.** `pom.xml`, `BotComponentDescriptor.java`. All bracketed `// OPENICF-5011 begin/end`.

---

### 2026-04-16 — OPENICF-5013 closed: published-agent filter with config toggle

**Decision.** `publishedon IS NOT NULL` is the correct published signal. Probe against 31 production bots: `componentstate=0` and `statecode=0` for every record — zero discriminating power. `publishedon` cleanly split into published vs. draft and matches the Copilot Studio "Last published" UI column.

**Filter is client-side** behind config toggle `includeUnpublishedAgents` (default false). Server-side would bake a governance policy choice into the connector binary.

**UID lookup of unpublished agent when toggle is off** → `UnknownUidException`. Consistent with full-scan behavior; no half-on state.

**Files changed.** `BotDescriptor.java`, `M365CopilotConfiguration.java`, `M365CopilotCrudService.java`, `Messages.properties`. All bracketed `// OPENICF-5013 begin/end`.

---

### 2026-04-16 — OPENICF-5003 closed: retry loop in `M365CopilotClient.execute()`

**Policy.** 3 attempts total (1 initial + 2 retries). Retryable: 429, 503, 504. Honors `Retry-After` (delta-seconds only). Per-wait cap: 60s. Total bounded wait: ≤120s. WARN on first retry and on give-up.

**Files changed.** `M365CopilotClient.java` only. All bracketed `// OPENICF-5003 begin/end`.

---

### 2026-04-16 — OPENICF-5004: drop `PoolableConnector`

**Decision.** Drop `PoolableConnector`; IDM creates a fresh instance per reconciliation. Cache lifetime now guaranteed by class contract, not RCS configuration. Every reconciliation pays the full bulk-load cost — acceptable for IH's current object volumes.

**Files changed.** `M365CopilotConnector.java`. All bracketed `// OPENICF-5004 begin/end`.

---

### 2026-04-16 — OPENICF-5005 + OPENICF-5002: identityBindingScanEnabled flag

**Decision.** Default `false` — operators must opt in. `scanEnabled=false` → `UnknownUidException` on UID lookup (not just silent empty on full scan). WARN lives at runtime, not config-time validation — connector may be deployed before offline pipeline is live.

**Files changed.** `M365CopilotConfiguration.java`, `M365CopilotCrudService.java`, `Messages.properties`. All bracketed `// OPENICF-5005 begin/end` and `// OPENICF-5002 begin/end`.

---

### 2026-04-16 — OPENICF-5001: remove `useManagedIdentity`

**Decision.** Remove the property outright. Setting it to `true` produced a runtime NPE with no error at config validation. Managed identity is not implemented; the property was a landmine.

**Files changed.** `M365CopilotConfiguration.java`. All bracketed `// OPENICF-5001 begin/end`.

---

### 2026-04-16 — Production YAML samples: findings that unblocked 5010 and triggered 5011/5012

**botcomponentids probed:** `ab618ec8-90ca-49e6-ac4b-0e09bd201537`, `0367693d-d0a7-49cb-8488-1e5c7e214c1a`, `1d247cb9-4f09-48f3-9c55-b6a200f492b7`

**Finding 1 — target bot is `action.botSchemaName`:**
```yaml
kind: TaskDialog
action:
  kind: InvokeConnectedAgentTaskAction
  botSchemaName: new_CustomerServiceAgent
  historyType:
    kind: ConversationHistory
```

**Finding 2 — ordering hazard confirmed.** `action.historyType.kind` two lines after `action.kind` — line-scanner would misclassify if emitter reorders. Triggered OPENICF-5011.

**Finding 3 — `kind: AgentDialog` exists:**
```yaml
kind: AgentDialog
beginDialog:
  kind: OnToolSelected
  id: main
  description: A child agent
```
Child-agent-side declaration, mirror of parent's `InvokeConnectedAgentTaskAction` wrapper. Currently UNKNOWN/dropped. Tracked as OPENICF-5012.

**`schemaname` naming convention is not authoritative.** Patterns correlate with YAML `kind` but the classifier must always read the YAML.

---

## Resuming in a New Chat

v1 is complete. If work resumes on open items (OPENICF-5012, OPENICF-5009), use this protocol.

### What the new Claude needs to see

1. **This file (`IMPLEMENTATION_LOG.md`)** — full backlog, status, and decisions.
2. **The latest source of files relevant to the next ticket.** Upload directly; do not rely on the project mount.
3. **The session guidelines** (below).

### Kickoff message template

> Resuming Copilot Studio connector. Read `IMPLEMENTATION_LOG.md` in the project first, including the Decisions Log. Current state: v1 complete as of 2026-04-17; OPENICF-5012 deferred (needs IH governance decision on AgentDialog handling); OPENICF-5009 deferred (Nice to Fix, filter pushdown). Session guidelines: plan → ask → wait → implement; bracket all changes with `// OPENICF-XXXX begin` / `// OPENICF-XXXX end` comments (IDs ≥ 5001). Latest source files attached: {list filenames}.

### Known context that does NOT transfer via memory

- The reasoning in the Decisions Log above.
- The production YAML shape (AgentDialog, InvokeConnectedAgentTaskAction structure).
- The `schemaname` naming convention finding: patterns correlate but are not authoritative.
- The AgentDialog finding: child-agent-side declaration, mirror of the parent's wrapper. Currently UNKNOWN/dropped. Whether to surface it is OPENICF-5012.
- The SAS URL finding (OPENICF-5014): Azure Blob Storage rejects Bearer tokens alongside SAS signatures. The fix is `executeGetUnauthenticated()`.
- The owner attribute sourcing decision (OPENICF-5015): owner attrs come from Dataverse `owninguser` expansion, not the inventory `agents[]` array. `ownerPrincipalId` is the AAD object ID (`azureactivedirectoryobjectid`), not the Dataverse `systemuserid`.

**The only reliable transfer mechanism is this file.**

---

## Key Design Decisions

### Object Classes
| OC | Source | Notes |
|----|--------|-------|
| `__ACCOUNT__` | `bot` entity + `owninguser` expansion | One per published agent |
| `agentTool` | `botcomponent` componenttype=9, `data.kind=TaskDialog`, `action.kind` ∈ {InvokeConnectorTaskAction, InvokeExternalAgentTaskAction} | SnakeYAML classification |
| `agentKnowledgeBase` | `botcomponent` componenttype=16 | Clean, unambiguous |
| `agentIdentityBinding` | Offline inventory JSON (`identityBindings[]` array only) | Offline job resolves AAD groups via Graph |

### Type 9 YAML Discriminator (BotComponentDescriptor)
| `data.kind` | `data.action.kind` | Result |
|-------------|-------------------|--------|
| `AdaptiveDialog` | — | Topic → skip |
| `TaskDialog` | `InvokeConnectorTaskAction` | → `agentTool`, toolType=Connector |
| `TaskDialog` | `InvokeExternalAgentTaskAction` | → `agentTool`, toolType=MCP |
| `TaskDialog` | `InvokeConnectedAgentTaskAction` | → `connectedAgentReferences` (wrapper schemaName) + `connectedAgentTargetSchemaName` (raw `action.botSchemaName`) + `connectedAgentTargetBotId` (resolved botid, may be absent if unresolved) on `__ACCOUNT__` |
| `AgentDialog` | — | `UNKNOWN` / dropped. Child-agent-side declaration. Decision tracked as OPENICF-5012. |

### Relationship Attributes on `__ACCOUNT__`
- `toolIds` — multi-valued String, `botcomponentid` of classified tools
- `knowledgeBaseIds` — multi-valued String, `botcomponentid` of type 16 components
- `connectedAgentReferences` — multi-valued String, `schemaName` of the local TaskDialog wrapper component
- `connectedAgentTargetSchemaName` — multi-valued String, raw `action.botSchemaName` from YAML
- `connectedAgentTargetBotId` — multi-valued String, resolved botid from schemaname index; entries present only when resolution succeeds

### Owner Attributes on `__ACCOUNT__`
Sourced from Dataverse `$expand=owninguser` — no inventory read, no Graph call required.

| Attribute | Dataverse field | Notes |
|-----------|----------------|-------|
| `ownerPrincipalId` | `owninguser.azureactivedirectoryobjectid` | AAD object ID; absent for SYSTEM |
| `ownerDisplayName` | `owninguser.fullname` | Always present |
| `ownerUserPrincipalName` | `owninguser.domainname` | Absent when empty (SYSTEM) |
| `ownerMail` | `owninguser.internalemailaddress` | Absent when empty (SYSTEM) |

### Inventory JSON Access
- Inventory is fetched **only when `identityBindingScanEnabled=true`**.
- Only `identityBindings[]` is read; `agents[]` is ignored.
- Fetched via `executeGetUnauthenticated()` — no Authorization header — because SAS URLs and Bearer tokens are mutually exclusive.
- Cached for the connector instance lifetime (= one reconciliation).

### Cache Strategy (M365CopilotClient)
- `listAllBots()` and `listAllBotComponents()` cached on first call; reused within the reconciliation.
- Inventory JSON cached separately, only when needed.
- GET-by-UID calls bypass cache and go directly to Dataverse API.
- No stale-cache risk across reconciliations because `PoolableConnector` is not implemented.

### Auth
- Connector: client credentials only. Scope: `https://{environmentUrl}/.default`. No Graph permissions.
- Offline job: managed identity. Graph permissions: `GroupMember.Read.All`, optionally `User.Read.All`.
- Inventory blob: SAS URL (unauthenticated fetch from connector side).

### Identity Binding — Offline Job Branching
| `accesscontrolpolicy` | Label | Job action |
|-----------------------|-------|-----------|
| 0 | Any | Skip — no bindings |
| 1 | Agent readers | Deferred |
| 2 | Group membership | Resolve `authorizedsecuritygroupids` GUIDs via Graph ← **IH primary case** |
| 3 | Any (multi-tenant) | Skip — no bindings |

### Pagination
- Dataverse: OData `@odata.nextLink` — connector accumulates all pages before returning to handler.
- ICF layer: decimal integer cookie, `SearchResultsHandler` guard, `remaining` count emitted.

---

## Session Guidelines
1. Checkpoint after every two artifacts
2. Always plan first, ask for approval, wait for go-ahead before coding
3. Always ask for the latest source before starting work
4. If asking multiple questions, wait for all answers before proceeding
5. Make only those code changes that are absolutely required
6. For every fix, bracket changes with `// OPENICF-XXXX begin` and `// OPENICF-XXXX end` comments (IDs ≥ 5001)
