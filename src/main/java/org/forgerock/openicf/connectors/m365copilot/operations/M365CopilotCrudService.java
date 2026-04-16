// src/main/java/org/forgerock/openicf/connectors/m365copilot/operations/M365CopilotCrudService.java
package org.forgerock.openicf.connectors.m365copilot.operations;

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.forgerock.openicf.connectors.m365copilot.M365CopilotConfiguration;
import org.forgerock.openicf.connectors.m365copilot.client.BotComponentDescriptor;
import org.forgerock.openicf.connectors.m365copilot.client.BotDescriptor;
import org.forgerock.openicf.connectors.m365copilot.client.CopilotIdentityBindingDescriptor;
import org.forgerock.openicf.connectors.m365copilot.client.M365CopilotClient;
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

    // --- __ACCOUNT__ ---

    public void searchAgents(String query, ResultsHandler handler, OperationOptions options) {
        if (query != null && !query.isEmpty()) {
            searchAgentByUid(query, handler);
        } else {
            searchAllAgents(handler);
        }
    }

    private void searchAgentByUid(String botId, ResultsHandler handler) {
        LOG.ok("GET bot by UID: {0}", botId);
        JsonNode node = client.getBot(botId);
        BotDescriptor bot = BotDescriptor.fromJson(node);
        // OPENICF-5013 begin
        if (!cfg.isIncludeUnpublishedAgents() && !bot.isPublished()) {
            throw new UnknownUidException(
                    "Agent is unpublished and includeUnpublishedAgents=false: " + botId);
        }
        // OPENICF-5013 end
        List<BotComponentDescriptor> components = client.listAllBotComponents().stream()
                .filter(c -> botId.equals(c.getParentBotId()))
                .collect(Collectors.toList());
        handler.handle(bot.toConnectorObject(components));
    }

    private void searchAllAgents(ResultsHandler handler) {
        LOG.ok("Searching all agents");
        List<JsonNode> botNodes = client.listAllBots();
        List<BotComponentDescriptor> allComponents = client.listAllBotComponents();
        LOG.ok("Retrieved {0} bots, {1} botcomponents", botNodes.size(), allComponents.size());

        for (JsonNode node : botNodes) {
            BotDescriptor bot = BotDescriptor.fromJson(node);
            // OPENICF-5013 begin
            if (!cfg.isIncludeUnpublishedAgents() && !bot.isPublished()) {
                continue;
            }
            // OPENICF-5013 end
            if (!handler.handle(bot.toConnectorObject(allComponents))) {
                LOG.ok("Handler returned false, stopping iteration");
                return;
            }
        }
    }

    // --- agentTool ---

    public void searchTools(String query, ResultsHandler handler, OperationOptions options) {
        if (query != null && !query.isEmpty()) {
            searchToolByUid(query, handler);
        } else {
            searchAllTools(handler);
        }
    }

    private void searchToolByUid(String botComponentId, ResultsHandler handler) {
        LOG.ok("GET botcomponent (tool) by UID: {0}", botComponentId);
        JsonNode node = client.getBotComponent(botComponentId);
        BotComponentDescriptor comp = BotComponentDescriptor.fromJson(node);
        BotComponentDescriptor.ComponentKind kind = comp.getComponentKind();
        if (kind != BotComponentDescriptor.ComponentKind.TOOL_CONNECTOR
                && kind != BotComponentDescriptor.ComponentKind.TOOL_MCP) {
            throw new UnknownUidException("Botcomponent is not a tool: " + botComponentId);
        }
        handler.handle(comp.toAgentToolConnectorObject());
    }

    private void searchAllTools(ResultsHandler handler) {
        LOG.ok("Searching all tools");
        for (BotComponentDescriptor comp : client.listAllBotComponents()) {
            BotComponentDescriptor.ComponentKind kind = comp.getComponentKind();
            if (kind != BotComponentDescriptor.ComponentKind.TOOL_CONNECTOR
                    && kind != BotComponentDescriptor.ComponentKind.TOOL_MCP) {
                continue;
            }
            if (!handler.handle(comp.toAgentToolConnectorObject())) {
                return;
            }
        }
    }

    // --- agentKnowledgeBase ---

    public void searchKnowledgeBases(String query, ResultsHandler handler, OperationOptions options) {
        if (query != null && !query.isEmpty()) {
            searchKnowledgeBaseByUid(query, handler);
        } else {
            searchAllKnowledgeBases(handler);
        }
    }

    private void searchKnowledgeBaseByUid(String botComponentId, ResultsHandler handler) {
        LOG.ok("GET botcomponent (knowledge base) by UID: {0}", botComponentId);
        JsonNode node = client.getBotComponent(botComponentId);
        BotComponentDescriptor comp = BotComponentDescriptor.fromJson(node);
        if (comp.getComponentKind() != BotComponentDescriptor.ComponentKind.KNOWLEDGE_SOURCE) {
            throw new UnknownUidException("Botcomponent is not a knowledge base: " + botComponentId);
        }
        handler.handle(comp.toKnowledgeBaseConnectorObject());
    }

    private void searchAllKnowledgeBases(ResultsHandler handler) {
        LOG.ok("Searching all knowledge bases");
        for (BotComponentDescriptor comp : client.listAllBotComponents()) {
            if (comp.getComponentKind() != BotComponentDescriptor.ComponentKind.KNOWLEDGE_SOURCE) {
                continue;
            }
            if (!handler.handle(comp.toKnowledgeBaseConnectorObject())) {
                return;
            }
        }
    }

    // --- agentIdentityBinding ---

    public void searchIdentityBindings(String query, ResultsHandler handler, OperationOptions options) {
        // OPENICF-5005 begin: gate on identityBindingScanEnabled; OPENICF-5002: WARN when enabled but inventory missing
        if (!cfg.isIdentityBindingScanEnabled()) {
            LOG.ok("identityBindingScanEnabled=false; skipping agentIdentityBinding search");
            if (query != null && !query.isEmpty()) {
                throw new UnknownUidException(
                        "Identity binding scan is disabled; agentIdentityBinding objects are not available");
            }
            return;
        }
        if (!hasInventorySource()) {
            LOG.warn("identityBindingScanEnabled=true but no inventory source configured "
                    + "(toolsInventoryUrl or toolsInventoryFilePath); returning no agentIdentityBinding data");
            if (query != null && !query.isEmpty()) {
                throw new UnknownUidException("No inventory source configured");
            }
            return;
        }
        // OPENICF-5002 end
        // OPENICF-5005 end
        if (query != null && !query.isEmpty()) {
            searchIdentityBindingByUid(query, handler);
        } else {
            searchAllIdentityBindings(handler);
        }
    }

    private void searchIdentityBindingByUid(String compositeUid, ResultsHandler handler) {
        LOG.ok("Searching for identity binding by UID: {0}", compositeUid);
        int sep = compositeUid.indexOf(':');
        if (sep <= 0) {
            throw new ConnectorException(
                    "Invalid identity binding UID format (expected agentId:groupId): " + compositeUid);
        }
        String agentId = compositeUid.substring(0, sep);
        String groupId = compositeUid.substring(sep + 1);

        JsonNode inventory = client.fetchInventoryJson();
        if (inventory == null) {
            throw new UnknownUidException("No inventory available");
        }

        for (CopilotIdentityBindingDescriptor binding : CopilotIdentityBindingDescriptor.fromInventoryJson(inventory)) {
            if (agentId.equals(binding.getAgentId()) && groupId.equals(binding.getGroupId())) {
                handler.handle(binding.toConnectorObject());
                return;
            }
        }

        throw new UnknownUidException("Identity binding not found: " + compositeUid);
    }

    private void searchAllIdentityBindings(ResultsHandler handler) {
        LOG.ok("Searching all identity bindings");
        JsonNode inventory = client.fetchInventoryJson();
        if (inventory == null) {
            LOG.ok("Inventory is null, returning empty results");
            return;
        }
        for (CopilotIdentityBindingDescriptor binding : CopilotIdentityBindingDescriptor.fromInventoryJson(inventory)) {
            if (!handler.handle(binding.toConnectorObject())) {
                return;
            }
        }
    }

    private boolean hasInventorySource() {
        return (cfg.getToolsInventoryUrl() != null && !cfg.getToolsInventoryUrl().isEmpty())
                || (cfg.getToolsInventoryFilePath() != null && !cfg.getToolsInventoryFilePath().isEmpty());
    }
}