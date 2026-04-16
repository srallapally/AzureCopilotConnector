// src/main/java/org/forgerock/openicf/connectors/m365copilot/client/BotComponentDescriptor.java
package org.forgerock.openicf.connectors.m365copilot.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.forgerock.openicf.connectors.m365copilot.utils.M365CopilotConstants;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;

import static org.forgerock.openicf.connectors.m365copilot.utils.M365CopilotConstants.*;

public class BotComponentDescriptor {

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

    private BotComponentDescriptor(String botComponentId, String name, int componentType,
                                   String parentBotId, String schemaName, String description,
                                   String createdOn, String modifiedOn,
                                   String dataKind, String actionKind,
                                   String connectionReference, String operationId,
                                   String modelDescription) {
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
    }

    public static BotComponentDescriptor fromJson(JsonNode node) {
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

        if (componentType == COMPONENT_TYPE_TOPIC_V2) {
            String data = text(node, "data");
            if (data != null) {
                dataKind = extractYamlValue(data, "kind:");
                actionKind = extractActionKind(data);
                connectionReference = extractYamlValue(data, "connectionReference:");
                operationId = extractOperationId(data);
                modelDescription = extractYamlValue(data, "modelDescription:");
            }
        }

        return new BotComponentDescriptor(id, name, componentType, parentBotId, schemaName,
                description, createdOn, modifiedOn, dataKind, actionKind,
                connectionReference, operationId, modelDescription);
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

    public String getBotComponentId() { return botComponentId; }
    public String getParentBotId()    { return parentBotId; }
    public String getSchemaName()     { return schemaName; }

    // --- YAML parsing helpers ---

    /**
     * Extracts the value of a top-level YAML scalar key by scanning lines.
     * Returns the trimmed value after the first occurrence of the key, or null.
     */
    static String extractYamlValue(String yaml, String key) {
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
     * Extracts action.kind from the YAML data field.
     * Looks for an "action:" block and then the first "kind:" line within it.
     */
    static String extractActionKind(String yaml) {
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
                // Exit action block if we hit a new top-level key (no leading spaces)
                if (!line.startsWith(" ") && !line.startsWith("\t") && !trimmed.isEmpty()) {
                    break;
                }
            }
        }
        return null;
    }

    /**
     * Extracts operationId — tries operationId: first, then operationDetails.operationId:.
     */
    static String extractOperationId(String yaml) {
        String direct = extractYamlValue(yaml, "operationId:");
        if (direct != null) return direct;
        return extractYamlValue(yaml, "operationDetails.operationId:");
    }

    private static String text(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return (f != null && !f.isNull()) ? f.asText() : null;
    }
}