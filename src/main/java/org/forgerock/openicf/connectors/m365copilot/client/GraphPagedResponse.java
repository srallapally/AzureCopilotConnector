// src/main/java/org/forgerock/openicf/connectors/m365copilot/client/GraphPagedResponse.java
package org.forgerock.openicf.connectors.m365copilot.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public final class GraphPagedResponse {

    private final List<JsonNode> value;
    private final String nextLink;

    public GraphPagedResponse(List<JsonNode> value, String nextLink) {
        this.value = value != null ? value : Collections.emptyList();
        this.nextLink = nextLink;
    }

    public List<JsonNode> getValue() {
        return value;
    }

    public String getNextLink() {
        return nextLink;
    }

    public boolean hasNextLink() {
        return nextLink != null && !nextLink.isEmpty();
    }

    public static GraphPagedResponse fromJson(JsonNode root) {
        List<JsonNode> items = new ArrayList<>();
        JsonNode valueNode = root.get("value");
        if (valueNode != null && valueNode.isArray()) {
            for (JsonNode item : valueNode) {
                items.add(item);
            }
        }
        String next = null;
        JsonNode nextNode = root.get("@odata.nextLink");
        if (nextNode != null && !nextNode.isNull()) {
            next = nextNode.asText();
        }
        return new GraphPagedResponse(items, next);
    }
}
