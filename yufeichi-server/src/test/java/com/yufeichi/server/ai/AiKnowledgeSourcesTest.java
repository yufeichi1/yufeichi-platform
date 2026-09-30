package com.yufeichi.server.ai;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiKnowledgeSourcesTest {
    @Test void paragraphSplittingPreservesTextAndUnicodeAtBoundaries() {
        String input = "短段\n\n" + "字".repeat(1023) + "😀结尾\n\n下一段";
        var pieces = AiKnowledgeSources.split(input);
        assertThat(pieces).allSatisfy(piece -> {
            assertThat(piece.length()).isLessThanOrEqualTo(1024);
            assertThat(Character.isHighSurrogate(piece.charAt(piece.length() - 1))).isFalse();
        });
        assertThat(String.join("", pieces).replace("\n", "")).isEqualTo(input.replace("\n", ""));
        assertThat(AiKnowledgeSources.split(" \n\n ")).isEmpty();
    }
}
