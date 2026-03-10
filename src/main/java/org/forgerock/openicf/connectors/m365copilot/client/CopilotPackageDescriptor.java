// src/main/java/org/forgerock/openicf/connectors/m365copilot/client/CopilotPackageDescriptor.java
package org.forgerock.openicf.connectors.m365copilot.client;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;

import static org.forgerock.openicf.connectors.m365copilot.utils.M365CopilotConstants.*;

public class CopilotPackageDescriptor {

    // List-level fields
    private String id;
    private String displayName;
    private String shortDescription;
    private Boolean isBlocked;
    private String type;
    private List<String> supportedHosts;
    private String availableTo;
    private String deployedTo;
    private String lastModifiedDateTime;

    // Lazy fields (GET-by-UID / detail endpoint)
    private String version;
    private String manifestVersion;
    private List<String> categories;
    private String longDescription;
    private String toolsRaw;
    private List<String> toolIds;

    // Inventory fields
    private String owner;
    private String createdAt;
    private String entraAgentObjectId;

    private boolean detailEnriched = false;
    private boolean inventoryEnriched = false;

    public static CopilotPackageDescriptor fromListJson(JsonNode node) {
        CopilotPackageDescriptor d = new CopilotPackageDescriptor();
        d.id = textOrNull(node, "id");
        d.displayName = textOrNull(node, "displayName");
        d.shortDescription = textOrNull(node, "shortDescription");
        d.isBlocked = node.has("isBlocked") && !node.get("isBlocked").isNull()
                ? node.get("isBlocked").asBoolean() : null;
        d.type = textOrNull(node, "type");
        d.supportedHosts = stringListOrNull(node, "supportedHosts");
        d.availableTo = textOrNull(node, "availableTo");
        d.deployedTo = textOrNull(node, "deployedTo");
        d.lastModifiedDateTime = textOrNull(node, "lastModifiedDateTime");
        return d;
    }

    public void enrichFromDetail(JsonNode detailNode) {
        this.detailEnriched = true;
        this.version = textOrNull(detailNode, "version");
        this.manifestVersion = textOrNull(detailNode, "manifestVersion");
        this.categories = stringListOrNull(detailNode, "categories");
        this.longDescription = textOrNull(detailNode, "longDescription");

        JsonNode elementDetails = detailNode.get("elementDetails");
        if (elementDetails != null && elementDetails.isArray()) {
            this.toolsRaw = elementDetails.toString();
            this.toolIds = new ArrayList<>();
            for (JsonNode ed : elementDetails) {
                JsonNode elements = ed.get("elements");
                if (elements != null && elements.isArray()) {
                    for (JsonNode elem : elements) {
                        String elemId = textOrNull(elem, "id");
                        if (elemId != null) {
                            this.toolIds.add(elemId);
                        }
                    }
                }
            }
        }
    }

    public void enrichFromInventory(JsonNode inventoryNode) {
        this.inventoryEnriched = true;
        this.owner = textOrNull(inventoryNode, "owner");
        this.createdAt = textOrNull(inventoryNode, "createdAt");
        this.entraAgentObjectId = textOrNull(inventoryNode, "entraAgentObjectId");
    }

    public ConnectorObject toConnectorObject() {
        ConnectorObjectBuilder cob = new ConnectorObjectBuilder();
        cob.setObjectClass(ObjectClass.ACCOUNT);
        cob.setUid(id);
        cob.setName(displayName != null ? displayName : id);

        cob.addAttribute(ATTR_PLATFORM, PLATFORM);
        cob.addAttribute(ATTR_AGENT_ID, id);

        if (shortDescription != null) {
            cob.addAttribute(ATTR_DESCRIPTION, shortDescription);
        }
        if (isBlocked != null) {
            cob.addAttribute(ATTR_IS_BLOCKED, isBlocked);
        }
        if (type != null) {
            cob.addAttribute(ATTR_PACKAGE_TYPE, type);
        }
        if (supportedHosts != null && !supportedHosts.isEmpty()) {
            cob.addAttribute(AttributeBuilder.build(ATTR_SUPPORTED_HOSTS, supportedHosts));
        }
        if (availableTo != null) {
            cob.addAttribute(ATTR_AVAILABLE_TO, availableTo);
        }
        if (deployedTo != null) {
            cob.addAttribute(ATTR_DEPLOYED_TO, deployedTo);
        }
        if (lastModifiedDateTime != null) {
            cob.addAttribute(ATTR_UPDATED_AT, lastModifiedDateTime);
        }

        // Lazy attributes — only populated when detail-enriched
        if (detailEnriched) {
            if (version != null) {
                cob.addAttribute(ATTR_VERSION, version);
            }
            if (manifestVersion != null) {
                cob.addAttribute(ATTR_MANIFEST_VERSION, manifestVersion);
            }
            if (categories != null && !categories.isEmpty()) {
                cob.addAttribute(AttributeBuilder.build(ATTR_CATEGORIES, categories));
            }
            if (longDescription != null) {
                cob.addAttribute(ATTR_LONG_DESCRIPTION, longDescription);
            }
            if (toolsRaw != null) {
                cob.addAttribute(ATTR_TOOLS_RAW, toolsRaw);
            }
            if (toolIds != null && !toolIds.isEmpty()) {
                cob.addAttribute(AttributeBuilder.build(ATTR_TOOL_IDS, toolIds));
            }
        }

        // Inventory attributes
        if (inventoryEnriched) {
            if (owner != null) {
                cob.addAttribute(ATTR_OWNER, owner);
            }
            if (createdAt != null) {
                cob.addAttribute(ATTR_CREATED_AT, createdAt);
            }
            if (entraAgentObjectId != null) {
                cob.addAttribute(ATTR_ENTRA_AGENT_OBJECT_ID, entraAgentObjectId);
            }
        }

        return cob.build();
    }

    public String getId() { return id; }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return (f != null && !f.isNull()) ? f.asText() : null;
    }

    private static List<String> stringListOrNull(JsonNode node, String field) {
        JsonNode arr = node.get(field);
        if (arr == null || !arr.isArray()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : arr) {
            result.add(item.asText());
        }
        return result;
    }
}
