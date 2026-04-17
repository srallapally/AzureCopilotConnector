// src/main/java/org/forgerock/openicf/connectors/m365copilot/operations/M365CopilotCrudService.java
package org.forgerock.openicf.connectors.m365copilot.operations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
// OPENICF-5008 begin
import org.identityconnectors.framework.common.objects.SearchResult;
import org.identityconnectors.framework.spi.SearchResultsHandler;
// OPENICF-5008 end

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
            searchAllAgents(handler, options);
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
        // OPENICF-5010 begin
        Map<String, String> botSchemaNameIndex = BotDescriptor.buildSchemaNameIndex(client.listAllBots());
        // OPENICF-5010 end
        // OPENICF-INV-001 begin
        JsonNode inventory = client.fetchInventoryJson();
        Map<String, JsonNode> agentInventoryIndex = BotDescriptor.buildAgentInventoryIndex(inventory);
        JsonNode agentInventory = agentInventoryIndex.get(botId);
        handler.handle(bot.toConnectorObject(components, botSchemaNameIndex, agentInventory));
        // OPENICF-INV-001 end
    }

    private void searchAllAgents(ResultsHandler handler, OperationOptions options) {
        LOG.ok("Searching all agents");
        List<JsonNode> botNodes = client.listAllBots();
        List<BotComponentDescriptor> allComponents = client.listAllBotComponents();
        // OPENICF-5010 begin
        Map<String, String> botSchemaNameIndex = BotDescriptor.buildSchemaNameIndex(botNodes);
        // OPENICF-5010 end
        // OPENICF-INV-001 begin
        JsonNode inventory = client.fetchInventoryJson();
        Map<String, JsonNode> agentInventoryIndex = BotDescriptor.buildAgentInventoryIndex(inventory);
        // OPENICF-INV-001 end

        // OPENICF-5008 begin: pre-filter to a candidate list so the page window is applied
        // over the set IDM will actually see, not the raw Dataverse set
        List<JsonNode> candidates = new ArrayList<>(botNodes.size());
        for (JsonNode node : botNodes) {
            if (!cfg.isIncludeUnpublishedAgents() && !BotDescriptor.fromJson(node).isPublished()) {
                continue;
            }
            candidates.add(node);
        }
        LOG.ok("Retrieved {0} bots ({1} published candidates), {2} botcomponents",
                botNodes.size(), candidates.size(), allComponents.size());

        int pageSize = pageSize(options);
        int offset   = pageOffset(options);

        List<JsonNode> page = pageSize > 0
                ? candidates.subList(offset, Math.min(offset + pageSize, candidates.size()))
                : candidates;

        for (JsonNode node : page) {
            BotDescriptor bot = BotDescriptor.fromJson(node);
            // OPENICF-INV-001 begin
            JsonNode agentInventory = agentInventoryIndex.get(bot.getBotId());
            if (!handler.handle(bot.toConnectorObject(allComponents, botSchemaNameIndex, agentInventory))) {
                // OPENICF-INV-001 end
                LOG.ok("Handler returned false, stopping iteration");
                return;
            }
        }

        if (pageSize > 0) {
            emitSearchResult(handler, offset, pageSize, candidates.size());
        }
        // OPENICF-5008 end
    }

    // --- agentTool ---

    public void searchTools(String query, ResultsHandler handler, OperationOptions options) {
        if (query != null && !query.isEmpty()) {
            searchToolByUid(query, handler);
        } else {
            searchAllTools(handler, options);
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

    private void searchAllTools(ResultsHandler handler, OperationOptions options) {
        LOG.ok("Searching all tools");
        // OPENICF-5008 begin
        List<BotComponentDescriptor> candidates = new ArrayList<>();
        for (BotComponentDescriptor comp : client.listAllBotComponents()) {
            BotComponentDescriptor.ComponentKind kind = comp.getComponentKind();
            if (kind == BotComponentDescriptor.ComponentKind.TOOL_CONNECTOR
                    || kind == BotComponentDescriptor.ComponentKind.TOOL_MCP) {
                candidates.add(comp);
            }
        }

        int pageSize = pageSize(options);
        int offset   = pageOffset(options);

        List<BotComponentDescriptor> page = pageSize > 0
                ? candidates.subList(offset, Math.min(offset + pageSize, candidates.size()))
                : candidates;

        for (BotComponentDescriptor comp : page) {
            if (!handler.handle(comp.toAgentToolConnectorObject())) {
                return;
            }
        }

        if (pageSize > 0) {
            emitSearchResult(handler, offset, pageSize, candidates.size());
        }
        // OPENICF-5008 end
    }

    // --- agentKnowledgeBase ---

    public void searchKnowledgeBases(String query, ResultsHandler handler, OperationOptions options) {
        if (query != null && !query.isEmpty()) {
            searchKnowledgeBaseByUid(query, handler);
        } else {
            searchAllKnowledgeBases(handler, options);
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

    private void searchAllKnowledgeBases(ResultsHandler handler, OperationOptions options) {
        LOG.ok("Searching all knowledge bases");
        // OPENICF-5008 begin
        List<BotComponentDescriptor> candidates = new ArrayList<>();
        for (BotComponentDescriptor comp : client.listAllBotComponents()) {
            if (comp.getComponentKind() == BotComponentDescriptor.ComponentKind.KNOWLEDGE_SOURCE) {
                candidates.add(comp);
            }
        }

        int pageSize = pageSize(options);
        int offset   = pageOffset(options);

        List<BotComponentDescriptor> page = pageSize > 0
                ? candidates.subList(offset, Math.min(offset + pageSize, candidates.size()))
                : candidates;

        for (BotComponentDescriptor comp : page) {
            if (!handler.handle(comp.toKnowledgeBaseConnectorObject())) {
                return;
            }
        }

        if (pageSize > 0) {
            emitSearchResult(handler, offset, pageSize, candidates.size());
        }
        // OPENICF-5008 end
    }

    // --- agentIdentityBinding ---

    public void searchIdentityBindings(String query, ResultsHandler handler, OperationOptions options) {
        // OPENICF-5005 begin
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
        // OPENICF-5005 end
        if (query != null && !query.isEmpty()) {
            searchIdentityBindingByUid(query, handler);
        } else {
            searchAllIdentityBindings(handler, options);
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

    private void searchAllIdentityBindings(ResultsHandler handler, OperationOptions options) {
        LOG.ok("Searching all identity bindings");
        JsonNode inventory = client.fetchInventoryJson();
        if (inventory == null) {
            LOG.ok("Inventory is null, returning empty results");
            return;
        }
        // OPENICF-5008 begin
        List<CopilotIdentityBindingDescriptor> candidates =
                CopilotIdentityBindingDescriptor.fromInventoryJson(inventory);

        int pageSize = pageSize(options);
        int offset   = pageOffset(options);

        List<CopilotIdentityBindingDescriptor> page = pageSize > 0
                ? candidates.subList(offset, Math.min(offset + pageSize, candidates.size()))
                : candidates;

        for (CopilotIdentityBindingDescriptor binding : page) {
            if (!handler.handle(binding.toConnectorObject())) {
                return;
            }
        }

        if (pageSize > 0) {
            emitSearchResult(handler, offset, pageSize, candidates.size());
        }
        // OPENICF-5008 end
    }

    // --- OPENICF-5008 begin: paging helpers ---

    private static int pageSize(OperationOptions options) {
        if (options == null) return 0;
        Integer ps = options.getPageSize();
        return (ps != null && ps > 0) ? ps : 0;
    }

    private static int pageOffset(OperationOptions options) {
        if (options == null) return 0;
        String cookie = options.getPagedResultsCookie();
        if (cookie == null || cookie.isEmpty()) return 0;
        try {
            int offset = Integer.parseInt(cookie);
            return Math.max(0, offset);
        } catch (NumberFormatException e) {
            LOG.warn("Unparseable pagedResultsCookie ''{0}''; starting from offset 0", cookie);
            return 0;
        }
    }

    private static void emitSearchResult(ResultsHandler handler, int offset, int pageSize, int total) {
        if (!(handler instanceof SearchResultsHandler)) return;
        int nextOffset = offset + pageSize;
        String nextCookie = nextOffset < total ? String.valueOf(nextOffset) : null;
        int remaining = Math.max(0, total - nextOffset);
        ((SearchResultsHandler) handler).handleResult(new SearchResult(nextCookie, remaining));
    }

    // --- OPENICF-5008 end ---

    private boolean hasInventorySource() {
        return (cfg.getToolsInventoryUrl() != null && !cfg.getToolsInventoryUrl().isEmpty())
                || (cfg.getToolsInventoryFilePath() != null && !cfg.getToolsInventoryFilePath().isEmpty());
    }
}