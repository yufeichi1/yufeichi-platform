package com.yufeichi.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import com.yufeichi.server.common.result.PageResult;
import com.yufeichi.server.vo.ArticleSummaryVO;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SiteReadWorkflowTest {
    final SiteReadToolsTest fixture=new SiteReadToolsTest();
    final AiProperties properties=new AiProperties();
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final AiRequestGuard guard=new AiRequestGuard(redis,properties);
    final ObjectMapper json=new ObjectMapper();
    final SiteReadWorkflow workflow=new SiteReadWorkflow(new SiteReadToolCatalog(fixture.tools,json),fixture.tools,properties,guard,json);
    void setup() {
        properties.setEnabled(true);properties.setToolsEnabled(true);
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L);
        var summary=new ArticleSummaryVO(1L,"Java标题","公开摘要",null,null,777L,1,0,0,0L,null,null,null);
        when(fixture.articles.list(any(),eq(true))).thenReturn(new PageResult<>(List.of(summary),1,1,3));
    }
    @Test void sourceHiddenBetweenSearchAndCompletionDiscardsAllEarlierPreviews() {
        setup();when(fixture.articles.detail(1,true)).thenReturn(fixture.article("公开正文"))
                .thenThrow(new BusinessException(ErrorCode.ARTICLE_NOT_FOUND));
        var result=workflow.query(new ToolQueryRequest("Java",ToolQueryRequest.Scope.ARTICLE,3),1);
        assertThat(result.matches()).isEmpty();assertThat(result.details()).isEmpty();assertThat(result.message()).contains("内容已变化");
        assertThat(result.steps()).hasSize(2);verifyNoInteractions(fixture.projects);
    }
    @Test void sourceBodyChangedDuringCompletionCannotReleaseAnOldExcerpt() {
        setup();when(fixture.articles.detail(1,true)).thenReturn(fixture.article("旧正文"),fixture.article("新正文"),fixture.article("新正文"));
        var result=workflow.query(new ToolQueryRequest("Java",ToolQueryRequest.Scope.ARTICLE,3),1);
        assertThat(result.details()).isEmpty();assertThat(result.matches()).isEmpty();
    }
    @Test void subsequentEmptyRequestHasNoPreviousResultsOrSteps() {
        setup();when(fixture.articles.detail(1,true)).thenReturn(fixture.article("公开正文"));
        var first=workflow.query(new ToolQueryRequest("Java",ToolQueryRequest.Scope.ARTICLE,3),1);
        when(fixture.articles.list(any(),eq(true))).thenReturn(new PageResult<>(List.of(),0,1,3));
        var second=workflow.query(new ToolQueryRequest("无结果",ToolQueryRequest.Scope.ARTICLE,3),1);
        assertThat(first.details()).hasSize(1);assertThat(second.details()).isEmpty();assertThat(second.steps()).hasSize(1);
        assertThat(first.requestId()).isNotEqualTo(second.requestId());
    }
}
