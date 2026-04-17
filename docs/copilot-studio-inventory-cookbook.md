# Copilot Studio — API & Operations Runbook

_Covers every `az` CLI command, `curl` call, and API endpoint used by both
the OpenICF connector and the `func-copilot-inventory` Azure Function._

---

## API Permissions Summary

Two principals are involved: the **connector service principal** (used by the OpenICF connector at reconciliation time) and the **inventory job managed identity** (used by the Azure Function). Their permissions are completely separate.

### Connector service principal

| Resource | Permission type | Permission | Purpose |
|---|---|---|---|
| Dataverse environment | Dataverse security role | System Administrator | Read `bot`, `botcomponent`, `systemuser` (via owninguser expansion) |
| Entra ID | None | — | App registration only; no Azure API permissions required |
| Microsoft Graph | **None** | — | Not used by connector |
| Azure Blob Storage | None | — | Inventory fetched via SAS URL; no RBAC role needed |

The connector service principal must also be registered as a **Power Platform admin application** (one-time per tenant, via the BAP API or Admin Center) before it can be added as a Dataverse application user.

**No Microsoft Graph permissions are required by the connector.** Owner attributes (`ownerDisplayName`, `ownerPrincipalId`, `ownerUserPrincipalName`, `ownerMail`) are sourced directly from the Dataverse `systemuser` table via `$expand=owninguser` — a Dataverse-internal join that requires no Graph access.

### Inventory job managed identity (Azure Function)

| Resource | Permission type | Permission | Purpose |
|---|---|---|---|
| Dataverse environment | Dataverse security role | System Administrator | Read `bot.authorizedsecuritygroupids`, `bot.accesscontrolpolicy` |
| Microsoft Graph | App role | `GroupMember.Read.All` | Resolve AAD group GUIDs to `displayName`, `mail`, `groupType` |
| Microsoft Graph | App role | `User.Read.All` | Optional — enrich owner `userPrincipalName` and `mail` in inventory (not used by connector, only by job) |
| Azure Blob Storage | RBAC role | Storage Blob Data Contributor | Write inventory JSON to blob container |

---

## 1. Prerequisites

```bash
# Azure Functions Core Tools v4
npm install -g azure-functions-core-tools@4 --unsafe-perm true

# Authenticate and set subscription
az login
az account set --subscription <IH_SUBSCRIPTION_ID>
```

---

## 2. Infrastructure Provisioning

### 2.1 Resource group

```bash
az group create \
  --name rg-copilot-inventory \
  --location eastus
```

### 2.2 Storage account (Function App backing store + inventory blob)

```bash
az storage account create \
  --name stcopilotinventory \
  --resource-group rg-copilot-inventory \
  --sku Standard_LRS \
  --allow-blob-public-access false
```

### 2.3 Function App (Linux Consumption, Python 3.11)

```bash
# Note: --os-type Linux is required — default is Windows which does not support Python
az functionapp create \
  --resource-group rg-copilot-inventory \
  --consumption-plan-location eastus \
  --runtime python \
  --runtime-version 3.11 \
  --functions-version 4 \
  --name func-copilot-inventory \
  --storage-account stcopilotinventory \
  --os-type Linux

# Assign system-managed identity (run separately to avoid zsh bracket issue)
az functionapp identity assign \
  --name func-copilot-inventory \
  --resource-group rg-copilot-inventory
```

The `identity assign` output includes `principalId` — save it:

```bash
FUNC_PRINCIPAL=$(az functionapp identity show \
  --name func-copilot-inventory \
  --resource-group rg-copilot-inventory \
  --query principalId -o tsv)

echo $FUNC_PRINCIPAL
```

### 2.4 Inventory blob container

```bash
az storage container create \
  --account-name stcopilotinventory \
  --name tools-inventory \
  --auth-mode login
```

---

## 3. Role Assignments

### 3.1 Storage Blob Data Contributor (job writes inventory blob)

```bash
STORAGE_ID=$(az storage account show \
  --name stcopilotinventory \
  --resource-group rg-copilot-inventory \
  --query id -o tsv)

az role assignment create \
  --assignee $FUNC_PRINCIPAL \
  --role "Storage Blob Data Contributor" \
  --scope $STORAGE_ID
```

### 3.2 SAS URL for connector blob access

The connector reads the inventory blob via a SAS URL — no RBAC role is required on the connector service principal. Azure Blob Storage rejects requests that carry both a SAS signature and an `Authorization` header; the connector uses an unauthenticated GET (SAS-only).

