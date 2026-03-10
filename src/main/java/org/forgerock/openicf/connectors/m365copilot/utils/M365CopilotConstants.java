// src/main/java/org/forgerock/openicf/connectors/m365copilot/utils/M365CopilotConstants.java
package org.forgerock.openicf.connectors.m365copilot.utils;

public final class M365CopilotConstants {

    private M365CopilotConstants() { }

    public static final String PLATFORM = "M365_COPILOT";
    public static final String GRAPH_BASE = "https://graph.microsoft.com";
    public static final String TOKEN_ENDPOINT_TEMPLATE =
            "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    public static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";
    public static final String PACKAGES_PATH = "/copilot/admin/catalog/packages";

    // Object class names
    public static final String OC_AGENT_TOOL = "agentTool";
    public static final String OC_AGENT_IDENTITY_BINDING = "agentIdentityBinding";

    // __ACCOUNT__ attribute names
    public static final String ATTR_PLATFORM = "platform";
    public static final String ATTR_AGENT_ID = "agentId";
    public static final String ATTR_DESCRIPTION = "description";
    public static final String ATTR_IS_BLOCKED = "isBlocked";
    public static final String ATTR_PACKAGE_TYPE = "packageType";
    public static final String ATTR_SUPPORTED_HOSTS = "supportedHosts";
    public static final String ATTR_AVAILABLE_TO = "availableTo";
    public static final String ATTR_DEPLOYED_TO = "deployedTo";
    public static final String ATTR_UPDATED_AT = "updatedAt";
    public static final String ATTR_VERSION = "version";
    public static final String ATTR_MANIFEST_VERSION = "manifestVersion";
    public static final String ATTR_CATEGORIES = "categories";
    public static final String ATTR_LONG_DESCRIPTION = "longDescription";
    public static final String ATTR_TOOLS_RAW = "toolsRaw";
    public static final String ATTR_TOOL_IDS = "toolIds";
    public static final String ATTR_OWNER = "owner";
    public static final String ATTR_CREATED_AT = "createdAt";
    public static final String ATTR_ENTRA_AGENT_OBJECT_ID = "entraAgentObjectId";

    // agentTool attribute names
    public static final String ATTR_TOOL_TYPE = "toolType";
    public static final String ATTR_SCHEMA_URI = "schemaUri";

    // agentIdentityBinding attribute names
    public static final String ATTR_AGENT_VERSION = "agentVersion";
    public static final String ATTR_KIND = "kind";
    public static final String ATTR_PRINCIPAL = "principal";
    public static final String ATTR_PERMISSIONS = "permissions";
    public static final String ATTR_SCOPE = "scope";
    public static final String ATTR_SCOPE_RESOURCE_ID = "scopeResourceId";
}
