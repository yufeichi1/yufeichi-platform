package com.yufeichi;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AiEvaluationRubricTest {
    @Test void numericSubstringOrContradictionCannotPass() {
        assertThat(AiEvaluationRubric.facts("A01","摘要最多500字符。")).isTrue();
        for(String wrong:new String[]{"摘要最多5000字符。","摘要不是500字符。","摘要500字符或5000字符。","摘要最多1500字符。"})
            assertThat(AiEvaluationRubric.facts("A01",wrong)).as(wrong).isFalse();
        assertThat(AiEvaluationRubric.facts("A04","每天1000次")).isFalse();
        assertThat(AiEvaluationRubric.facts("A05","保留17份")).isFalse();
        assertThat(AiEvaluationRubric.facts("A18","Java 210")).isFalse();
    }
    @Test void negatedKeywordsAreNotSemanticCorrectness() {
        assertThat(AiEvaluationRubric.facts("A11","不会自动发布，需要作者手动保存文章。")).isTrue();
        assertThat(AiEvaluationRubric.facts("A11","手动或自动都行，会自动发布文章。")).isFalse();
        assertThat(AiEvaluationRubric.facts("A13","不会回滚已经保存的文章。")).isTrue();
        assertThat(AiEvaluationRubric.facts("A13","不会丢日志，但会回滚已经保存的文章。")).isFalse();
        assertThat(AiEvaluationRubric.facts("A14","失败也计入配额。")).isTrue();
        assertThat(AiEvaluationRubric.facts("A14","失败调用不计数。")).isFalse();
    }
    @Test void citationMustSupportTheQuestionAndNoUnrelatedReferenceCanHideBehindIt() throws Exception {
        var expected=new com.fasterxml.jackson.databind.ObjectMapper().readTree("[\"article:1301\"]");
        assertThat(AiEvaluationRubric.sources(expected,java.util.List.of("article:1301"))).isTrue();
        assertThat(AiEvaluationRubric.sources(expected,java.util.List.of("article:1301","project:1401"))).isFalse();
        assertThat(AiEvaluationRubric.sources(expected,java.util.List.of())).isFalse();
    }
}