Generate a SAS URL for the inventory blob:

```bash
# Set expiry as needed — rotate before expiry
az storage blob generate-sas \
  --account-name stcopilotinventory \
  --container-name tools-inventory \
  --name copilot-studio/inventory.json \
  --permissions r \
  --expiry 2027-01-01T00:00:00Z \
  --auth-mode login \
  --as-user \
  --full-uri
```

Configure the output URL as `toolsInventoryUrl` in the connector. Establish a SAS rotation procedure before the expiry date.

---

## 4. Microsoft Graph Permissions (for inventory job managed identity)

System-assigned managed identities have no app registration, so `az ad app
permission add` does not work. Grant permissions by directly assigning app roles
to the managed identity's service principal.

**These permissions are for the inventory job only. The connector does not require any Graph permissions.**

### 4.1 Get Microsoft Graph service principal ID for your tenant

```bash
GRAPH_SP_ID=$(az ad sp show \
  --id 00000003-0000-0000-c000-000000000000 \
  --query id -o tsv)

echo $GRAPH_SP_ID
```

### 4.2 Look up the correct app role ID (do not hardcode — IDs vary by tenant)

```bash
# GroupMember.Read.All
az ad sp show \
  --id 00000003-0000-0000-c000-000000000000 \
  --query "appRoles[?value=='GroupMember.Read.All'].id" \
  -o tsv

# User.Read.All (optional — for owner UPN/mail enrichment in inventory job)
az ad sp show \
  --id 00000003-0000-0000-c000-000000000000 \
  --query "appRoles[?value=='User.Read.All'].id" \
  -o tsv
```

### 4.3 Assign GroupMember.Read.All

```bash
GROUP_MEMBER_READ_ALL="<id from step 4.2>"

az rest --method POST \
  --uri "https://graph.microsoft.com/v1.0/servicePrincipals/${GRAPH_SP_ID}/appRoleAssignedTo" \
  --body "{
    \"principalId\": \"${FUNC_PRINCIPAL}\",
    \"resourceId\": \"${GRAPH_SP_ID}\",
    \"appRoleId\": \"${GROUP_MEMBER_READ_ALL}\"
  }"
```

### 4.4 Assign User.Read.All (optional)

```bash
USER_READ_ALL="<id from step 4.2>"

az rest --method POST \
  --uri "https://graph.microsoft.com/v1.0/servicePrincipals/${GRAPH_SP_ID}/appRoleAssignedTo" \
  --body "{
    \"principalId\": \"${FUNC_PRINCIPAL}\",
    \"resourceId\": \"${GRAPH_SP_ID}\",
    \"appRoleId\": \"${USER_READ_ALL}\"
  }"
```

---

## 5. Power Platform Registration

Both the connector service principal and the inventory job managed identity must be registered as Dataverse application users. This cannot be done via CLI — use the Power Platform Admin Center.

For each principal, get its `appId`:

```bash
# Connector service principal appId
az ad sp show \
  --id <CONNECTOR_SP_OBJECT_ID> \
  --query '{displayName:displayName, appId:appId}' \
  -o json

# Inventory job managed identity appId
az ad sp show \
  --id $FUNC_PRINCIPAL \
  --query '{displayName:displayName, appId:appId}' \
  -o json
```

Then in the portal for each:

1. Go to `https://admin.powerplatform.microsoft.com`
2. Select environment (e.g. `prod-na-95fcaf76`)
3. **Settings → Users + permissions → Application users**
4. **New app user** → search by the `appId` value above
5. Set Business unit to the root business unit
6. Assign security role: **System Administrator**
7. Click **Create**

---

## 6. Application Settings

```bash
az functionapp config appsettings set \
  --name func-copilot-inventory \
  --resource-group rg-copilot-inventory \
  --settings \
    CS_TENANT_ID="<IH_TENANT_GUID>" \
    CS_ENVIRONMENT_URL="https://org51451987.crm.dynamics.com" \
    CS_AUTH_MODE="MANAGED_IDENTITY" \
    TOOLS_INVENTORY_STORAGE_ACCOUNT_URL="https://stcopilotinventory.blob.core.windows.net" \
    TOOLS_INVENTORY_CONTAINER="tools-inventory" \
    TOOLS_INVENTORY_BLOB="copilot-studio/inventory.json"
```

Required settings summary:

| Setting | Description |
|---|---|
| `CS_TENANT_ID` | Entra tenant GUID |
| `CS_ENVIRONMENT_URL` | Dataverse environment URL |
| `CS_AUTH_MODE` | `MANAGED_IDENTITY` (production) or `CLIENT_CREDENTIALS` (dev/test) |
| `TOOLS_INVENTORY_STORAGE_ACCOUNT_URL` | Blob Storage account URL |
| `TOOLS_INVENTORY_CONTAINER` | Container name |
| `TOOLS_INVENTORY_BLOB` | Blob path within the container |
| `CS_CLIENT_ID` | Required only when `CS_AUTH_MODE=CLIENT_CREDENTIALS` |
| `CS_CLIENT_SECRET_NAME` | Key Vault secret name — required for `CLIENT_CREDENTIALS` mode |
| `KEY_VAULT_URL` | Key Vault URL — required for `CLIENT_CREDENTIALS` mode |

---

## 7. CORS (Portal Test Button)

```bash
az functionapp cors add \
  --name func-copilot-inventory \
  --resource-group rg-copilot-inventory \
  --allowed-origins https://portal.azure.com
```

---

## 8. Deployment

Run from the directory containing `host.json` and `requirements.txt`:

```bash
func azure functionapp publish func-copilot-inventory --python
```

Expected directory structure before publishing:

```
host.json
requirements.txt
local.settings.json        ← dev only, not deployed
copilot_inventory_job/
├── __init__.py
├── function.json
├── copilot_studio_inventory_job.py
├── config_loader.py
└── copilot_studio_inventory_config.py
```

---

## 9. Manual Trigger

Linux Consumption does not support `func azure functionapp logstream` or the
portal Test button without CORS. Use the admin endpoint instead:

```bash
MASTER_KEY=$(az functionapp keys list \
  --name func-copilot-inventory \
  --resource-group rg-copilot-inventory \
  --query masterKey -o tsv)

curl -X POST \
  "https://func-copilot-inventory.azurewebsites.net/admin/functions/copilot_inventory_job" \
  -H "x-functions-key: $MASTER_KEY" \
  -H "Content-Type: application/json" \
  -d '{}'
```

---

## 10. Verify Blob Output

```bash
# Check the blob exists and get metadata
az storage blob show \
  --account-name stcopilotinventory \
  --container-name tools-inventory \
  --name copilot-studio/inventory.json \
  --auth-mode login

# Download and inspect
az storage blob download \
  --account-name stcopilotinventory \
  --container-name tools-inventory \
  --name copilot-studio/inventory.json \
  --file /tmp/inventory.json \
  --auth-mode login

cat /tmp/inventory.json | python3 -m json.tool | head -80
```

---

## 11. Logs and Monitoring

### Application Insights (primary — Linux Consumption only)

```bash
# Open Live Metrics in browser
func azure functionapp logstream func-copilot-inventory --browser
```

Or query via CLI:

```bash
az monitor app-insights query \
  --app func-copilot-inventory \
  --analytics-query "traces | where timestamp > ago(10m) | order by timestamp desc | take 50" \
  --output table
```

### Invocation history (portal)

**func-copilot-inventory → Monitor → Invocations**

Each row shows success/failure, duration, and links to full log output including
exception tracebacks.

---

## 12. Troubleshooting Reference

### ModuleNotFoundError: No module named 'config_loader'

**Cause:** Flat absolute imports (`from config_loader import ...`) do not work
inside a function subdirectory on Linux Consumption. The runtime adds
`/home/site/wwwroot` to `sys.path` but not the function subdirectory.

**Fix:** Use relative imports in all files inside the function package:

```python
# __init__.py
from .config_loader import load_inventory_config
from .copilot_studio_inventory_job import run_inventory_job

# config_loader.py
from .copilot_studio_inventory_config import CopilotStudioInventoryJobConfig, AuthMode

# copilot_studio_inventory_job.py
from .copilot_studio_inventory_config import AuthMode, CopilotStudioInventoryJobConfig
```

Redeploy after fixing.

### 403 — "The user is not a member of the organization"

**Cause:** The managed identity (or connector service principal) is not registered
as a Dataverse application user in the target Power Platform environment.

**Fix:** Complete step 5 (Power Platform Registration). Use the `appId` from
`az ad sp show`, not the `principalId`.

### 403 — "Permission being assigned was not found on application"

**Cause:** The Graph app role ID used in the `az rest` call is wrong. App role
IDs differ by tenant.

**Fix:** Look up the correct ID at runtime:

```bash
az ad sp show \
  --id 00000003-0000-0000-c000-000000000000 \
  --query "appRoles[?value=='GroupMember.Read.All'].id" \
  -o tsv
```

