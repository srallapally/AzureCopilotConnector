// src/main/java/org/forgerock/openicf/connectors/m365copilot/operations/M365CopilotCrudService.java
package org.forgerock.openicf.connectors.m365copilot.operations;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.forgerock.openicf.connectors.m365copilot.M365CopilotConfiguration;
import org.forgerock.openicf.connectors.m365copilot.client.CopilotElementDescriptor;
import org.forgerock.openicf.connectors.m365copilot.client.CopilotIdentityBindingDescriptor;
import org.forgerock.openicf.connectors.m365copilot.client.CopilotPackageDescriptor;
import org.forgerock.openicf.connectors.m365copilot.client.M365CopilotClient;
import org.forgerock.openicf.connectors.m365copilot.utils.M365CopilotConstants;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.ResultsHandler;

public class M365CopilotCrudService {

    private static final Log LOG = Log.getLog(M365CopilotCrudService.class);

    private final M365CopilotClient client;
    private final M365CopilotConfiguration cfg;

    public M365CopilotCrudService(M365CopilotClient client, M365CopilotConfiguration cfg) {
        this.client = client;
        this.cfg = cfg;
    }

    public void searchPackages(String query, ResultsHandler handler, OperationOptions options) {
        if (query != null && !query.isEmpty()) {
            searchPackageByUid(query, handler);
        } else {
            searchAllPackages(handler);
        }
    }

    public void searchTools(String query, ResultsHandler handler, OperationOptions options) {
        if (!hasInventorySource()) {
            LOG.ok("No inventory source configured; returning empty results for agentTool");
            return;
        }

        if (query != null && !query.isEmpty()) {
            searchToolByUid(query, handler);
        } else {
            searchAllTools(handler);
        }
    }

    public void searchIdentityBindings(String query, ResultsHandler handler, OperationOptions options) {
        if (!hasInventorySource()) {
            LOG.ok("No inventory source configured; returning empty results for agentIdentityBinding");
            return;
        }

        if (query != null && !query.isEmpty()) {
            searchIdentityBindingByUid(query, handler);
        } else {
            searchAllIdentityBindings(handler);
        }
    }

    private void searchPackageByUid(String uid, ResultsHandler handler) {
        LOG.ok("GET package by UID: {0}", uid);
        JsonNode detailNode = client.graphGetSingle(M365CopilotConstants.PACKAGES_PATH + "/" + uid);
        CopilotPackageDescriptor descriptor = CopilotPackageDescriptor.fromListJson(detailNode);
        descriptor.enrichFromDetail(detailNode);

        enrichFromInventoryIfEnabled(descriptor, uid);

        handler.handle(descriptor.toConnectorObject());
    }

    private void searchAllPackages(ResultsHandler handler) {
        String path = M365CopilotConstants.PACKAGES_PATH + buildFilterQueryString();
        LOG.ok("Searching all packages: {0}", path);

        List<JsonNode> allItems = client.graphGetAllPages(path);
        LOG.ok("Retrieved {0} packages", allItems.size());

        for (JsonNode item : allItems) {
            CopilotPackageDescriptor descriptor = CopilotPackageDescriptor.fromListJson(item);

            if (cfg.isFetchPackageDetails()) {
                try {
                    JsonNode detail = client.graphGetSingle(
                            M365CopilotConstants.PACKAGES_PATH + "/" + descriptor.getId());
                    descriptor.enrichFromDetail(detail);
                    enrichFromInventoryIfEnabled(descriptor, descriptor.getId());
                } catch (Exception e) {
                    LOG.warn("Failed to fetch details for package {0}: {1}",
                            descriptor.getId(), e.getMessage());
                }
            }

            if (!handler.handle(descriptor.toConnectorObject())) {
                LOG.ok("Handler returned false, stopping iteration");
                break;
            }
        }
    }

