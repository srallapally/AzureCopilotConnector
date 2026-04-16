// src/main/java/org/forgerock/openicf/connectors/m365copilot/client/M365CopilotClient.java
package org.forgerock.openicf.connectors.m365copilot.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    private static final String BOTS_SELECT =
            "botid,name,statecode,statuscode,accesscontrolpolicy,authorizedsecuritygroupids," +
                    "authenticationmode,runtimeprovider,language,schemaname,publishedon,createdon,modifiedon," +
                    "configuration";

    private static final String BOT_COMPONENTS_SELECT =
            "botcomponentid,name,componenttype,data,_parentbotid_value,schemaname,description,createdon,modifiedon";

    private static final String BOT_COMPONENTS_FILTER =
            "componenttype eq " + M365CopilotConstants.COMPONENT_TYPE_TOPIC_V2 +
                    " or componenttype eq " + M365CopilotConstants.COMPONENT_TYPE_KNOWLEDGE_SOURCE;

    private final CloseableHttpClient httpClient;
    private final M365CopilotConfiguration cfg;
    private final String dataverseBaseUrl;
    private final String tokenScope;

    private volatile String cachedToken;
    private volatile long tokenExpiresAt = 0;
    private final Object tokenLock = new Object();

    private volatile List<JsonNode> cachedBots = null;
    private volatile List<BotComponentDescriptor> cachedBotComponents = null;
    private volatile JsonNode cachedInventory = null;
    private final Object cacheLock = new Object();

    public M365CopilotClient(M365CopilotConfiguration cfg) {
        this.cfg = cfg;
        this.dataverseBaseUrl = cfg.getEnvironmentUrl() + M365CopilotConstants.DATAVERSE_API_PATH;
        this.tokenScope = cfg.getEnvironmentUrl() + "/.default";

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

    // --- Token acquisition ---

    public String getAccessToken() {
        synchronized (tokenLock) {
            if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) {
                return cachedToken;
            }
            return acquireTokenViaClientCredentials();
        }
    }

    private String acquireTokenViaClientCredentials() {
        String tokenUrl = String.format(M365CopilotConstants.TOKEN_ENDPOINT_TEMPLATE, cfg.getTenantId());

        HttpPost post = new HttpPost(tokenUrl);
        List<BasicNameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("grant_type", "client_credentials"));
        params.add(new BasicNameValuePair("client_id", cfg.getClientId()));
        params.add(new BasicNameValuePair("client_secret", SecurityUtil.decrypt(cfg.getClientSecret())));
        params.add(new BasicNameValuePair("scope", tokenScope));

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

    // --- Cached bulk queries ---

    public List<JsonNode> listAllBots() {
        synchronized (cacheLock) {
            if (cachedBots == null) {
                String path = "/bots?$select=" + BOTS_SELECT + "&$orderby=createdon asc";
                cachedBots = getAllPages(path);
                LOG.ok("Loaded {0} bots into cache", cachedBots.size());
            }
            return cachedBots;
        }
    }

    public List<BotComponentDescriptor> listAllBotComponents() {
        synchronized (cacheLock) {
            if (cachedBotComponents == null) {
                String path = "/botcomponents?$select=" + BOT_COMPONENTS_SELECT +
                        "&$filter=" + BOT_COMPONENTS_FILTER +
                        "&$orderby=_parentbotid_value asc,componenttype asc";
                List<JsonNode> raw = getAllPages(path);
                List<BotComponentDescriptor> parsed = new ArrayList<>(raw.size());
                for (JsonNode node : raw) {
                    parsed.add(BotComponentDescriptor.fromJson(node));
                }
                cachedBotComponents = parsed;
                LOG.ok("Loaded {0} botcomponents into cache", cachedBotComponents.size());
            }
            return cachedBotComponents;
        }
    }

    // --- GET by UID ---

    public JsonNode getBot(String botId) {
        return dataverseGet("/bots(" + botId + ")?$select=" + BOTS_SELECT);
    }

    public JsonNode getBotComponent(String botComponentId) {
        return dataverseGet("/botcomponents(" + botComponentId + ")?$select=" + BOT_COMPONENTS_SELECT);
    }

    // --- Inventory JSON ---

    public JsonNode fetchInventoryJson() {
        synchronized (cacheLock) {
            if (cachedInventory != null) {
                return cachedInventory;
            }
            if (cfg.getToolsInventoryUrl() != null && !cfg.getToolsInventoryUrl().isEmpty()) {
                cachedInventory = executeGet(cfg.getToolsInventoryUrl());
                return cachedInventory;
            }
            if (cfg.getToolsInventoryFilePath() != null && !cfg.getToolsInventoryFilePath().isEmpty()) {
                try {
                    String content = new String(
                            Files.readAllBytes(Paths.get(cfg.getToolsInventoryFilePath())),
                            StandardCharsets.UTF_8);
                    cachedInventory = MAPPER.readTree(content);
                    return cachedInventory;
                } catch (IOException e) {
                    throw new ConfigurationException(
                            "Failed to read inventory file: " + cfg.getToolsInventoryFilePath() +
                                    " — " + e.getMessage(), e);
                }
            }
            return null;
        }
    }

    // --- Test ---

    public void testConnection() {
        dataverseGet("/bots?$top=1&$select=botid");
        LOG.ok("Test connection successful");
    }

    // --- Lifecycle ---

    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            LOG.warn("Error closing HTTP client: {0}", e.getMessage());
        }
    }

    // --- Internal HTTP ---

    private JsonNode dataverseGet(String path) {
        return executeGet(dataverseBaseUrl + path);
    }

    private List<JsonNode> getAllPages(String path) {
        List<JsonNode> allItems = new ArrayList<>();
        String url = dataverseBaseUrl + path;

        while (url != null) {
            JsonNode response = executeGet(url);
            ODataPagedResponse page = ODataPagedResponse.fromJson(response);
            allItems.addAll(page.getValue());
            if (page.hasNextLink()) {
                url = page.getNextLink();
                LOG.ok("Following @odata.nextLink, accumulated {0} items so far", allItems.size());
            } else {
                url = null;
            }
        }

        return allItems;
    }

    private JsonNode executeGet(String url) {
        HttpGet get = new HttpGet(url);
        get.setHeader("Authorization", "Bearer " + getAccessToken());
        get.setHeader("Accept", "application/json");
        get.setHeader("OData-MaxVersion", "4.0");
        get.setHeader("OData-Version", "4.0");
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