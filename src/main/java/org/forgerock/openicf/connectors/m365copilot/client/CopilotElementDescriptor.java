// src/main/java/org/forgerock/openicf/connectors/m365copilot/client/CopilotElementDescriptor.java
package org.forgerock.openicf.connectors.m365copilot.client;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;

import static org.forgerock.openicf.connectors.m365copilot.utils.M365CopilotConstants.*;

public class CopilotElementDescriptor {

    private String id;
    private String parentPackageId;
    private String elementType;
    private String definitionJson;

    public static List<CopilotElementDescriptor> fromInventoryJson(JsonNode inventoryRoot, String packageId) {
        List<CopilotElementDescriptor> result = new ArrayList<>();

        // Try elementDetails[].elements[] first, then fall back to tools[]
        JsonNode elementDetails = inventoryRoot.get("elementDetails");
        if (elementDetails != null && elementDetails.isArray()) {
            for (JsonNode ed : elementDetails) {
                String edType = ed.has("elementType") ? ed.get("elementType").asText() : null;
                JsonNode elements = ed.get("elements");
                if (elements != null && elements.isArray()) {
                    for (JsonNode elem : elements) {
                        CopilotElementDescriptor d = new CopilotElementDescriptor();
                        d.id = elem.has("id") ? elem.get("id").asText() : null;
                        d.parentPackageId = packageId;
                        d.elementType = edType;
                        d.definitionJson = elem.has("definition") ? elem.get("definition").toString() : null;
                        result.add(d);
                    }
                }
            }
        }

        JsonNode tools = inventoryRoot.get("tools");
        if (tools != null && tools.isArray()) {
            for (JsonNode tool : tools) {
                CopilotElementDescriptor d = new CopilotElementDescriptor();
                d.id = tool.has("id") ? tool.get("id").asText() : null;
                d.parentPackageId = packageId;
                d.elementType = tool.has("elementType") ? tool.get("elementType").asText() : null;
                d.definitionJson = tool.has("definition") ? tool.get("definition").toString() : null;
                result.add(d);
            }
        }

        return result;
    }

    public ConnectorObject toConnectorObject() {
        ConnectorObjectBuilder cob = new ConnectorObjectBuilder();
        cob.setObjectClass(new ObjectClass(OC_AGENT_TOOL));
        cob.setUid(id);
        cob.setName(id);

        cob.addAttribute(ATTR_AGENT_ID, parentPackageId);
        if (elementType != null) {
            cob.addAttribute(ATTR_TOOL_TYPE, elementType);
        }
        // description is null per spec — API does not provide it
        cob.addAttribute(ATTR_DESCRIPTION, (String) null);
        if (definitionJson != null) {
            cob.addAttribute(ATTR_SCHEMA_URI, definitionJson);
        }
        cob.addAttribute(ATTR_PLATFORM, PLATFORM);

        return cob.build();
    }

    public String getId() { return id; }
}
