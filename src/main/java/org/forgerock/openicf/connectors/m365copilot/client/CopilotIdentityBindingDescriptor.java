// src/main/java/org/forgerock/openicf/connectors/m365copilot/client/CopilotIdentityBindingDescriptor.java
package org.forgerock.openicf.connectors.m365copilot.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;

import static org.forgerock.openicf.connectors.m365copilot.utils.M365CopilotConstants.*;

public class CopilotIdentityBindingDescriptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String packageId;
    private String resourceId;
    private String resourceType;
    private String upn;
    private String displayName;

    public static List<CopilotIdentityBindingDescriptor> fromInventoryJson(JsonNode inventoryRoot, String packageId) {
        List<CopilotIdentityBindingDescriptor> result = new ArrayList<>();
        JsonNode bindings = inventoryRoot.get("identityBindings");
        if (bindings == null || !bindings.isArray()) {
            return result;
        }
        for (JsonNode binding : bindings) {
            CopilotIdentityBindingDescriptor d = new CopilotIdentityBindingDescriptor();
            d.packageId = packageId;
            d.resourceId = binding.has("resourceId") ? binding.get("resourceId").asText() : null;
            d.resourceType = binding.has("resourceType") ? binding.get("resourceType").asText() : null;
            d.upn = binding.has("upn") ? binding.get("upn").asText() : null;
            d.displayName = binding.has("displayName") ? binding.get("displayName").asText() : null;
            result.add(d);
        }
        return result;
    }

    public String getCompositeUid() {
        return packageId + ":" + resourceId;
    }

    public String getKind() {
        if ("user".equalsIgnoreCase(resourceType)) {
            return "DIRECT";
        }
        if ("group".equalsIgnoreCase(resourceType)) {
            return "GROUP";
        }
        return resourceType;
    }

    public String getPrincipalJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("resourceId", resourceId);
        node.put("resourceType", resourceType);
        node.put("upn", upn);
        node.put("displayName", displayName);
        return node.toString();
    }

    public ConnectorObject toConnectorObject() {
        String compositeUid = getCompositeUid();
        ConnectorObjectBuilder cob = new ConnectorObjectBuilder();
        cob.setObjectClass(new ObjectClass(OC_AGENT_IDENTITY_BINDING));
        cob.setUid(compositeUid);
        cob.setName(compositeUid);

        cob.addAttribute(ATTR_PLATFORM, PLATFORM);
        cob.addAttribute(ATTR_AGENT_ID, packageId);
        cob.addAttribute(ATTR_AGENT_VERSION, "latest");
        cob.addAttribute(ATTR_KIND, getKind());
        cob.addAttribute(ATTR_PRINCIPAL, getPrincipalJson());
        cob.addAttribute(AttributeBuilder.build(ATTR_PERMISSIONS, Collections.emptyList()));
        cob.addAttribute(ATTR_SCOPE, "CATALOG");
        cob.addAttribute(ATTR_SCOPE_RESOURCE_ID, packageId);

        return cob.build();
    }

    public String getPackageId() { return packageId; }
    public String getResourceId() { return resourceId; }
}
