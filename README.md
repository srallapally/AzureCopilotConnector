# Copilot Studio OpenICF Connector

An OpenICF connector for Microsoft Copilot Studio agent governance via the Power Platform Dataverse API. Surfaces Copilot Studio agents, their tools, knowledge sources, and authorized security groups into PingOne IDM (OpenIDM) for identity governance and access certification.

---

## Overview

| Field | Value |
|-------|-------|
| Maven artifactId | `m365copilot-connector` |
| Version | `1.5.20.33` |
| Java | 17 |
| Framework | OpenICF 1.5 |
| Package | `org.forgerock.openicf.connectors.m365copilot` |
| Target | PingOne IDM / OpenIDM via RCS |

---

## What It Governs

The connector answers four governance questions about your Power Platform environment:

- **What Copilot Studio agents exist?** Published agents, their metadata, AI settings, and schema names.
- **Who owns each agent?** Owner identity sourced directly from Dataverse — AAD object ID, display name, UPN, and email.
- **What tools and knowledge sources does each agent use?** Power Platform connector tools, MCP tools, and linked knowledge bases.
- **Which Azure AD security groups are authorized to interact with each agent?** Resolved via an offline Azure Function that writes a JSON inventory to Blob Storage.

---

## Architecture

```
Dataverse Web API ──────────────────────────────────────────────────┐
  bot (+ owninguser expansion)                                       │
  botcomponent (type 9 + type 16)                                    ▼
                                                         M365CopilotClient
Azure Blob Storage (SAS URL) ─────────────────────────►  + token cache
  identity-bindings.json                                 + bulk query cache
  [written by offline Azure Function]                    + unauthenticated
                                                           inventory fetch
                                                               │
                                                    M365CopilotCrudService
                                                    BotComponentDescriptor
                                                    (SnakeYAML classification)
                                                               │
                                            ┌──────────────────┼──────────────────┐
                                            ▼                  ▼                  ▼
                                       __ACCOUNT__         agentTool       agentKnowledgeBase
                                    (Copilot agent)    (connector/MCP)      (knowledge source)
                                            │
                                            └──────────────────►  agentIdentityBinding
                                                                   (AAD group × agent)
```

---

## Object Classes

### `__ACCOUNT__` — Copilot Agent

One object per published agent (by default). Populated from the Dataverse `bot` entity with `$expand=owninguser`.

| Attribute | Type | Source |
|-----------|------|--------|
| `agentId` | String | `botid` |
| `schemaName` | String | `schemaname` |
| `accessControlPolicy` | String | `accesscontrolpolicy` OptionSet label |
| `authenticationMode` | String | `authenticationmode` |
| `statecode` / `statuscode` | Integer | Dataverse fields |
| `publishedOn` / `createdOn` / `modifiedOn` | String | ISO-8601 timestamps |
| `contentModeration` | String | Parsed from `configuration` blob |
| `generativeActionsEnabled` | Boolean | Parsed from `configuration` blob |
| `useModelKnowledge` | Boolean | Parsed from `configuration` blob |
| `ownerPrincipalId` | String | `owninguser.azureactivedirectoryobjectid` (AAD object ID) |
| `ownerDisplayName` | String | `owninguser.fullname` |
| `ownerUserPrincipalName` | String | `owninguser.domainname` |
| `ownerMail` | String | `owninguser.internalemailaddress` |
| `toolIds` | String[] | `botcomponentid` of child tools |
| `knowledgeBaseIds` | String[] | `botcomponentid` of child knowledge sources |
| `connectedAgentReferences` | String[] | `schemaname` of sub-agent wrapper components |
| `connectedAgentTargetSchemaName` | String[] | Raw `action.botSchemaName` from YAML |
| `connectedAgentTargetBotId` | String[] | Resolved `botid` of connected agent targets |

### `agentTool` — Agent Tool

One object per tool (Power Platform connector or MCP server). Populated from Dataverse `botcomponent` type 9, discriminated by YAML `data` field.

| Attribute | Type | Source |
|-----------|------|--------|
| `agentId` | String | `_parentbotid_value` |
| `toolType` | String | `Connector` or `MCP` |
| `connectionReference` | String | YAML `connectionReference` |
| `operationId` | String | YAML `operationId` |
| `description` | String | YAML `modelDescription` |
| `schemaName` | String | `schemaname` |

