package com.yufeichi.server.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;

class AiConfigurationTest {
    @Test void officialDeepSeekUsesNonThinkingUnlessExplicitlyOverridden() {
        var properties = new AiProperties();
        properties.setBaseUrl("https://api.deepseek.com");
        assertThat(properties.effectiveReasoningEffort()).isEqualTo("none");
        properties.setReasoningEffort("low");
        assertThat(properties.effectiveReasoningEffort()).isEqualTo("low");
        properties.setReasoningEffort("");
        properties.setBaseUrl("https://example.com");
        assertThat(properties.effectiveReasoningEffort()).isNull();
        properties.setReasoningEffort("invalid");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disabledAiNeedsNeitherKeyNorProviderAndStartsWithoutNetwork() {
        new ApplicationContextRunner().withUserConfiguration(AiConfiguration.class)
                .withPropertyValues("app.ai.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(AiModelGateway.class);
                    assertThat(context.getBean(AiProperties.class).isEnabled()).isFalse();
                });
    }

    @Test void missingEnabledConfigurationKeepsCoreStartupAvailableWithoutTransport() {
        new ApplicationContextRunner().withUserConfiguration(AiConfiguration.class)
                .withPropertyValues("app.ai.enabled=true", "app.ai.api-key=PRIVATE-TEST-VALUE")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(AiModelGateway.class);
                    assertThat(context.containsBean("aiHttpExecutor")).isFalse();
                });
    }

    @Test void disallowsUnsafeProviderUrls() {
        var properties = new AiProperties();
        properties.setEnabled(true);
        properties.setApiKey("unit-test-key");
        properties.setChatModel("unit-test-model");
        for (String url : new String[]{"http://example.com", "https://key:password@example.com", "https://example.com?key=x"}) {
            properties.setBaseUrl(url);
            assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        }
        properties.setBaseUrl("https://example.com");
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}
