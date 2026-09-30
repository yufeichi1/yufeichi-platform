package com.yufeichi.server.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiEmbeddingModelTest {
    private final EmbeddingModel delegate = mock(EmbeddingModel.class);
    private final AiEmbeddingModel model = new AiEmbeddingModel(delegate, 3, 100);
    @Test void dimensionsAreLocalAndValidResponsePassesWithoutProbe() {
        assertThat(model.dimensions()).isEqualTo(3);
        verifyNoInteractions(delegate);
        when(delegate.call(any())).thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[]{1, 0, 0}, 0))));
        assertThat(model.embed("公开测试文本")).containsExactly(1, 0, 0);
    }
    @Test void rejectsWrongDimensionNonFiniteAndZeroVectors() {
        for (float[] vector : List.of(new float[]{1, 0}, new float[]{Float.NaN, 0, 0}, new float[]{Float.POSITIVE_INFINITY, 0, 0}, new float[]{0, 0, 0})) {
            when(delegate.call(any())).thenReturn(new EmbeddingResponse(List.of(new Embedding(vector, 0))));
            assertThatThrownBy(() -> model.embed("正文")).isInstanceOf(AiProviderException.class).hasNoCause();
        }
    }
    @Test void rejectsMissingOrMisorderedResults() {
        when(delegate.call(any())).thenReturn(new EmbeddingResponse(List.of()));
        assertThatThrownBy(() -> model.embed("正文")).isInstanceOf(AiProviderException.class);
        when(delegate.call(any())).thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[]{1, 0, 0}, 1))));
        assertThatThrownBy(() -> model.embed("正文")).isInstanceOf(AiProviderException.class);
    }
    @Test void boundsInputBeforeProviderCall() {
        for (List<String> input : List.of(List.<String>of(), List.of(" "), List.of("x".repeat(101)), java.util.Collections.nCopies(21, "x")))
            assertThatThrownBy(() -> model.call(new EmbeddingRequest(input, null))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(delegate);
    }
    @Test void providerFailureCannotExposePrivateExceptionBody() {
        when(delegate.call(any())).thenThrow(new IllegalStateException("PRIVATE-EMBEDDING-KEY-AND-CONTENT"));
        assertThatThrownBy(() -> model.embed("正文")).isInstanceOf(AiProviderException.class).hasNoCause()
                .hasMessageNotContaining("PRIVATE");
    }
}
