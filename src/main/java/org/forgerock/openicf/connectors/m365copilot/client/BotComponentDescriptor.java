// src/main/java/org/forgerock/openicf/connectors/m365copilot/client/BotComponentDescriptor.java
package org.forgerock.openicf.connectors.m365copilot.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;
// OPENICF-5011 begin
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
// OPENICF-5011 end

import static org.forgerock.openicf.connectors.m365copilot.utils.M365CopilotConstants.*;

public class BotComponentDescriptor {

    // OPENICF-5011 begin
    private static final Log LOG = Log.getLog(BotComponentDescriptor.class);
    // OPENICF-5011 end

    public enum ComponentKind { TOOL_CONNECTOR, TOOL_MCP, CONNECTED_AGENT, KNOWLEDGE_SOURCE, TOPIC, UNKNOWN }

    private final String botComponentId;
    private final String name;
    private final int componentType;
    private final String parentBotId;
    private final String schemaName;
    private final String description;
    private final String createdOn;
    private final String modifiedOn;

    // Parsed from YAML data field (type 9 only)
    private final String dataKind;
    private final String actionKind;
    private final String connectionReference;
    private final String operationId;
    private final String modelDescription;
    // OPENICF-5010 begin
    private final String targetBotSchemaName;
    // OPENICF-5010 end

    private BotComponentDescriptor(String botComponentId, String name, int componentType,
                                   String parentBotId, String schemaName, String description,
                                   String createdOn, String modifiedOn,
                                   String dataKind, String actionKind,
                                   String connectionReference, String operationId,
                                   String modelDescription,
                                   // OPENICF-5010 begin
                                   String targetBotSchemaName
                                   // OPENICF-5010 end
    ) {
        this.botComponentId = botComponentId;
        this.name = name;
        this.componentType = componentType;
        this.parentBotId = parentBotId;
        this.schemaName = schemaName;
        this.description = description;
        this.createdOn = createdOn;
        this.modifiedOn = modifiedOn;
        this.dataKind = dataKind;
        this.actionKind = actionKind;
        this.connectionReference = connectionReference;
        this.operationId = operationId;
        this.modelDescription = modelDescription;
        // OPENICF-5010 begin
        this.targetBotSchemaName = targetBotSchemaName;
        // OPENICF-5010 end
    }

    public static BotComponentDescriptor fromJson(JsonNode node) {
        // OPENICF-5014 begin
        LOG.ok("BotComponentDescriptor.fromJson: {0}", node);
        // OPENICF-5014 end
        String id = text(node, "botcomponentid");
        String name = text(node, "name");
        int componentType = node.has("componenttype") ? node.get("componenttype").asInt(-1) : -1;
        String parentBotId = text(node, "_parentbotid_value");
        String schemaName = text(node, "schemaname");
        String description = text(node, "description");
        String createdOn = text(node, "createdon");
        String modifiedOn = text(node, "modifiedon");

        String dataKind = null;
        String actionKind = null;
        String connectionReference = null;
        String operationId = null;
        String modelDescription = null;
        // OPENICF-5010 begin
        String targetBotSchemaName = null;
        // OPENICF-5010 end

        if (componentType == COMPONENT_TYPE_TOPIC_V2) {
            String data = text(node, "data");
            if (data != null) {
                // OPENICF-5011 begin
                Map<String, Object> yaml = parseYamlData(data, id);
                dataKind            = stringVal(yaml, "kind");
                actionKind          = stringValNested(yaml, "action", "kind");
                connectionReference = stringVal(yaml, "connectionReference");
                operationId         = resolveOperationId(yaml);
                modelDescription    = stringVal(yaml, "modelDescription");
                // OPENICF-5011 end
                // OPENICF-5010 begin
                targetBotSchemaName = stringValNested(yaml, "action", "botSchemaName");
                // OPENICF-5010 end
            }
        }

        return new BotComponentDescriptor(id, name, componentType, parentBotId, schemaName,
                description, createdOn, modifiedOn, dataKind, actionKind,
                connectionReference, operationId, modelDescription,
                // OPENICF-5010 begin
                targetBotSchemaName
                // OPENICF-5010 end
        );
    }

    public ComponentKind getComponentKind() {
        if (componentType == COMPONENT_TYPE_KNOWLEDGE_SOURCE) {
            return ComponentKind.KNOWLEDGE_SOURCE;
        }
        if (componentType == COMPONENT_TYPE_TOPIC_V2) {
            if (KIND_ADAPTIVE_DIALOG.equals(dataKind)) {
                return ComponentKind.TOPIC;
            }
            if (KIND_TASK_DIALOG.equals(dataKind)) {
                if (ACTION_KIND_CONNECTOR.equals(actionKind)) return ComponentKind.TOOL_CONNECTOR;
                if (ACTION_KIND_MCP.equals(actionKind))       return ComponentKind.TOOL_MCP;
                if (ACTION_KIND_CONNECTED_AGENT.equals(actionKind)) return ComponentKind.CONNECTED_AGENT;
            }
        }
        return ComponentKind.UNKNOWN;
    }

