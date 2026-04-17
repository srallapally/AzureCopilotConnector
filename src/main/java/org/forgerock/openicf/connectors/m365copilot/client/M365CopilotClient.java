// src/main/java/org/forgerock/openicf/connectors/m365copilot/client/M365CopilotClient.java
package org.forgerock.openicf.connectors.m365copilot.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
// OPENICF-5003 begin
import org.apache.http.Header;
// OPENICF-5003 end
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
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

    // OPENICF-5003 begin
    private static final int MAX_ATTEMPTS = 3;                 // 1 initial + 2 retries
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 5;  // used when header missing/unparseable
    private static final int MAX_RETRY_AFTER_SECONDS = 60;     // per-wait cap
    // OPENICF-5003 end

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
                URI uri = new URIBuilderWrapper("/bots")
                        .addParameter("$select", BOTS_SELECT)
                        .addParameter("$orderby", "createdon asc")
                        .build();
                cachedBots = getAllPages(uri);
                LOG.ok("Loaded {0} bots into cache", cachedBots.size());
            }
            return cachedBots;
        }
    }

    public List<BotComponentDescriptor> listAllBotComponents() {
        synchronized (cacheLock) {
            if (cachedBotComponents == null) {
                URI uri = new URIBuilderWrapper("/botcomponents")
                        .addParameter("$select", BOT_COMPONENTS_SELECT)
                        .addParameter("$filter", BOT_COMPONENTS_FILTER)
                        .addParameter("$orderby", "_parentbotid_value asc,componenttype asc")
                        .build();
                List<JsonNode> raw = getAllPages(uri);
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
        URI uri = new URIBuilderWrapper("/bots(" + botId + ")")
                .addParameter("$select", BOTS_SELECT)
                .build();
        return executeGet(uri);
    }

    public JsonNode getBotComponent(String botComponentId) {
        URI uri = new URIBuilderWrapper("/botcomponents(" + botComponentId + ")")
                .addParameter("$select", BOT_COMPONENTS_SELECT)
                .build();
        return executeGet(uri);
    }

    // --- Inventory JSON ---

    public JsonNode fetchInventoryJson() {
        synchronized (cacheLock) {
            if (cachedInventory != null) {
                return cachedInventory;
            }
            if (cfg.getToolsInventoryUrl() != null && !cfg.getToolsInventoryUrl().isEmpty()) {
                // OPENICF-5014 begin: SAS URLs are self-authenticating — sending a Bearer token alongside
                // the SAS signature causes Azure Blob Storage to return 400 InvalidAuthenticationInfo.
                cachedInventory = executeGetUnauthenticated(cfg.getToolsInventoryUrl());
                // OPENICF-5014 end
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
        URI uri = new URIBuilderWrapper("/bots")
                .addParameter("$top", "1")
                .addParameter("$select", "botid")
                .build();
        executeGet(uri);
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

    private List<JsonNode> getAllPages(URI initialUri) {
        List<JsonNode> allItems = new ArrayList<>();

        // First page uses the URI we built; subsequent pages follow @odata.nextLink
        // as opaque, already-encoded strings returned by the server.
        JsonNode response = executeGet(initialUri);
        ODataPagedResponse page = ODataPagedResponse.fromJson(response);
        allItems.addAll(page.getValue());

        String nextUrl = page.hasNextLink() ? page.getNextLink() : null;
        while (nextUrl != null) {
            LOG.ok("Following @odata.nextLink, accumulated {0} items so far", allItems.size());
            response = executeGet(nextUrl);
            page = ODataPagedResponse.fromJson(response);
            allItems.addAll(page.getValue());
            nextUrl = page.hasNextLink() ? page.getNextLink() : null;
        }

        return allItems;
    }

    private JsonNode executeGet(URI uri) {
        HttpGet get = new HttpGet(uri);
        return sendWithDataverseHeaders(get);
    }

    private JsonNode executeGet(String url) {
        HttpGet get = new HttpGet(url);
        return sendWithDataverseHeaders(get);
    }

    private JsonNode sendWithDataverseHeaders(HttpGet get) {
        get.setHeader("Authorization", "Bearer " + getAccessToken());
        get.setHeader("Accept", "application/json");
        get.setHeader("OData-MaxVersion", "4.0");
        get.setHeader("OData-Version", "4.0");
        return execute(get);
    }

    // OPENICF-5014 begin: unauthenticated GET for SAS URLs — no Authorization header
    private JsonNode executeGetUnauthenticated(String url) {
        HttpGet get = new HttpGet(url);
        get.setHeader("Accept", "application/json");
        return execute(get);
    }
    // OPENICF-5014 end

    private JsonNode execute(HttpUriRequest request) {
        // OPENICF-5003 begin
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                HttpEntity entity = response.getEntity();
                String body = entity != null ? EntityUtils.toString(entity, StandardCharsets.UTF_8) : "";

                if (cfg.isLogPayloads()) {
                    LOG.ok("HTTP {0} {1} -> {2}\n{3}",
                            request.getMethod(), request.getURI(), status, body);
                }

                if (status == 429 || status == 503 || status == 504) {
                    if (attempt == MAX_ATTEMPTS) {
                        LOG.warn("HTTP {0} from {1} after {2} attempts — giving up",
                                status, request.getURI(), attempt);
                        throw new ConnectorException(
                                "HTTP " + status + " from " + request.getURI()
                                        + " after " + attempt + " attempts: " + body);
                    }
                    int sleepSeconds = parseRetryAfterSeconds(response);
                    if (attempt == 1) {
                        LOG.warn("HTTP {0} from {1} — retrying after {2}s",
                                status, request.getURI(), sleepSeconds);
                    }
                    try {
                        Thread.sleep(sleepSeconds * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ConnectorException("Retry interrupted for " + request.getURI(), ie);
                    }
                    continue;
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
        // Unreachable: the loop either returns on success or throws on give-up.
        throw new ConnectorException("Retry loop exited without result for " + request.getURI());
        // OPENICF-5003 end
    }

    // OPENICF-5003 begin
    /**
     * Reads Retry-After header and returns a sleep duration in seconds, clamped to
     * [0, MAX_RETRY_AFTER_SECONDS]. Falls back to DEFAULT_RETRY_AFTER_SECONDS when the
     * header is missing, not an integer (e.g., HTTP-date form), or negative.
     */
    private static int parseRetryAfterSeconds(CloseableHttpResponse response) {
        Header header = response.getFirstHeader("Retry-After");
        if (header == null || header.getValue() == null) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(header.getValue().trim());
            if (seconds < 0) {
                return DEFAULT_RETRY_AFTER_SECONDS;
            }
            return Math.min(seconds, MAX_RETRY_AFTER_SECONDS);
        } catch (NumberFormatException e) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
    }
    // OPENICF-5003 end

    /**
     * Thin wrapper around URIBuilder for constructing Dataverse API URIs.
     * Parses the base URL + relative path verbatim (preserving the "(id)" parenthesized
     * key syntax used by OData) and allows adding query parameters that are properly
     * percent-encoded by URIBuilder.
     */
    private final class URIBuilderWrapper {
        private final URIBuilder builder;

        URIBuilderWrapper(String relativePath) {
            try {
                this.builder = new URIBuilder(dataverseBaseUrl + relativePath);
            } catch (URISyntaxException e) {
                throw new ConnectorException(
                        "Failed to construct URI for path: " + relativePath + " — " + e.getMessage(), e);
            }
        }

        URIBuilderWrapper addParameter(String name, String value) {
            builder.addParameter(name, value);
            return this;
        }

        URI build() {
            try {
                return builder.build();
            } catch (URISyntaxException e) {
                throw new ConnectorException("Failed to build URI: " + e.getMessage(), e);
            }
        }
    }
}