Use that value — do not hardcode from documentation.

### HTTP 400 InvalidAuthenticationInfo from Azure Blob Storage

**Cause:** The connector sent an `Authorization: Bearer` header alongside a SAS
URL. Azure Blob Storage rejects requests that carry both a SAS signature and an
Authorization header simultaneously.

**Fix:** This is resolved in OPENICF-5014. The connector uses
`executeGetUnauthenticated()` for SAS URL fetches — no Authorization header.
If you see this error, verify you are running the post-5014 build.

### zsh: no matches found: [system]

**Cause:** zsh treats `[system]` as a glob pattern.

**Fix:** Quote it or use `identity assign` separately:

```bash
az functionapp identity assign \
  --name func-copilot-inventory \
  --resource-group rg-copilot-inventory
```

### Runtime python not supported for os windows

**Cause:** `az functionapp create` defaults to Windows OS.

**Fix:** Add `--os-type Linux` to the create command.

### func azure functionapp logstream — not supported

**Cause:** Linux Consumption and Flex plans do not support CLI log streaming.

**Fix:** Use `--browser` flag to open Application Insights Live Metrics, or
query invocation history via the portal Monitor tab.

### Can't determine project language from files

**Cause:** Core Tools cannot find `local.settings.json` (note: dot separator,
not underscore).

**Fix:**

```bash
cp local_settings.json local.settings.json
func azure functionapp publish func-copilot-inventory --python
```

### SAS URL has expired

**Cause:** The SAS token in `toolsInventoryUrl` has passed its expiry date.
The connector will get HTTP 403 `AuthenticationFailed` from Blob Storage.

**Fix:** Generate a new SAS URL (see step 3.2) and update the connector
configuration. Establish a rotation procedure before the next expiry.

---

## 13. Connector — Dataverse API Calls

These are the exact Dataverse OData calls the OpenICF connector makes at
reconciliation time. Useful for manual testing and for verifying permissions.

```bash
# Acquire a Dataverse token for the connector service principal
curl -X POST \
  "https://login.microsoftonline.com/<TENANT_ID>/oauth2/v2.0/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=<CONNECTOR_CLIENT_ID>" \
  -d "client_secret=<CONNECTOR_CLIENT_SECRET>" \
  -d "scope=https://org51451987.crm.dynamics.com/.default"

DV_TOKEN="<access_token from above>"
```

```bash
# Test connection (connector TestOp)
curl -s \
  "https://org51451987.crm.dynamics.com/api/data/v9.2/bots?\$top=1&\$select=botid" \
  -H "Authorization: Bearer $DV_TOKEN" \
  -H "Accept: application/json" \
  -H "OData-Version: 4.0" \
  -H "OData-MaxVersion: 4.0"

# Bulk agent list with owninguser expansion (connector listAllBots)
curl -s \
  "https://org51451987.crm.dynamics.com/api/data/v9.2/bots?\$select=botid,name,statecode,statuscode,accesscontrolpolicy,authorizedsecuritygroupids,authenticationmode,runtimeprovider,language,schemaname,publishedon,createdon,modifiedon,configuration,_ownerid_value&\$expand=owninguser(\$select=systemuserid,fullname,domainname,azureactivedirectoryobjectid,internalemailaddress)&\$orderby=createdon%20asc" \
  -H "Authorization: Bearer $DV_TOKEN" \
  -H "Accept: application/json" \
  -H "OData-Version: 4.0" \
  -H "OData-MaxVersion: 4.0"

# Bulk botcomponent list (connector listAllBotComponents)
curl -s \
  "https://org51451987.crm.dynamics.com/api/data/v9.2/botcomponents?\$select=botcomponentid,name,componenttype,data,_parentbotid_value,schemaname,description,createdon,modifiedon&\$filter=componenttype%20eq%209%20or%20componenttype%20eq%2016&\$orderby=_parentbotid_value%20asc,componenttype%20asc" \
  -H "Authorization: Bearer $DV_TOKEN" \
  -H "Accept: application/json" \
  -H "OData-Version: 4.0" \
  -H "OData-MaxVersion: 4.0"

# GET single bot by UID with owninguser expansion (connector getBot)
curl -s \
  "https://org51451987.crm.dynamics.com/api/data/v9.2/bots(<BOT_ID>)?\$select=botid,name,statecode,statuscode,accesscontrolpolicy,authorizedsecuritygroupids,authenticationmode,runtimeprovider,language,schemaname,publishedon,createdon,modifiedon,configuration,_ownerid_value&\$expand=owninguser(\$select=systemuserid,fullname,domainname,azureactivedirectoryobjectid,internalemailaddress)" \
  -H "Authorization: Bearer $DV_TOKEN" \
  -H "Accept: application/json" \
  -H "OData-Version: 4.0" \
  -H "OData-MaxVersion: 4.0"

# GET single botcomponent by UID (connector getBotComponent)
curl -s \
  "https://org51451987.crm.dynamics.com/api/data/v9.2/botcomponents(<BOTCOMPONENT_ID>)?\$select=botcomponentid,name,componenttype,data,_parentbotid_value,schemaname,description,createdon,modifiedon" \
  -H "Authorization: Bearer $DV_TOKEN" \
  -H "Accept: application/json" \
  -H "OData-Version: 4.0" \
  -H "OData-MaxVersion: 4.0"
```

