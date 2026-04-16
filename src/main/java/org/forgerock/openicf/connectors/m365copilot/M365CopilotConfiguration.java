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
    private String environmentUrl;
    private String clientId;
    private GuardedString clientSecret;
    // OPENICF-5001 begin: removed useManagedIdentity field — managed identity is not implemented; only client credentials are supported
    // OPENICF-5001 end
    private String toolsInventoryUrl;
    private String toolsInventoryFilePath;
    private int httpTimeoutSeconds = 30;
    private boolean logPayloads = false;
    // OPENICF-5005 begin: flag to gate agentIdentityBinding scanning; default false — operators must opt in
    private boolean identityBindingScanEnabled = false;
    // OPENICF-5005 end

    @ConfigurationProperty(order = 1,
            displayMessageKey = "tenantId.display",
            helpMessageKey = "tenantId.help",
            required = true)
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    @ConfigurationProperty(order = 2,
            displayMessageKey = "environmentUrl.display",
            helpMessageKey = "environmentUrl.help",
            required = true)
    public String getEnvironmentUrl() { return environmentUrl; }
    public void setEnvironmentUrl(String environmentUrl) { this.environmentUrl = environmentUrl; }

    @ConfigurationProperty(order = 3,
            displayMessageKey = "clientId.display",
            helpMessageKey = "clientId.help")
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    @ConfigurationProperty(order = 4,
            displayMessageKey = "clientSecret.display",
            helpMessageKey = "clientSecret.help",
            confidential = true)
    public GuardedString getClientSecret() { return clientSecret; }
    public void setClientSecret(GuardedString clientSecret) { this.clientSecret = clientSecret; }

    // OPENICF-5001 begin: removed useManagedIdentity @ConfigurationProperty, getter, and setter — property deleted (managed identity not implemented)
    // OPENICF-5001 end

    @ConfigurationProperty(order = 6,
            displayMessageKey = "toolsInventoryUrl.display",
            helpMessageKey = "toolsInventoryUrl.help")
    public String getToolsInventoryUrl() { return toolsInventoryUrl; }
    public void setToolsInventoryUrl(String toolsInventoryUrl) { this.toolsInventoryUrl = toolsInventoryUrl; }

    @ConfigurationProperty(order = 7,
            displayMessageKey = "toolsInventoryFilePath.display",
            helpMessageKey = "toolsInventoryFilePath.help")
    public String getToolsInventoryFilePath() { return toolsInventoryFilePath; }
    public void setToolsInventoryFilePath(String toolsInventoryFilePath) { this.toolsInventoryFilePath = toolsInventoryFilePath; }

    @ConfigurationProperty(order = 8,
            displayMessageKey = "httpTimeoutSeconds.display",
            helpMessageKey = "httpTimeoutSeconds.help")
    public int getHttpTimeoutSeconds() { return httpTimeoutSeconds; }
    public void setHttpTimeoutSeconds(int httpTimeoutSeconds) { this.httpTimeoutSeconds = httpTimeoutSeconds; }

    @ConfigurationProperty(order = 9,
            displayMessageKey = "logPayloads.display",
            helpMessageKey = "logPayloads.help")
    public boolean isLogPayloads() { return logPayloads; }
    public void setLogPayloads(boolean logPayloads) { this.logPayloads = logPayloads; }

    // OPENICF-5005 begin
    @ConfigurationProperty(order = 10,
            displayMessageKey = "identityBindingScanEnabled.display",
            helpMessageKey = "identityBindingScanEnabled.help")
    public boolean isIdentityBindingScanEnabled() { return identityBindingScanEnabled; }
    public void setIdentityBindingScanEnabled(boolean identityBindingScanEnabled) {
        this.identityBindingScanEnabled = identityBindingScanEnabled;
    }
    // OPENICF-5005 end

    String decryptSecret() {
        return SecurityUtil.decrypt(clientSecret);
    }

    @Override
    public void validate() {
        Set<String> missing = new HashSet<>();
        if (StringUtil.isBlank(tenantId)) {
            missing.add("tenantId");
        }
        if (StringUtil.isBlank(environmentUrl)) {
            missing.add("environmentUrl");
        }
        // OPENICF-5001 begin: clientId and clientSecret are now unconditionally required (useManagedIdentity removed)
        if (StringUtil.isBlank(clientId)) {
            missing.add("clientId");
        }
        if (clientSecret == null) {
            missing.add("clientSecret");
        }
        // OPENICF-5001 end
        if (!missing.isEmpty()) {
            throw new ConfigurationException("Missing required configuration: " + String.join(", ", missing));
        }
    }

    @Override
    public void release() {
        // No stateful resources to release
    }
}