    public ConnectorObject toAgentToolConnectorObject() {
        ConnectorObjectBuilder cob = new ConnectorObjectBuilder();
        cob.setObjectClass(new ObjectClass(OC_AGENT_TOOL));
        cob.setUid(botComponentId);
        cob.setName(name != null ? name : botComponentId);

        cob.addAttribute(ATTR_PLATFORM, PLATFORM);
        cob.addAttribute(ATTR_AGENT_ID, parentBotId);

        ComponentKind kind = getComponentKind();
        String toolType = (kind == ComponentKind.TOOL_MCP) ? TOOL_TYPE_MCP : TOOL_TYPE_CONNECTOR;
        cob.addAttribute(ATTR_TOOL_TYPE, toolType);

        if (connectionReference != null) cob.addAttribute(ATTR_CONNECTION_REFERENCE, connectionReference);
        if (operationId != null)         cob.addAttribute(ATTR_OPERATION_ID, operationId);
        if (modelDescription != null)    cob.addAttribute(ATTR_DESCRIPTION, modelDescription);
        if (schemaName != null)          cob.addAttribute(ATTR_SCHEMA_NAME, schemaName);
        if (createdOn != null)           cob.addAttribute(ATTR_CREATED_ON, createdOn);
        if (modifiedOn != null)          cob.addAttribute(ATTR_MODIFIED_ON, modifiedOn);

        return cob.build();
    }

    public ConnectorObject toKnowledgeBaseConnectorObject() {
        ConnectorObjectBuilder cob = new ConnectorObjectBuilder();
        cob.setObjectClass(new ObjectClass(OC_AGENT_KNOWLEDGE_BASE));
        cob.setUid(botComponentId);
        cob.setName(name != null ? name : botComponentId);

        cob.addAttribute(ATTR_PLATFORM, PLATFORM);
        cob.addAttribute(ATTR_AGENT_ID, parentBotId);
        if (schemaName != null)  cob.addAttribute(ATTR_SCHEMA_NAME, schemaName);
        if (description != null) cob.addAttribute(ATTR_DESCRIPTION, description);
        if (createdOn != null)   cob.addAttribute(ATTR_CREATED_ON, createdOn);
        if (modifiedOn != null)  cob.addAttribute(ATTR_MODIFIED_ON, modifiedOn);

        return cob.build();
    }

    // --- Accessors used by BotDescriptor ---

    public String getBotComponentId()      { return botComponentId; }
    public String getParentBotId()         { return parentBotId; }
    public String getSchemaName()          { return schemaName; }
    // OPENICF-5010 begin
    public String getTargetBotSchemaName() { return targetBotSchemaName; }
    // OPENICF-5010 end

    // --- OPENICF-5011 begin: SnakeYAML-based parsing helpers ---

    /**
     * Parses the YAML data field using SnakeYAML's SafeConstructor.
     * Returns the top-level map, or an empty map on any parse failure.
     * SafeConstructor prevents arbitrary Java object instantiation.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYamlData(String data, String botComponentId) {
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object parsed = yaml.load(data);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
            LOG.warn("YAML data for botcomponent {0} did not parse to a map (got {1}); treating as unclassified",
                    botComponentId, parsed == null ? "null" : parsed.getClass().getSimpleName());
            return Collections.emptyMap();
        } catch (Exception e) {
            // OPENICF-5014 begin
            // SnakeYAML failed (e.g. @ character or unquoted colon in embedded content).
            // Fall back to line-scanner for kind and action.kind — enough for classification.
            String kind = extractYamlValue(data, "kind:");
            String actionKind = extractActionKind(data);
            if (kind != null) {
                Map<String, Object> synthetic = new HashMap<>();
                synthetic.put("kind", kind);
                if (actionKind != null) {
                    Map<String, Object> action = new HashMap<>();
                    action.put("kind", actionKind);
                    synthetic.put("action", action);
                }
                return synthetic;
            }
            LOG.warn("Failed to parse YAML data for botcomponent {0}: {1}; treating as unclassified",
                    botComponentId, e.getMessage());
            return Collections.emptyMap();
            // OPENICF-5014 end
        }
    }

    /** Returns a top-level string value from the parsed YAML map, or null if absent or not a string. */
    private static String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return (val instanceof String) ? (String) val : null;
    }

    /**
     * Returns a string value one level deep (parent → child key), or null if either level is
     * absent, not a map, or the leaf is not a string.
     */
    @SuppressWarnings("unchecked")
    private static String stringValNested(Map<String, Object> map, String parentKey, String childKey) {
        Object parent = map.get(parentKey);
        if (!(parent instanceof Map)) {
            return null;
        }
        Object val = ((Map<String, Object>) parent).get(childKey);
        return (val instanceof String) ? (String) val : null;
    }

    /**
     * Resolves operationId: tries top-level "operationId" first, then "operationDetails.operationId".
     */
    @SuppressWarnings("unchecked")
    private static String resolveOperationId(Map<String, Object> map) {
        String direct = stringVal(map, "operationId");
        if (direct != null) {
            return direct;
        }
        Object details = map.get("operationDetails");
        if (details instanceof Map) {
            Object val = ((Map<String, Object>) details).get("operationId");
            return (val instanceof String) ? (String) val : null;
        }
        return null;
    }

    // --- OPENICF-5011 end ---

    // OPENICF-5014 begin
    /** Extracts the value of a top-level YAML scalar key by scanning lines. */
    private static String extractYamlValue(String yaml, String key) {
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key)) {
                String value = trimmed.substring(key.length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * Extracts action.kind by scanning for an "action:" block and reading the
     * first "kind:" line within it.
     */
    private static String extractActionKind(String yaml) {
        String[] lines = yaml.split("\n");
        boolean inAction = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals("action:") || trimmed.startsWith("action: ")) {
                inAction = true;
                continue;
            }
            if (inAction) {
                if (trimmed.startsWith("kind:")) {
                    return trimmed.substring("kind:".length()).trim();
                }
                if (!line.startsWith(" ") && !line.startsWith("\t") && !trimmed.isEmpty()) {
                    break;
                }
            }
        }
        return null;
    }
    // OPENICF-5014 end

    private static String text(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return (f != null && !f.isNull()) ? f.asText() : null;
    }
}