```bash
# Fetch inventory blob via SAS URL (connector fetchInventoryJson — NO Authorization header)
curl -s "<TOOLS_INVENTORY_SAS_URL>" \
  -H "Accept: application/json"
# Note: do NOT add an Authorization header — SAS and Bearer are mutually exclusive
```

---

## 14. Inventory Job — Dataverse API Calls

These are the Dataverse OData calls the inventory job makes. Useful for manual
testing with a Dataverse token.

```bash
# Acquire a Dataverse token (client credentials — dev/test only; job uses managed identity)
curl -X POST \
  "https://login.microsoftonline.com/<TENANT_ID>/oauth2/v2.0/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=<CLIENT_ID>" \
  -d "client_secret=<CLIENT_SECRET>" \
  -d "scope=https://org51451987.crm.dynamics.com/.default"

DV_TOKEN="<access_token from above>"
```

```bash
# List all bots with owner and policy fields
curl -s \
  "https://org51451987.crm.dynamics.com/api/data/v9.2/bots?\$select=botid,name,schemaname,publishedon,accesscontrolpolicy,authorizedsecuritygroupids,statecode,statuscode,_ownerid_value,modifiedon,createdon" \
  -H "Authorization: Bearer $DV_TOKEN" \
  -H "Accept: application/json" \
  -H "OData-Version: 4.0" \
  -H "OData-MaxVersion: 4.0" \
  -H "Prefer: odata.include-annotations=\"*\""

# Filter to policy=2 agents only (group membership — primary IH case)
curl -s \
  "https://org51451987.crm.dynamics.com/api/data/v9.2/bots?\$select=botid,name,accesscontrolpolicy,authorizedsecuritygroupids&\$filter=accesscontrolpolicy%20eq%202&\$top=5" \
  -H "Authorization: Bearer $DV_TOKEN" \
  -H "Accept: application/json" \
  -H "OData-Version: 4.0" \
  -H "OData-MaxVersion: 4.0"

# Get a single bot by ID
curl -s \
  "https://org51451987.crm.dynamics.com/api/data/v9.2/bots(<BOT_ID>)" \
  -H "Authorization: Bearer $DV_TOKEN" \
  -H "Accept: application/json" \
  -H "OData-Version: 4.0" \
  -H "OData-MaxVersion: 4.0"

# Resolve owner (systemuser) — for job owner enrichment
curl -s \
  "https://org51451987.crm.dynamics.com/api/data/v9.2/systemusers(<USER_ID>)?\$select=systemuserid,fullname,domainname,azureactivedirectoryobjectid,internalemailaddress" \
  -H "Authorization: Bearer $DV_TOKEN" \
  -H "Accept: application/json" \
  -H "OData-Version: 4.0" \
  -H "OData-MaxVersion: 4.0"

# Get Dataverse entity metadata (field type lookup)
curl -s \
  "https://org51451987.crm.dynamics.com/api/data/v9.2/EntityDefinitions(LogicalName='bot')/Attributes(LogicalName='authorizedsecuritygroupids')" \
  -H "Authorization: Bearer $DV_TOKEN" \
  -H "Accept: application/json" \
  -H "OData-Version: 4.0" \
  -H "OData-MaxVersion: 4.0"
```

---

## 15. Microsoft Graph API Calls (made by the inventory job at runtime)

