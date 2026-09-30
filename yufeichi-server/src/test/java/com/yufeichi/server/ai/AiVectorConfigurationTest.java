package com.yufeichi.server.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AiVectorConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(AiConfiguration.class, AiVectorConfiguration.class);
    }
    @Test void aiOffOrVectorOffDoesNotNeedModelOrPostgres() {
        runner().withPropertyValues("app.ai.enabled=false", "app.ai.vector.enabled=true").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(AiVectorStore.class).doesNotHaveBean(AiEmbeddingModel.class));
        runner().withPropertyValues("app.ai.enabled=true", "app.ai.vector.enabled=false").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(AiVectorStore.class));
    }
    @Test void incompleteVectorConfigurationDoesNotCreateTransport() {
        runner().withPropertyValues("app.ai.enabled=true", "app.ai.vector.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(AiVectorStore.class);
            assertThat(context.containsBean("aiEmbeddingExecutor")).isFalse();
        });
    }
    @Test void independentLazyPoolDoesNotReplaceBusinessDataSourceOrRequireLivePostgres() {
        var business = mock(DataSource.class);
        runner().withBean("businessDataSource", DataSource.class, () -> business).withPropertyValues(
                "app.ai.enabled=true", "app.ai.vector.enabled=true", "app.ai.embedding.base-url=http://127.0.0.1:9/compatible-mode/v1",
                "app.ai.embedding.api-key=configuration-fixture", "app.ai.embedding.model=fixture-embedding",
                "app.ai.vector.url=jdbc:postgresql://127.0.0.1:9/ai_vector_test", "app.ai.vector.username=fixture", "app.ai.vector.password=fixture")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AiVectorStore.class).hasSingleBean(AiEmbeddingModel.class);
                    assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("businessDataSource");
                    assertThat(context.getBean(DataSource.class)).isSameAs(business);
                    assertThat(context).doesNotHaveBean(AiModelGateway.class);
                });
    }
    @Test void embeddingPathSupportsWorkspaceVersionPrefixAndRejectsUnsafeConfig() {
        var embedding = new AiProperties.Embedding();
        embedding.setBaseUrl("https://example.com/compatible-mode/v1/");
        embedding.setApiKey("fixture"); embedding.setModel("fixture");
        assertThat(embedding.effectivePath()).isEqualTo("/embeddings");
        assertThatCode(embedding::validate).doesNotThrowAnyException();
        embedding.setBaseUrl("https://example.com");
        assertThat(embedding.effectivePath()).isEqualTo("/v1/embeddings");
        embedding.setBaseUrl("http://example.com");
        assertThatThrownBy(embedding::validate).isInstanceOf(IllegalArgumentException.class);
        embedding.setDimensions(0);
        assertThatThrownBy(embedding::validate).isInstanceOf(IllegalArgumentException.class);
        var vector = new AiProperties.Vector();
        vector.setEnabled(true); vector.setUsername("fixture"); vector.setPassword("fixture"); vector.setUrl("jdbc:mysql://localhost/site");
        assertThatThrownBy(vector::validate).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void chatAndEmbeddingTransportsCanCoexistWithSeparateConfiguration() {
        runner().withPropertyValues("app.ai.enabled=true", "app.ai.vector.enabled=true",
                "app.ai.base-url=http://127.0.0.1:9", "app.ai.api-key=chat-fixture-key", "app.ai.chat-model=chat-fixture-model",
                "app.ai.embedding.base-url=http://127.0.0.1:9/compatible-mode/v1", "app.ai.embedding.api-key=embedding-fixture-key",
                "app.ai.embedding.model=embedding-fixture-model", "app.ai.vector.url=jdbc:postgresql://127.0.0.1:9/vector_test",
                "app.ai.vector.username=fixture", "app.ai.vector.password=fixture")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(AiModelGateway.class)
                        .hasSingleBean(AiEmbeddingModel.class).hasSingleBean(AiVectorStore.class));
    }
}