### `agentKnowledgeBase` — Knowledge Source

One object per knowledge source. Populated from Dataverse `botcomponent` type 16.

| Attribute | Type | Source |
|-----------|------|--------|
| `agentId` | String | `_parentbotid_value` |
| `schemaName` | String | `schemaname` |
| `description` | String | `description` |

### `agentIdentityBinding` — Identity Binding

One object per (agent × AAD security group) pair. Populated from an offline JSON inventory written by a scheduled Azure Function. Only produced when `identityBindingScanEnabled=true`.

| Attribute | Type | Source |
|-----------|------|--------|
| `agentId` | String | Inventory `agentId` |
| `kind` | String | Hardcoded: `GROUP` |
| `principal` | String | JSON: `{groupId, displayName, mail, groupType}` |
| `scope` | String | Hardcoded: `ENVIRONMENT` |

Composite UID: `{agentId}:{groupId}`

---

## Tool Classification

Dataverse uses a single `componenttype=9` for topics, tools, and sub-agent references. The connector parses the YAML `data` field of each type 9 record using SnakeYAML to classify it:

| `data.kind` | `data.action.kind` | Result |
|-------------|-------------------|--------|
| `AdaptiveDialog` | — | Conversation topic — skipped |
| `TaskDialog` | `InvokeConnectorTaskAction` | → `agentTool`, `toolType=Connector` |
| `TaskDialog` | `InvokeExternalAgentTaskAction` | → `agentTool`, `toolType=MCP` |
| `TaskDialog` | `InvokeConnectedAgentTaskAction` | → connected-agent attributes on `__ACCOUNT__` |
| `AgentDialog` | — | Dropped (child-agent declaration — see open items) |

---

## Configuration

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `tenantId` | Y | — | Azure AD tenant GUID |
| `environmentUrl` | Y | — | Dataverse environment URL, e.g. `https://org51451987.crm.dynamics.com` |
| `clientId` | Y | — | Azure AD application (client) ID |
| `clientSecret` | Y | — | Azure AD application client secret (`GuardedString`) |
| `toolsInventoryUrl` | N | — | SAS URL to identity binding JSON in Azure Blob Storage |
| `toolsInventoryFilePath` | N | — | Local path to inventory JSON. Dev/test only. |
| `identityBindingScanEnabled` | N | `false` | Enable `agentIdentityBinding` reconciliation. Inventory is never fetched when `false`. |
| `includeUnpublishedAgents` | N | `false` | Include draft agents (`publishedon` is null). |
| `httpTimeoutSeconds` | N | `30` | HTTP connect and read timeout. |
| `logPayloads` | N | `false` | Log raw API responses. Dev/troubleshooting only. |

---

## Authentication & Permissions

### Connector service principal

The connector uses OAuth2 client credentials (`grant_type=client_credentials`) to acquire a Dataverse token scoped to `https://{environmentUrl}/.default`. No Microsoft Graph permissions are required.

**Required setup:**
1. Create an Azure AD app registration (no API permissions needed).
2. Register the app as a Power Platform admin application (one-time per tenant).
3. Add the app as a Dataverse application user with the **System Administrator** security role in the target environment via the Power Platform Admin Center.

**Inventory blob:** Fetched via SAS URL without an Authorization header. SAS and Bearer token auth are mutually exclusive in Azure Blob Storage — the connector sends no `Authorization` header for inventory fetches.

### Offline inventory job (Azure Function)

The identity binding inventory is produced by a separate Azure Function with a system-assigned managed identity. It requires:

- Dataverse **System Administrator** role (to read `authorizedsecuritygroupids`)
- Microsoft Graph app role: **`GroupMember.Read.All`** (to resolve group GUIDs)
- Azure Blob Storage: **Storage Blob Data Contributor** (to write the inventory JSON)

---

## Building

```bash
mvn clean package
```

The output JAR is an OSGi bundle at `target/m365copilot-connector-1.5.20.33.jar`.

**Dependencies embedded in the bundle:**
- `httpclient` 4.5.14
- `httpcore` 4.4.16
- `jackson-databind` / `jackson-core` / `jackson-annotations` 2.16.2
- `snakeyaml` 2.2
- `commons-logging`, `commons-codec`

---

## Deployment

Copy the JAR to the OpenIDM/RCS connectors directory and configure via the provisioner REST API:

