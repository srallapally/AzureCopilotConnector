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

    private String agentId;
    private String groupId;
    private String groupDisplayName;
    private String groupMail;
    private String groupType;

    public static List<CopilotIdentityBindingDescriptor> fromInventoryJson(JsonNode inventoryRoot) {
        List<CopilotIdentityBindingDescriptor> result = new ArrayList<>();
        JsonNode bindings = inventoryRoot.get("identityBindings");
        if (bindings == null || !bindings.isArray()) {
            return result;
        }
        for (JsonNode binding : bindings) {
            CopilotIdentityBindingDescriptor d = new CopilotIdentityBindingDescriptor();
            d.agentId = text(binding, "agentId");
            d.groupId = text(binding, "groupId");
            d.groupDisplayName = text(binding, "groupDisplayName");
            d.groupMail = text(binding, "groupMail");
            d.groupType = text(binding, "groupType");
            result.add(d);
        }
        return result;
    }

    public String getCompositeUid() {
        return agentId + ":" + groupId;
    }

    public String getPrincipalJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("groupId", groupId);
        node.put("displayName", groupDisplayName);
        node.put("mail", groupMail);
        node.put("groupType", groupType);
        return node.toString();
    }

    public ConnectorObject toConnectorObject() {
        String compositeUid = getCompositeUid();
        ConnectorObjectBuilder cob = new ConnectorObjectBuilder();
        cob.setObjectClass(new ObjectClass(OC_AGENT_IDENTITY_BINDING));
        cob.setUid(compositeUid);
        cob.setName(compositeUid);

        cob.addAttribute(ATTR_PLATFORM, PLATFORM);
        cob.addAttribute(ATTR_AGENT_ID, agentId);
        cob.addAttribute(ATTR_AGENT_VERSION, "latest");
        cob.addAttribute(ATTR_KIND, "GROUP");
        cob.addAttribute(ATTR_PRINCIPAL, getPrincipalJson());
        cob.addAttribute(AttributeBuilder.build(ATTR_PERMISSIONS, Collections.emptyList()));
        cob.addAttribute(ATTR_SCOPE, "ENVIRONMENT");
        cob.addAttribute(ATTR_SCOPE_RESOURCE_ID, agentId);

        return cob.build();
    }

    public String getAgentId() { return agentId; }
    public String getGroupId() { return groupId; }

    private static String text(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return (f != null && !f.isNull()) ? f.asText() : null;
    }
}