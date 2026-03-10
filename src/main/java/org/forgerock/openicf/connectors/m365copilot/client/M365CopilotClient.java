// src/main/java/org/forgerock/openicf/connectors/m365copilot/client/M365CopilotClient.java
package org.forgerock.openicf.connectors.m365copilot.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.forgerock.openicf.connectors.m365copilot.M365CopilotConfiguration;
import org.forgerock.openicf.connectors.m365copilot.utils.M365CopilotConstants;
import org.forgerock.openicf.connectors.m365copilot.utils.TokenResponse;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.SecurityUtil;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.InvalidCredentialException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;

public class M365CopilotClient {

    private static final Log LOG = Log.getLog(M365CopilotClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CloseableHttpClient httpClient;
    private final M365CopilotConfiguration cfg;
    private final String graphBaseUrl;

    private volatile String cachedToken;
    private volatile long tokenExpiresAt = 0;
    private final Object tokenLock = new Object();

    public M365CopilotClient(M365CopilotConfiguration cfg) {
        this.cfg = cfg;
        this.graphBaseUrl = M365CopilotConstants.GRAPH_BASE + "/" + cfg.getGraphApiVersion();

        int timeoutMs = cfg.getHttpTimeoutSeconds() * 1000;
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .build();
        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    public String getAccessToken() {
        synchronized (tokenLock) {
            if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) {
                return cachedToken;
            }

            if (cfg.isUseManagedIdentity()) {
                return acquireTokenViaManagedIdentity();
            } else {
                return acquireTokenViaClientCredentials();
            }
        }
    }

    private String acquireTokenViaManagedIdentity() {
        try {
            TokenRequestContext context = new TokenRequestContext();
            context.addScopes(M365CopilotConstants.GRAPH_SCOPE);

            com.azure.core.credential.AccessToken azureToken =
                    new DefaultAzureCredentialBuilder().build()
                            .getTokenSync(context);

            cachedToken = azureToken.getToken();
            tokenExpiresAt = azureToken.getExpiresAt().toInstant().toEpochMilli();
            LOG.ok("Acquired token via managed identity, expires at {0}", azureToken.getExpiresAt());
            return cachedToken;
        } catch (Exception e) {
            throw new InvalidCredentialException("Failed to acquire token via managed identity: " + e.getMessage(), e);
        }
    }

    private String acquireTokenViaClientCredentials() {
        String tokenUrl = String.format(M365CopilotConstants.TOKEN_ENDPOINT_TEMPLATE, cfg.getTenantId());

        HttpPost post = new HttpPost(tokenUrl);
        List<BasicNameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("grant_type", "client_credentials"));
        params.add(new BasicNameValuePair("client_id", cfg.getClientId()));
        params.add(new BasicNameValuePair("client_secret", SecurityUtil.decrypt(cfg.getClientSecret())));
        params.add(new BasicNameValuePair("scope", M365CopilotConstants.GRAPH_SCOPE));

        try {
            post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
            try (CloseableHttpResponse response = httpClient.execute(post)) {
                int status = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                if (status != 200) {
                    throw new InvalidCredentialException(
                            "Token request failed with HTTP " + status + ": " + body);
                }

                TokenResponse tokenResponse = TokenResponse.fromJson(MAPPER.readTree(body));
                cachedToken = tokenResponse.getAccessToken();
                tokenExpiresAt = System.currentTimeMillis() + (tokenResponse.getExpiresInSeconds() * 1000);
                LOG.ok("Acquired token via client credentials, expires in {0}s", tokenResponse.getExpiresInSeconds());
                return cachedToken;
            }
        } catch (InvalidCredentialException e) {
            throw e;
        } catch (IOException e) {
            throw new ConnectorException("Failed to acquire access token: " + e.getMessage(), e);
        }
    }

    public JsonNode graphGet(String path) {
        String url = graphBaseUrl + path;
        return executeGet(url);
    }

    public List<JsonNode> graphGetAllPages(String path) {
        List<JsonNode> allItems = new ArrayList<>();
        String url = graphBaseUrl + path;

        while (url != null) {
            JsonNode response = executeGet(url);
            GraphPagedResponse page = GraphPagedResponse.fromJson(response);
            allItems.addAll(page.getValue());

            if (page.hasNextLink()) {
                url = page.getNextLink();
                LOG.ok("Following @odata.nextLink, accumulated {0} items so far", allItems.size());
            } else {
                url = null;
            }
        }

        LOG.ok("Completed pagination, total items: {0}", allItems.size());
        return allItems;
    }

    public JsonNode graphGetSingle(String path) {
        return graphGet(path);
    }

    public JsonNode fetchInventoryJson(String packageId) {
        if (cfg.getToolsInventoryUrl() != null && !cfg.getToolsInventoryUrl().isEmpty()) {
            String url = cfg.getToolsInventoryUrl().replace("{id}", packageId);
            return executeGet(url);
        }

        if (cfg.getToolsInventoryFilePath() != null && !cfg.getToolsInventoryFilePath().isEmpty()) {
            try {
                String content = new String(Files.readAllBytes(
                        Paths.get(cfg.getToolsInventoryFilePath())), StandardCharsets.UTF_8);
                return MAPPER.readTree(content);
            } catch (IOException e) {
                throw new ConfigurationException(
                        "Failed to read inventory file: " + cfg.getToolsInventoryFilePath() + " — " + e.getMessage(), e);
            }
        }

        return null;
    }

    public void testConnection() {
        graphGet(M365CopilotConstants.PACKAGES_PATH + "?$top=1");
        LOG.ok("Test connection successful");
    }

    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            LOG.warn("Error closing HTTP client: {0}", e.getMessage());
        }
    }

    private JsonNode executeGet(String url) {
        HttpGet get = new HttpGet(url);
        get.setHeader("Authorization", "Bearer " + getAccessToken());
        get.setHeader("Accept", "application/json");
        return execute(get);
    }

    private JsonNode execute(HttpUriRequest request) {
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            HttpEntity entity = response.getEntity();
            String body = entity != null ? EntityUtils.toString(entity, StandardCharsets.UTF_8) : "";

            if (cfg.isLogPayloads()) {
                LOG.ok("HTTP {0} {1} -> {2}\n{3}",
                        request.getMethod(), request.getURI(), status, body);
            }

            if (status == 404) {
                throw new UnknownUidException("Resource not found: " + request.getURI());
            }
            if (status == 401 || status == 403) {
                throw new InvalidCredentialException(
                        "Authentication failed (HTTP " + status + "): " + body);
            }
            if (status < 200 || status >= 300) {
                throw new ConnectorException("HTTP " + status + " from " + request.getURI() + ": " + body);
            }

            return MAPPER.readTree(body);
        } catch (ConnectorException e) {
            throw e;
        } catch (IOException e) {
            throw new ConnectorException("HTTP request failed: " + request.getURI() + " — " + e.getMessage(), e);
        }
    }
}