    private void searchToolByUid(String toolId, ResultsHandler handler) {
        LOG.ok("Searching for tool by UID: {0}", toolId);
        List<JsonNode> packages = client.graphGetAllPages(
                M365CopilotConstants.PACKAGES_PATH + buildFilterQueryString());

        for (JsonNode pkgNode : packages) {
            String packageId = pkgNode.has("id") ? pkgNode.get("id").asText() : null;
            if (packageId == null) continue;

            JsonNode inventory = client.fetchInventoryJson(packageId);
            if (inventory == null) continue;

            List<CopilotElementDescriptor> tools = CopilotElementDescriptor.fromInventoryJson(inventory, packageId);
            for (CopilotElementDescriptor tool : tools) {
                if (toolId.equals(tool.getId())) {
                    handler.handle(tool.toConnectorObject());
                    return;
                }
            }
        }

        throw new UnknownUidException("Tool not found: " + toolId);
    }

    private void searchAllTools(ResultsHandler handler) {
        LOG.ok("Searching all tools");
        List<JsonNode> packages = client.graphGetAllPages(
                M365CopilotConstants.PACKAGES_PATH + buildFilterQueryString());

        for (JsonNode pkgNode : packages) {
            String packageId = pkgNode.has("id") ? pkgNode.get("id").asText() : null;
            if (packageId == null) continue;

            JsonNode inventory = client.fetchInventoryJson(packageId);
            if (inventory == null) continue;

            List<CopilotElementDescriptor> tools = CopilotElementDescriptor.fromInventoryJson(inventory, packageId);
            for (CopilotElementDescriptor tool : tools) {
                if (!handler.handle(tool.toConnectorObject())) {
                    return;
                }
            }
        }
    }

    private void searchIdentityBindingByUid(String compositeUid, ResultsHandler handler) {
        LOG.ok("Searching for identity binding by UID: {0}", compositeUid);
        int separatorIdx = compositeUid.indexOf(':');
        if (separatorIdx <= 0) {
            throw new ConnectorException("Invalid identity binding UID format (expected packageId:resourceId): "
                    + compositeUid);
        }

        String packageId = compositeUid.substring(0, separatorIdx);
        String resourceId = compositeUid.substring(separatorIdx + 1);

        JsonNode inventory = client.fetchInventoryJson(packageId);
        if (inventory == null) {
            throw new UnknownUidException("No inventory found for package: " + packageId);
        }

        List<CopilotIdentityBindingDescriptor> bindings =
                CopilotIdentityBindingDescriptor.fromInventoryJson(inventory, packageId);
        for (CopilotIdentityBindingDescriptor binding : bindings) {
            if (resourceId.equals(binding.getResourceId())) {
                handler.handle(binding.toConnectorObject());
                return;
            }
        }

        throw new UnknownUidException("Identity binding not found: " + compositeUid);
    }

    private void searchAllIdentityBindings(ResultsHandler handler) {
        LOG.ok("Searching all identity bindings");
        List<JsonNode> packages = client.graphGetAllPages(
                M365CopilotConstants.PACKAGES_PATH + buildFilterQueryString());

        for (JsonNode pkgNode : packages) {
            String packageId = pkgNode.has("id") ? pkgNode.get("id").asText() : null;
            if (packageId == null) continue;

            JsonNode inventory = client.fetchInventoryJson(packageId);
            if (inventory == null) continue;

            List<CopilotIdentityBindingDescriptor> bindings =
                    CopilotIdentityBindingDescriptor.fromInventoryJson(inventory, packageId);
            for (CopilotIdentityBindingDescriptor binding : bindings) {
                if (!handler.handle(binding.toConnectorObject())) {
                    return;
                }
            }
        }
    }

    private void enrichFromInventoryIfEnabled(CopilotPackageDescriptor descriptor, String packageId) {
        if (cfg.isEntraAgentIdLookupEnabled() || hasInventorySource()) {
            JsonNode inventory = client.fetchInventoryJson(packageId);
            if (inventory != null) {
                descriptor.enrichFromInventory(inventory);
            }
        }
    }

    private boolean hasInventorySource() {
        return (cfg.getToolsInventoryUrl() != null && !cfg.getToolsInventoryUrl().isEmpty())
                || (cfg.getToolsInventoryFilePath() != null && !cfg.getToolsInventoryFilePath().isEmpty());
    }

    private String buildFilterQueryString() {
        if (cfg.getPackageTypeFilter() != null && !cfg.getPackageTypeFilter().isEmpty()) {
            return "?$filter=type eq '" + cfg.getPackageTypeFilter() + "'";
        }
        return "";
    }
}
