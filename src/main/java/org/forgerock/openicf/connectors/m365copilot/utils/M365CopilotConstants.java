// src/main/java/org/forgerock/openicf/connectors/m365copilot/utils/M365CopilotConstants.java
package org.forgerock.openicf.connectors.m365copilot.utils;

public final class M365CopilotConstants {

    private M365CopilotConstants() { }

    // Platform identifier
    public static final String PLATFORM = "COPILOT_STUDIO";

    // Dataverse API base path (appended to environmentUrl)
    public static final String DATAVERSE_API_PATH = "/api/data/v9.2";

    // Token endpoint template — formatted with tenantId
    public static final String TOKEN_ENDPOINT_TEMPLATE =
            "https://login.microsoftonline.com/%s/oauth2/v2.0/token";

    // Object class names
    public static final String OC_AGENT_TOOL             = "agentTool";
    public static final String OC_AGENT_KNOWLEDGE_BASE   = "agentKnowledgeBase";
    public static final String OC_AGENT_IDENTITY_BINDING = "agentIdentityBinding";

    // Dataverse componenttype values
    public static final int COMPONENT_TYPE_TOPIC_V2         = 9;
    public static final int COMPONENT_TYPE_KNOWLEDGE_SOURCE = 16;

    // YAML data field discriminators
    public static final String KIND_TASK_DIALOG            = "TaskDialog";
    public static final String KIND_ADAPTIVE_DIALOG        = "AdaptiveDialog";
    public static final String ACTION_KIND_CONNECTOR       = "InvokeConnectorTaskAction";
    public static final String ACTION_KIND_MCP             = "InvokeExternalAgentTaskAction";
    public static final String ACTION_KIND_CONNECTED_AGENT = "InvokeConnectedAgentTaskAction";

    // Tool type labels (exposed as agentTool.toolType attribute values)
    public static final String TOOL_TYPE_CONNECTOR = "Connector";
    public static final String TOOL_TYPE_MCP       = "MCP";

    // __ACCOUNT__ attribute names
    public static final String ATTR_PLATFORM              = "platform";
    public static final String ATTR_AGENT_ID              = "agentId";
    public static final String ATTR_STATECODE             = "statecode";
    public static final String ATTR_STATUSCODE            = "statuscode";
    public static final String ATTR_ACCESS_CONTROL_POLICY = "accessControlPolicy";
    public static final String ATTR_AUTHENTICATION_MODE   = "authenticationMode";
    public static final String ATTR_RUNTIME_PROVIDER      = "runtimeProvider";
    public static final String ATTR_LANGUAGE              = "language";
    public static final String ATTR_SCHEMA_NAME           = "schemaName";
    public static final String ATTR_PUBLISHED_ON          = "publishedOn";
    public static final String ATTR_CREATED_ON            = "createdOn";
    public static final String ATTR_MODIFIED_ON           = "modifiedOn";
    public static final String ATTR_TOOL_IDS              = "toolIds";
    public static final String ATTR_KNOWLEDGE_BASE_IDS    = "knowledgeBaseIds";
    // OPENICF-5007 begin: renamed from ATTR_CONNECTED_AGENTS/"connectedAgents" — the value is the TaskDialog schemaName, a reference not a resolved target agent. Real target-bot extraction tracked as OPENICF-5010.
    public static final String ATTR_CONNECTED_AGENT_REFERENCES = "connectedAgentReferences";
    // OPENICF-5007 end

    // __ACCOUNT__ configuration-derived attribute names
    public static final String ATTR_CONTENT_MODERATION         = "contentModeration";
    public static final String ATTR_GENERATIVE_ACTIONS_ENABLED = "generativeActionsEnabled";
    public static final String ATTR_USE_MODEL_KNOWLEDGE        = "useModelKnowledge";

    // agentTool attribute names
    public static final String ATTR_TOOL_TYPE            = "toolType";
    public static final String ATTR_CONNECTION_REFERENCE = "connectionReference";
    public static final String ATTR_OPERATION_ID         = "operationId";
    public static final String ATTR_DESCRIPTION          = "description";

    // agentIdentityBinding attribute names
    public static final String ATTR_AGENT_VERSION     = "agentVersion";
    public static final String ATTR_KIND              = "kind";
    public static final String ATTR_PRINCIPAL         = "principal";
    public static final String ATTR_PERMISSIONS       = "permissions";
    public static final String ATTR_SCOPE             = "scope";
    public static final String ATTR_SCOPE_RESOURCE_ID = "scopeResourceId";

    // accessControlPolicy integer values
    public static final int ACCESS_POLICY_ANY             = 0;
    public static final int ACCESS_POLICY_AGENT_READERS   = 1;
    public static final int ACCESS_POLICY_GROUP_MEMBERSHIP = 2;
    public static final int ACCESS_POLICY_ANY_MULTITENANT = 3;
}