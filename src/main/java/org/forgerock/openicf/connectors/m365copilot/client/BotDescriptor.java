// src/main/java/org/forgerock/openicf/connectors/m365copilot/client/BotDescriptor.java
package org.forgerock.openicf.connectors.m365copilot.client;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;

import static org.forgerock.openicf.connectors.m365copilot.utils.M365CopilotConstants.*;

public class BotDescriptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String botId;
    private final String name;
    private final Integer statecode;
    private final Integer statuscode;
    private final String accessControlPolicy;
    private final String authenticationMode;
    private final String runtimeProvider;
    private final String language;
    private final String schemaName;
    private final String publishedOn;
    private final String createdOn;
    private final String modifiedOn;
    private final String contentModeration;
    private final Boolean generativeActionsEnabled;
    private final Boolean useModelKnowledge;

    private BotDescriptor(String botId, String name, Integer statecode, Integer statuscode,
                          String accessControlPolicy, String authenticationMode,
                          String runtimeProvider, String language, String schemaName,
                          String publishedOn, String createdOn, String modifiedOn,
                          String contentModeration, Boolean generativeActionsEnabled,
                          Boolean useModelKnowledge) {
        this.botId = botId;
        this.name = name;
        this.statecode = statecode;
        this.statuscode = statuscode;
        this.accessControlPolicy = accessControlPolicy;
        this.authenticationMode = authenticationMode;
        this.runtimeProvider = runtimeProvider;
        this.language = language;
        this.schemaName = schemaName;
        this.publishedOn = publishedOn;
        this.createdOn = createdOn;
        this.modifiedOn = modifiedOn;
        this.contentModeration = contentModeration;
        this.generativeActionsEnabled = generativeActionsEnabled;
        this.useModelKnowledge = useModelKnowledge;
    }

    public static BotDescriptor fromJson(JsonNode node) {
        String botId = text(node, "botid");
        String name = text(node, "name");

        Integer statecode = null;
        Integer statuscode = null;
        if (node.has("statecode") && !node.get("statecode").isNull()) {
            statecode = node.get("statecode").asInt();
        }
        if (node.has("statuscode") && !node.get("statuscode").isNull()) {
            statuscode = node.get("statuscode").asInt();
        }

        // accesscontrolpolicy is an OptionSet integer — convert to label
        String accessControlPolicy = null;
        if (node.has("accesscontrolpolicy") && !node.get("accesscontrolpolicy").isNull()) {
            int policy = node.get("accesscontrolpolicy").asInt(-1);
            accessControlPolicy = accessControlPolicyLabel(policy);
        }

        // Parse configuration blob for AI settings
        String contentModeration = null;
        Boolean generativeActionsEnabled = null;
        Boolean useModelKnowledge = null;
        String configText = text(node, "configuration");
        if (configText != null) {
            try {
                JsonNode config = MAPPER.readTree(configText);
                JsonNode aiSettings = config.path("aISettings");
                if (!aiSettings.isMissingNode()) {
                    if (aiSettings.has("contentModeration") && !aiSettings.get("contentModeration").isNull()) {
                        contentModeration = aiSettings.get("contentModeration").asText();
                    }
                    if (aiSettings.has("useModelKnowledge") && !aiSettings.get("useModelKnowledge").isNull()) {
                        useModelKnowledge = aiSettings.get("useModelKnowledge").asBoolean();
                    }
                }
                JsonNode settings = config.path("settings");
                if (!settings.isMissingNode()) {
                    if (settings.has("GenerativeActionsEnabled") && !settings.get("GenerativeActionsEnabled").isNull()) {
                        generativeActionsEnabled = settings.get("GenerativeActionsEnabled").asBoolean();
                    }
                }
            } catch (Exception e) {
                // configuration blob unparseable — skip silently
            }
        }

        return new BotDescriptor(
                botId, name, statecode, statuscode, accessControlPolicy,
                text(node, "authenticationmode"),
                text(node, "runtimeprovider"),
                text(node, "language"),
                text(node, "schemaname"),
                text(node, "publishedon"),
                text(node, "createdon"),
                text(node, "modifiedon"),
                contentModeration, generativeActionsEnabled, useModelKnowledge
        );
    }

    /**
     * Builds the ConnectorObject, deriving relationship attributes from the provided
     * list of all BotComponentDescriptors for this bot's environment.
     */
    public ConnectorObject toConnectorObject(List<BotComponentDescriptor> allComponents) {
        ConnectorObjectBuilder cob = new ConnectorObjectBuilder();
        cob.setObjectClass(ObjectClass.ACCOUNT);
        cob.setUid(botId);
        cob.setName(name != null ? name : botId);

        cob.addAttribute(ATTR_PLATFORM, PLATFORM);
        cob.addAttribute(ATTR_AGENT_ID, botId);

        if (statecode != null)           cob.addAttribute(ATTR_STATECODE, statecode);
        if (statuscode != null)          cob.addAttribute(ATTR_STATUSCODE, statuscode);
        if (accessControlPolicy != null) cob.addAttribute(ATTR_ACCESS_CONTROL_POLICY, accessControlPolicy);
        if (authenticationMode != null)  cob.addAttribute(ATTR_AUTHENTICATION_MODE, authenticationMode);
        if (runtimeProvider != null)     cob.addAttribute(ATTR_RUNTIME_PROVIDER, runtimeProvider);
        if (language != null)            cob.addAttribute(ATTR_LANGUAGE, language);
        if (schemaName != null)          cob.addAttribute(ATTR_SCHEMA_NAME, schemaName);
        if (publishedOn != null)         cob.addAttribute(ATTR_PUBLISHED_ON, publishedOn);
        if (createdOn != null)           cob.addAttribute(ATTR_CREATED_ON, createdOn);
        if (modifiedOn != null)          cob.addAttribute(ATTR_MODIFIED_ON, modifiedOn);
        if (contentModeration != null)        cob.addAttribute(ATTR_CONTENT_MODERATION, contentModeration);
        if (generativeActionsEnabled != null) cob.addAttribute(ATTR_GENERATIVE_ACTIONS_ENABLED, generativeActionsEnabled);
        if (useModelKnowledge != null)        cob.addAttribute(ATTR_USE_MODEL_KNOWLEDGE, useModelKnowledge);

        // Derive relationship attributes from child components
        List<String> toolIds = new ArrayList<>();
        List<String> knowledgeBaseIds = new ArrayList<>();
        // OPENICF-5007 begin: renamed local from connectedAgents to connectedAgentReferences to match constant rename
        List<String> connectedAgentReferences = new ArrayList<>();
        // OPENICF-5007 end

        for (BotComponentDescriptor comp : allComponents) {
            if (!botId.equals(comp.getParentBotId())) continue;
            switch (comp.getComponentKind()) {
                case TOOL_CONNECTOR:
                case TOOL_MCP:
                    toolIds.add(comp.getBotComponentId());
                    break;
                case KNOWLEDGE_SOURCE:
                    knowledgeBaseIds.add(comp.getBotComponentId());
                    break;
                case CONNECTED_AGENT:
                    if (comp.getSchemaName() != null) {
                        // OPENICF-5007 begin
                        connectedAgentReferences.add(comp.getSchemaName());
                        // OPENICF-5007 end
                    }
                    break;
                default:
                    break;
            }
        }

        cob.addAttribute(AttributeBuilder.build(ATTR_TOOL_IDS, toolIds));
        cob.addAttribute(AttributeBuilder.build(ATTR_KNOWLEDGE_BASE_IDS, knowledgeBaseIds));
        // OPENICF-5007 begin
        cob.addAttribute(AttributeBuilder.build(ATTR_CONNECTED_AGENT_REFERENCES, connectedAgentReferences));
        // OPENICF-5007 end

        return cob.build();
    }

    public String getBotId() { return botId; }

    private static String accessControlPolicyLabel(int value) {
        switch (value) {
            case ACCESS_POLICY_ANY:              return "Any";
            case ACCESS_POLICY_AGENT_READERS:    return "Agent readers";
            case ACCESS_POLICY_GROUP_MEMBERSHIP: return "Group membership";
            case ACCESS_POLICY_ANY_MULTITENANT:  return "Any (multi-tenant)";
            default:                             return String.valueOf(value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return (f != null && !f.isNull()) ? f.asText() : null;
    }
}