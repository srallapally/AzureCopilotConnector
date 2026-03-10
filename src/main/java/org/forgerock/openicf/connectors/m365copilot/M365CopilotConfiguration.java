// src/main/java/org/forgerock/openicf/connectors/m365copilot/M365CopilotConfiguration.java
package org.forgerock.openicf.connectors.m365copilot;

import java.util.HashSet;
import java.util.Set;

import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.common.security.SecurityUtil;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.spi.AbstractConfiguration;
import org.identityconnectors.framework.spi.ConfigurationClass;
import org.identityconnectors.framework.spi.ConfigurationProperty;
import org.identityconnectors.framework.spi.StatefulConfiguration;

@ConfigurationClass(skipUnsupported = true)
public class M365CopilotConfiguration extends AbstractConfiguration implements StatefulConfiguration {

    private String tenantId;
    private String clientId;
    private GuardedString clientSecret;
    private boolean useManagedIdentity = false;
    private String graphApiVersion = "beta";
    private String packageTypeFilter;
    private String toolsInventoryUrl;
    private String toolsInventoryFilePath;
    private boolean fetchPackageDetails = false;
    private boolean entraAgentIdLookupEnabled = false;
    private int httpTimeoutSeconds = 30;
    private boolean logPayloads = false;

    @ConfigurationProperty(order = 1,
            displayMessageKey = "tenantId.display",
            helpMessageKey = "tenantId.help",
            required = true)
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    @ConfigurationProperty(order = 2,
            displayMessageKey = "clientId.display",
            helpMessageKey = "clientId.help")
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    @ConfigurationProperty(order = 3,
            displayMessageKey = "clientSecret.display",
            helpMessageKey = "clientSecret.help",
            confidential = true)
    public GuardedString getClientSecret() { return clientSecret; }
    public void setClientSecret(GuardedString clientSecret) { this.clientSecret = clientSecret; }

    @ConfigurationProperty(order = 4,
            displayMessageKey = "useManagedIdentity.display",
            helpMessageKey = "useManagedIdentity.help")
    public boolean isUseManagedIdentity() { return useManagedIdentity; }
    public void setUseManagedIdentity(boolean useManagedIdentity) { this.useManagedIdentity = useManagedIdentity; }

    @ConfigurationProperty(order = 5,
            displayMessageKey = "graphApiVersion.display",
            helpMessageKey = "graphApiVersion.help")
    public String getGraphApiVersion() { return graphApiVersion; }
    public void setGraphApiVersion(String graphApiVersion) { this.graphApiVersion = graphApiVersion; }

    @ConfigurationProperty(order = 6,
            displayMessageKey = "packageTypeFilter.display",
            helpMessageKey = "packageTypeFilter.help")
    public String getPackageTypeFilter() { return packageTypeFilter; }
    public void setPackageTypeFilter(String packageTypeFilter) { this.packageTypeFilter = packageTypeFilter; }

    @ConfigurationProperty(order = 7,
            displayMessageKey = "toolsInventoryUrl.display",
            helpMessageKey = "toolsInventoryUrl.help")
    public String getToolsInventoryUrl() { return toolsInventoryUrl; }
    public void setToolsInventoryUrl(String toolsInventoryUrl) { this.toolsInventoryUrl = toolsInventoryUrl; }

    @ConfigurationProperty(order = 8,
            displayMessageKey = "toolsInventoryFilePath.display",
            helpMessageKey = "toolsInventoryFilePath.help")
    public String getToolsInventoryFilePath() { return toolsInventoryFilePath; }
    public void setToolsInventoryFilePath(String toolsInventoryFilePath) { this.toolsInventoryFilePath = toolsInventoryFilePath; }

    @ConfigurationProperty(order = 9,
            displayMessageKey = "fetchPackageDetails.display",
            helpMessageKey = "fetchPackageDetails.help")
    public boolean isFetchPackageDetails() { return fetchPackageDetails; }
    public void setFetchPackageDetails(boolean fetchPackageDetails) { this.fetchPackageDetails = fetchPackageDetails; }

    @ConfigurationProperty(order = 10,
            displayMessageKey = "entraAgentIdLookupEnabled.display",
            helpMessageKey = "entraAgentIdLookupEnabled.help")
    public boolean isEntraAgentIdLookupEnabled() { return entraAgentIdLookupEnabled; }
    public void setEntraAgentIdLookupEnabled(boolean entraAgentIdLookupEnabled) { this.entraAgentIdLookupEnabled = entraAgentIdLookupEnabled; }

    @ConfigurationProperty(order = 11,
            displayMessageKey = "httpTimeoutSeconds.display",
            helpMessageKey = "httpTimeoutSeconds.help")
    public int getHttpTimeoutSeconds() { return httpTimeoutSeconds; }
    public void setHttpTimeoutSeconds(int httpTimeoutSeconds) { this.httpTimeoutSeconds = httpTimeoutSeconds; }

    @ConfigurationProperty(order = 12,
            displayMessageKey = "logPayloads.display",
            helpMessageKey = "logPayloads.help")
    public boolean isLogPayloads() { return logPayloads; }
    public void setLogPayloads(boolean logPayloads) { this.logPayloads = logPayloads; }

    String decryptSecret() {
        return SecurityUtil.decrypt(clientSecret);
    }

    @Override
    public void validate() {
        Set<String> missing = new HashSet<>();
        if (StringUtil.isBlank(tenantId)) {
            missing.add("tenantId");
        }
        if (!useManagedIdentity) {
            if (StringUtil.isBlank(clientId)) {
                missing.add("clientId");
            }
            if (clientSecret == null) {
                missing.add("clientSecret");
            }
        }
        if (!missing.isEmpty()) {
            throw new ConfigurationException("Missing required configuration: " + String.join(", ", missing));
        }
    }

    @Override
    public void release() {
        // No stateful resources to release
    }
}