```bash
# Acquire a Graph token (client credentials — dev/test only; job uses managed identity)
curl -X POST \
  "https://login.microsoftonline.com/<TENANT_ID>/oauth2/v2.0/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=<CLIENT_ID>" \
  -d "client_secret=<CLIENT_SECRET>" \
  -d "scope=https://graph.microsoft.com/.default"

GRAPH_TOKEN="<access_token from above>"

# Resolve Entra group by GUID (requires GroupMember.Read.All)
curl -s \
  "https://graph.microsoft.com/v1.0/groups/<GROUP_ID>?\$select=id,displayName,mail,groupTypes,securityEnabled,mailEnabled" \
  -H "Authorization: Bearer $GRAPH_TOKEN" \
  -H "Accept: application/json"

# Resolve user by AAD object ID — optional owner enrichment (requires User.Read.All)
curl -s \
  "https://graph.microsoft.com/v1.0/users/<USER_AAD_ID>?\$select=id,displayName,userPrincipalName,mail" \
  -H "Authorization: Bearer $GRAPH_TOKEN" \
  -H "Accept: application/json"

# Check app role assignments on managed identity (verify Graph permissions granted)
curl -s \
  "https://graph.microsoft.com/v1.0/servicePrincipals/<FUNC_PRINCIPAL>/appRoleAssignments" \
  -H "Authorization: Bearer $GRAPH_TOKEN" \
  -H "Accept: application/json"

# Check appRoleAssignedTo on Microsoft Graph SP (verify from the other direction)
curl -s \
  "https://graph.microsoft.com/v1.0/servicePrincipals/<GRAPH_SP_ID>/appRoleAssignedTo?\$filter=principalId eq '<FUNC_PRINCIPAL>'" \
  -H "Authorization: Bearer $GRAPH_TOKEN" \
  -H "Accept: application/json"
```

---

## 16. Entra / Identity Calls (used during setup)

```bash
# Get managed identity principal and client IDs
az functionapp identity show \
  --name func-copilot-inventory \
  --resource-group rg-copilot-inventory \
  --query '{principalId:principalId, clientId:clientId}' \
  -o json

# Get appId from principalId (needed for Power Platform registration)
az ad sp show \
  --id $FUNC_PRINCIPAL \
  --query '{displayName:displayName, appId:appId}' \
  -o json

# Get Microsoft Graph service principal ID
az ad sp show \
  --id 00000003-0000-0000-c000-000000000000 \
  --query id -o tsv

# Look up Graph app role IDs by permission name
az ad sp show \
  --id 00000003-0000-0000-c000-000000000000 \
  --query "appRoles[?value=='GroupMember.Read.All'].id" -o tsv

az ad sp show \
  --id 00000003-0000-0000-c000-000000000000 \
  --query "appRoles[?value=='User.Read.All'].id" -o tsv

# Grant Graph app role to managed identity service principal
az rest --method POST \
  --uri "https://graph.microsoft.com/v1.0/servicePrincipals/${GRAPH_SP_ID}/appRoleAssignedTo" \
  --body "{
    \"principalId\": \"${FUNC_PRINCIPAL}\",
    \"resourceId\": \"${GRAPH_SP_ID}\",
    \"appRoleId\": \"${APP_ROLE_ID}\"
  }"
```

---

## 17. Connector Configuration

Configure the OpenICF connector provisioner. The inventory URL must be a SAS URL — the connector fetches it without an Authorization header.

```json
{
  "tenantId": "<TENANT_GUID>",
  "environmentUrl": "https://org51451987.crm.dynamics.com",
  "clientId": "<CONNECTOR_CLIENT_ID>",
  "clientSecret": "<CONNECTOR_CLIENT_SECRET>",
  "toolsInventoryUrl": "https://stcopilotinventory.blob.core.windows.net/tools-inventory/copilot-studio/inventory.json?<SAS_PARAMS>",
  "identityBindingScanEnabled": true,
  "includeUnpublishedAgents": false,
  "httpTimeoutSeconds": 30,
  "logPayloads": false
}
```

`identityBindingScanEnabled` must be `true` for `agentIdentityBinding` objects to be produced. When `false`, the inventory blob is never fetched regardless of `toolsInventoryUrl` being set.

---

## 18. Schedule

The function runs hourly (`0 0 * * * *` in `function.json`). To change:

```bash
az functionapp config appsettings set \
  --name func-copilot-inventory \
  --resource-group rg-copilot-inventory \
  --settings AzureWebJobs.copilot_inventory_job.Disabled=false

# Or edit function.json schedule field and redeploy
```

Standard cron expressions for reference:

| Schedule | Cron |
|---|---|
| Every hour | `0 0 * * * *` |
| Every 6 hours | `0 0 */6 * * *` |
| Daily at 2am UTC | `0 0 2 * * *` |
