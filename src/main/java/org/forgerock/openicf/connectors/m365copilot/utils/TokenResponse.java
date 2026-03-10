// src/main/java/org/forgerock/openicf/connectors/m365copilot/utils/TokenResponse.java
package org.forgerock.openicf.connectors.m365copilot.utils;

import com.fasterxml.jackson.databind.JsonNode;

public final class TokenResponse {

    private final String accessToken;
    private final long expiresInSeconds;

    public TokenResponse(String accessToken, long expiresInSeconds) {
        this.accessToken = accessToken;
        this.expiresInSeconds = expiresInSeconds;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public static TokenResponse fromJson(JsonNode node) {
        String token = node.get("access_token").asText();
        long expiresIn = node.get("expires_in").asLong();
        return new TokenResponse(token, expiresIn);
    }
}
