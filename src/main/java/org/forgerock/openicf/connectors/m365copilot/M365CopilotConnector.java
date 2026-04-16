// src/main/java/org/forgerock/openicf/connectors/m365copilot/M365CopilotConnector.java
package org.forgerock.openicf.connectors.m365copilot;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.forgerock.openicf.connectors.m365copilot.client.M365CopilotClient;
import org.forgerock.openicf.connectors.m365copilot.operations.M365CopilotCrudService;
import org.forgerock.openicf.connectors.m365copilot.utils.M365CopilotFilterTranslator;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.objects.AttributeInfoBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.ObjectClassInfoBuilder;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.SchemaBuilder;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.PoolableConnector;
import org.identityconnectors.framework.spi.operations.SchemaOp;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.TestOp;

import static org.forgerock.openicf.connectors.m365copilot.utils.M365CopilotConstants.*;

@ConnectorClass(
        displayNameKey = "connector.display",
        configurationClass = M365CopilotConfiguration.class)
public class M365CopilotConnector implements
        PoolableConnector,
        SchemaOp,
        TestOp,
        SearchOp<String> {

    private static final Log LOG = Log.getLog(M365CopilotConnector.class);

    private M365CopilotConfiguration cfg;
    private M365CopilotClient client;
    private M365CopilotCrudService crudService;
    private Schema schema;

    @Override
    public void init(Configuration configuration) {
        this.cfg = (M365CopilotConfiguration) configuration;
        this.client = new M365CopilotClient(cfg);
        this.crudService = new M365CopilotCrudService(client, cfg);
        LOG.ok("M365CopilotConnector initialized for environment {0}", cfg.getEnvironmentUrl());
    }

    @Override
    public void dispose() {
        if (client != null) {
            client.close();
            client = null;
        }
        cfg = null;
        crudService = null;
        schema = null;
    }

    @Override
    public void checkAlive() {
        // No-op — HTTP connections are stateless
    }

    @Override
    public Configuration getConfiguration() {
        return cfg;
    }

    @Override
    public void test() {
        try {
            client.testConnection();
        } catch (Exception e) {
            throw new ConnectorException("Test failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Schema schema() {
        if (this.schema != null) {
            return this.schema;
        }

        SchemaBuilder sb = new SchemaBuilder(M365CopilotConnector.class);

        ObjectClassInfo accountOci = buildAccountSchema();
        ObjectClassInfo agentToolOci = buildAgentToolSchema();
        ObjectClassInfo agentKnowledgeBaseOci = buildAgentKnowledgeBaseSchema();
        ObjectClassInfo agentIdentityBindingOci = buildAgentIdentityBindingSchema();

        sb.defineObjectClass(accountOci);
        sb.defineObjectClass(agentToolOci);
        sb.defineObjectClass(agentKnowledgeBaseOci);
        sb.defineObjectClass(agentIdentityBindingOci);

        sb.clearSupportedObjectClassesByOperation();

        Set<Class<? extends org.identityconnectors.framework.spi.operations.SPIOperation>> readOps =
                Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                        SchemaOp.class, TestOp.class, SearchOp.class)));

        for (Class<? extends org.identityconnectors.framework.spi.operations.SPIOperation> op : readOps) {
            sb.addSupportedObjectClass(op, accountOci);
            sb.addSupportedObjectClass(op, agentToolOci);
            sb.addSupportedObjectClass(op, agentKnowledgeBaseOci);
            sb.addSupportedObjectClass(op, agentIdentityBindingOci);
        }

        this.schema = sb.build();
        return this.schema;
    }

    @Override
    public FilterTranslator<String> createFilterTranslator(ObjectClass objectClass, OperationOptions options) {
        return new M365CopilotFilterTranslator();
    }

    @Override
    public void executeQuery(ObjectClass objectClass, String query, ResultsHandler handler, OperationOptions options) {
        String ocName = objectClass.getObjectClassValue();

        if (ObjectClass.ACCOUNT_NAME.equals(ocName)) {
            crudService.searchAgents(query, handler, options);
        } else if (OC_AGENT_TOOL.equals(ocName)) {
            crudService.searchTools(query, handler, options);
        } else if (OC_AGENT_KNOWLEDGE_BASE.equals(ocName)) {
            crudService.searchKnowledgeBases(query, handler, options);
        } else if (OC_AGENT_IDENTITY_BINDING.equals(ocName)) {
            crudService.searchIdentityBindings(query, handler, options);
        } else {
            throw new ConnectorException("Unsupported object class: " + ocName);
        }
    }

    // --- Schema builders ---

    private ObjectClassInfo buildAccountSchema() {
        ObjectClassInfoBuilder ocib = new ObjectClassInfoBuilder();
        ocib.setType(ObjectClass.ACCOUNT_NAME);

        ocib.addAttributeInfo(readOnly(ATTR_PLATFORM, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_AGENT_ID, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_STATECODE, Integer.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_STATUSCODE, Integer.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_ACCESS_CONTROL_POLICY, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_AUTHENTICATION_MODE, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_RUNTIME_PROVIDER, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_LANGUAGE, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_SCHEMA_NAME, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_PUBLISHED_ON, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_CREATED_ON, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_MODIFIED_ON, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_TOOL_IDS, String.class, true));
        ocib.addAttributeInfo(readOnly(ATTR_KNOWLEDGE_BASE_IDS, String.class, true));
        // OPENICF-5007 begin: renamed schema attribute from connectedAgents to connectedAgentReferences
        ocib.addAttributeInfo(readOnly(ATTR_CONNECTED_AGENT_REFERENCES, String.class, true));
        // OPENICF-5007 end
        ocib.addAttributeInfo(readOnly(ATTR_CONTENT_MODERATION, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_GENERATIVE_ACTIONS_ENABLED, Boolean.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_USE_MODEL_KNOWLEDGE, Boolean.class, false));

        return ocib.build();
    }

    private ObjectClassInfo buildAgentToolSchema() {
        ObjectClassInfoBuilder ocib = new ObjectClassInfoBuilder();
        ocib.setType(OC_AGENT_TOOL);

        ocib.addAttributeInfo(readOnly(ATTR_PLATFORM, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_AGENT_ID, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_TOOL_TYPE, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_CONNECTION_REFERENCE, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_OPERATION_ID, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_DESCRIPTION, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_SCHEMA_NAME, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_CREATED_ON, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_MODIFIED_ON, String.class, false));

        return ocib.build();
    }

    private ObjectClassInfo buildAgentKnowledgeBaseSchema() {
        ObjectClassInfoBuilder ocib = new ObjectClassInfoBuilder();
        ocib.setType(OC_AGENT_KNOWLEDGE_BASE);

        ocib.addAttributeInfo(readOnly(ATTR_PLATFORM, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_AGENT_ID, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_SCHEMA_NAME, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_DESCRIPTION, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_CREATED_ON, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_MODIFIED_ON, String.class, false));

        return ocib.build();
    }

    private ObjectClassInfo buildAgentIdentityBindingSchema() {
        ObjectClassInfoBuilder ocib = new ObjectClassInfoBuilder();
        ocib.setType(OC_AGENT_IDENTITY_BINDING);

        ocib.addAttributeInfo(readOnly(ATTR_PLATFORM, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_AGENT_ID, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_AGENT_VERSION, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_KIND, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_PRINCIPAL, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_PERMISSIONS, String.class, true));
        ocib.addAttributeInfo(readOnly(ATTR_SCOPE, String.class, false));
        ocib.addAttributeInfo(readOnly(ATTR_SCOPE_RESOURCE_ID, String.class, false));

        return ocib.build();
    }

    private static org.identityconnectors.framework.common.objects.AttributeInfo readOnly(
            String name, Class<?> type, boolean multiValued) {
        return AttributeInfoBuilder.define(name)
                .setType(type)
                .setMultiValued(multiValued)
                .setCreateable(false)
                .setUpdateable(false)
                .build();
    }
}