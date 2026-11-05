package com.yufeichi.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import com.yufeichi.server.common.result.PageResult;
import com.yufeichi.server.service.ArticleService;
import com.yufeichi.server.service.ProjectService;
import com.yufeichi.server.vo.ArticleVO;
import com.yufeichi.server.vo.ArticleSummaryVO;
import com.yufeichi.server.vo.ProjectVO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SiteReadToolsTest {
    final ArticleService articles = mock(ArticleService.class);
    final ProjectService projects = mock(ProjectService.class);
    final SiteReadTools tools = new SiteReadTools(articles, projects);
    final ObjectMapper json = new ObjectMapper();
    ArticleVO article(String body) {
        return new ArticleVO(1L,"Java标题","公开摘要",body,"https://external.invalid",null,777L,1,0,0,100L,null,null,null,List.of(),List.of());
    }
    @Test void catalogExposesExactlyFourBoundedSchemasAndActualSpringAiCallbacks() throws Exception {
        var callbacks = new SiteReadToolCatalog(tools,json).getToolCallbacks();
        assertThat(callbacks).extracting(c->c.getToolDefinition().name()).containsExactlyInAnyOrder("searchArticles","getArticleDetail","searchProjects","getProjectDetail");
        var evidence=json.createArrayNode();
        for(var callback:callbacks) {
            var schema=json.readTree(callback.getToolDefinition().inputSchema());
            evidence.addObject().put("name",callback.getToolDefinition().name()).put("description",callback.getToolDefinition().description()).set("inputSchema",schema);
            assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
            if(callback.getToolDefinition().name().startsWith("search")) {
                assertThat(schema.path("properties").path("keyword").path("maxLength").asInt()).isEqualTo(80);
                assertThat(schema.path("properties").path("limit").path("maximum").asInt()).isEqualTo(5);
            } else assertThat(schema.path("properties").path("id").path("minimum").asLong()).isEqualTo(1);
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/ai-day10-tool-schemas.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        when(articles.detail(1,true)).thenReturn(article("公开正文"));
        var result=json.readTree(new SiteReadToolCatalog(tools,json).named("getArticleDetail").call("{\"id\":1}"));
        assertThat(result.path("detail").path("url").asText()).isEqualTo("/articles/1");
        assertThat(result.toString()).doesNotContain("authorId","777","external.invalid","viewCount","status\":1");
    }
    @Test void callbackRejectsUnknownMissingDuplicateTrailingAndWrongTypeArgumentsBeforeBusinessQuery() {
        var catalog=new SiteReadToolCatalog(tools,json);
        for(String input:List.of("{}","{\"id\":0}","{\"id\":-1}","{\"id\":1.5}","{\"id\":\"1\"}","{\"id\":null}","{\"id\":9007199254740992}",
                "{\"id\":1,\"publicOnly\":false}","{\"id\":1,\"id\":2}","{\"id\":1}{}"))
            assertThatThrownBy(()->catalog.named("getArticleDetail").call(input)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(40000));
        for(String input:List.of("{\"keyword\":\"Java\"}","{\"keyword\":\"\",\"limit\":1}","{\"keyword\":\"%\",\"limit\":1}","{\"keyword\":\"Java\",\"limit\":6}",
                "{\"keyword\":\"Java\",\"limit\":1.5}","{\"keyword\":\"Java\",\"limit\":\"1\"}","{\"keyword\":\"Java\",\"limit\":1,\"sql\":\"DROP TABLE\"}"))
            assertThatThrownBy(()->catalog.named("searchArticles").call(input)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->catalog.named("deleteArticle")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(articles,projects);
    }
    @Test void directToolsAlsoRejectInvalidParametersWithoutRelyingOnSchema() {
        for(String value:List.of(" ","x".repeat(81),"a%","a_","a\\b","a\nb"))
            assertThatThrownBy(()->tools.searchArticles(value,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->tools.searchProjects(null,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->tools.searchArticles("Java",null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->tools.getProjectDetail(null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(articles,projects);
    }
    @Test void searchAlwaysUsesPublicServicesFirstPageAndBoundedLimit() {
        when(articles.list(any(),eq(true))).thenReturn(new PageResult<ArticleSummaryVO>(List.of(),0,1,5));
        when(projects.list(any(),eq(true))).thenReturn(new PageResult<ProjectVO>(List.of(),0,1,5));
        assertThat(tools.searchArticles(" Java ",5).status()).isEqualTo("NOT_FOUND"); tools.searchProjects("Java",3);
        verify(articles).list(argThat(q->q.getPageNum()==1 && q.getPageSize()==5 && q.getKeyword().equals("Java") && q.getStatus()==null),eq(true));
        verify(projects).list(argThat(q->q.getPageSize()==3 && q.getStatus()==null),eq(true));
    }
    @Test void detailIsBoundedOnUnicodeBoundaryWithVersionHashAndNoExternalFields() {
        when(articles.detail(1,true)).thenReturn(article("中".repeat(1992)+"😀"+"尾".repeat(500)));
        var detail=tools.getArticleDetail(1L).detail();
        assertThat(detail.text()).hasSizeLessThanOrEqualTo(2000); assertThat(detail.truncated()).isTrue();
        assertThat(Character.isHighSurrogate(detail.text().charAt(detail.text().length()-1))).isFalse();
        assertThat(detail.contentHash()).matches("[a-f0-9]{64}");
        when(articles.detail(1,true)).thenReturn(article("更新正文")); assertThat(tools.current(detail)).isFalse();
    }
    @Test void missingAndHiddenAreNormalNoResultButDatabaseFailureIsNotDisguised() {
        when(articles.detail(1,true)).thenThrow(new BusinessException(ErrorCode.ARTICLE_NOT_FOUND));
        assertThat(tools.getArticleDetail(1L).status()).isEqualTo("NOT_FOUND");
        when(projects.detail(1,true)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private-db-failure"));
        assertThatThrownBy(()->tools.getProjectDetail(1L)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
