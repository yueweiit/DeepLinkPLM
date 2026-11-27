package com.yuewei.plm.module.integration.crm.moldschedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuewei.plm.module.integration.crm.moldschedule.config.CrmMoldScheduleProperties;
import org.junit.jupiter.api.Test;

class CrmMoldSchedulePropertiesTest {
    @Test
    void failsClosedUntilEnabledWithAHostOnlyHttpsEndpointAndCredentials() {
        CrmMoldScheduleProperties properties = new CrmMoldScheduleProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isConfigured()).isFalse();

        properties.setEnabled(true);
        properties.setEndpointUrl("https://crm.example.test");
        properties.setKeyId("plm-test-01");
        properties.setSecret("test-secret");
        assertThat(properties.isConfigured()).isTrue();

        properties.setEndpointUrl("http://crm.example.test");
        assertThat(properties.isConfigured()).isFalse();
        properties.setEndpointUrl("https://crm.example.test/api");
        assertThat(properties.isConfigured()).isFalse();
        properties.setEndpointUrl("https://crm.example.test");
        properties.setPath("/api/integrations/events?mode=1");
        assertThat(properties.isConfigured()).isFalse();
    }
}