```bash
PUT /openidm/config/provisioner.openicf/copilot-studio
Content-Type: application/json

{
  "connectorRef": {
    "bundleName": "org.forgerock.openicf.connectors.m365copilot-connector",
    "bundleVersion": "1.5.20.33",
    "connectorName": "org.forgerock.openicf.connectors.m365copilot.M365CopilotConnector"
  },
  "configurationProperties": {
    "tenantId": "<TENANT_GUID>",
    "environmentUrl": "https://org51451987.crm.dynamics.com",
    "clientId": "<CLIENT_ID>",
    "clientSecret": "<CLIENT_SECRET">,
    "toolsInventoryUrl": "https://stcopilotinventory.blob.core.windows.net/tools-inventory/copilot-studio/inventory.json?<SAS_PARAMS>",
    "identityBindingScanEnabled": true,
    "includeUnpublishedAgents": false
  }
}
```

---

## Operations

| Operation | `__ACCOUNT__` | `agentTool` | `agentKnowledgeBase` | `agentIdentityBinding` |
|-----------|:---:|:---:|:---:|:---:|
| Search (all) | ✓ | ✓ | ✓ | ✓ |
| GET by UID | ✓ | ✓ | ✓ | ✓ |
| Paged search | ✓ | ✓ | ✓ | ✓ |
| Create / Update / Delete | ✗ | ✗ | ✗ | ✗ |
| Sync (incremental) | ✗ | ✗ | ✗ | ✗ |

**Test operation:** `GET /bots?$top=1&$select=botid` — verifies auth and Dataverse connectivity.

**Paging:** Decimal integer offset cookie. Pass `pageSize` in `OperationOptions`; the connector returns a `SearchResult` with the next cookie and remaining count.

---

## Identity Binding — Offline Job

The `agentIdentityBinding` object class depends on a scheduled Azure Function (`func-copilot-inventory`) that:

1. Reads `authorizedsecuritygroupids` from the Dataverse `bot` entity for agents with `accesscontrolpolicy=2` (Group membership).
2. Resolves each group GUID via Microsoft Graph (`GroupMember.Read.All`).
3. Writes `identity-bindings.json` to Azure Blob Storage.

The connector reads this file at reconciliation time via SAS URL. See `copilot-studio-inventory-cookbook.md` for full deployment instructions.

**Access control policy branching:**

| Policy | Label | Bindings |
|--------|-------|----------|
| 0 | Any | None emitted |
| 1 | Agent readers (Dataverse record-level) | Not implemented |
| 2 | Group membership | Resolved via Graph |
| 3 | Any (multi-tenant) | None emitted |

---

## Known Limitations

- **Read-only.** No Create, Update, Delete, or Sync operations.
- **Single environment per instance.** One `environmentUrl` per connector configuration.
- **Filter pushdown limited to UID equality.** All other filters fall through to full scan + ICF post-filter.
- **Full bulk load per reconciliation.** No incremental delta; the connector is non-poolable by design to prevent stale cache reuse.
- **`AgentDialog` components dropped.** Child-agent-side declarations classified as `UNKNOWN` pending governance decision (tracked as OPENICF-5012).
- **Connected-agent resolution is same-environment only.** Cross-environment sub-agent references produce `connectedAgentTargetSchemaName` but no `connectedAgentTargetBotId`.
- **SYSTEM-owned agents have no owner identity.** `ownerPrincipalId`, `ownerUserPrincipalName`, and `ownerMail` are absent; only `ownerDisplayName: "SYSTEM"` is emitted.
- **Published-agent filter relies on `publishedon`.** Whether this field is cleared on explicit unpublish has not been confirmed.
- **SAS URL requires rotation.** The `toolsInventoryUrl` SAS token has a fixed expiry and must be rotated before it expires.
- **Identity bindings require `identityBindingScanEnabled=true`.** Inventory is never fetched when the flag is `false`, regardless of `toolsInventoryUrl` being configured.

---

## Documentation

| Document | Description |
|----------|-------------|
| `copilot-studio-connector-design.md` | Full design specification — architecture, schema, API queries, auth, class responsibilities, limitations |
| `IMPLEMENTATION_LOG.md` | Implementation decisions log, fix backlog, and resumption context for future development sessions |
| `copilot-studio-inventory-cookbook.md` | API & operations runbook — all `az` CLI commands, `curl` calls, permissions setup, and troubleshooting |
