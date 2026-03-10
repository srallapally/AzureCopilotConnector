// src/main/java/org/forgerock/openicf/connectors/m365copilot/utils/M365CopilotFilterTranslator.java
package org.forgerock.openicf.connectors.m365copilot.utils;

import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.AbstractFilterTranslator;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;

public class M365CopilotFilterTranslator extends AbstractFilterTranslator<String> {

    @Override
    protected String createEqualsExpression(EqualsFilter filter, boolean not) {
        if (not) {
            return null;
        }
        Attribute attr = filter.getAttribute();
        if (attr.is(Uid.NAME) || attr.is(Name.NAME)) {
            if (attr.getValue() != null && !attr.getValue().isEmpty()) {
                return attr.getValue().get(0).toString();
            }
        }
        return null;
    }
}
