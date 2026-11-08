package com.yufeichi.server.ai;

import com.yufeichi.server.common.error.ErrorCode;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** Validates the configured embedding contract before a vector can enter storage. */
public final class AiEmbeddingModel implements EmbeddingModel {
    private final EmbeddingModel delegate;
    private final int dimensions;
    private final int maxInputChars;
    private final AiCostGuard costs;
    public AiEmbeddingModel(EmbeddingModel delegate, int dimensions, int maxInputChars) {
        this(delegate, dimensions, maxInputChars, null);
    }
    public AiEmbeddingModel(EmbeddingModel delegate, int dimensions, int maxInputChars, AiCostGuard costs) {
        this.delegate = delegate;
        this.dimensions = dimensions;
        this.maxInputChars = maxInputChars;
        this.costs = costs;
    }
    @Override public int dimensions() { return dimensions; }
    @Override public float[] embed(Document document) { return embed(document.getText()); }
    @Override public EmbeddingResponse call(EmbeddingRequest request) {
        var inputs = request.getInstructions();
        if (inputs == null || inputs.isEmpty() || inputs.size() > 20 || inputs.stream().anyMatch(text ->
                text == null || text.isBlank() || text.length() > maxInputChars))
            throw new IllegalArgumentException("Invalid bounded embedding input");
        var reservation = costs == null ? null : costs.reserve(true, 8192L + inputs.stream().mapToLong(text ->
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum(), 0, AiCallTelemetry.current());
        boolean success = false;
        try {
            // The configured model/dimensions remain authoritative, not a caller's options.
            var response = delegate.call(new EmbeddingRequest(inputs, null));
            if (response == null || response.getResults().size() != inputs.size()) throw invalid();
            for (int i = 0; i < inputs.size(); i++) {
                var embedding = response.getResults().get(i);
                var vector = embedding.getOutput();
                if (embedding.getIndex() != i || vector == null || vector.length != dimensions) throw invalid();
                double norm = 0;
                for (float value : vector) {
                    if (!Float.isFinite(value)) throw invalid();
                    norm += (double) value * value;
                }
                if (norm == 0) throw invalid();
            }
            if (reservation != null) reservation.usage(response.getMetadata().getUsage());
            success = true;
            return response;
        } catch (AiProviderException safe) { throw safe; }
        catch (RuntimeException privateFailure) { throw new AiProviderException(ErrorCode.AI_UNAVAILABLE, false); }
        finally { if (reservation != null) reservation.finish(success ? "ON_COMPLETE" : "ON_ERROR"); }
    }
    private static AiProviderException invalid() { return new AiProviderException(ErrorCode.AI_INVALID_RESPONSE, false); }
}
