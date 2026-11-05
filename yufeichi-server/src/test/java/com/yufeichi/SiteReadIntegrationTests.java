package com.yufeichi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yufeichi.server.YufeichiServerApplication;
import com.yufeichi.server.ai.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=YufeichiServerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"app.ai.enabled=true","app.ai.tools-enabled=true","app.ai.base-url=","app.ai.api-key=","app.ai.chat-model="})
@ActiveProfiles("test") @Testcontainers
class SiteReadIntegrationTests {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4").withDatabaseName("site_read_test").withUsername("reader_test").withPassword("isolated-read-test-password");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7").withExposedPorts(6379);
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path uploads;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));r.add("spring.data.redis.password",()->"");
        r.add("file.upload-path",()->uploads.toString());
    }
    @Autowired JdbcTemplate jdbc; @Autowired TestRestTemplate http; @Autowired SiteReadTools tools;
    @Autowired SiteReadWorkflow workflow; @Autowired SiteReadToolCatalog catalog; @Autowired AiProperties properties;
    @Autowired StringRedisTemplate redis; @Autowired ObjectMapper json;
    String token;
    @BeforeEach void fixtures() {
        properties.setEnabled(true);properties.setToolsEnabled(true);properties.setPublicEnabled(false);
        var keys=redis.keys("ai:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
        jdbc.update("UPDATE sys_user SET status=1 WHERE id=1");
        jdbc.update("INSERT IGNORE INTO sys_user(id,username,password,status) SELECT 801,'tools-reader',password,1 FROM sys_user WHERE id=1");
        jdbc.update("DELETE FROM blog_article_tag");jdbc.update("DELETE FROM blog_article");jdbc.update("DELETE FROM project");
        jdbc.update("INSERT INTO blog_article(id,title,summary,content,author_id,status,deleted) VALUES(101,'Java公开文章','公开摘要','Java21业务正文',1,1,0),(102,'Java私密草稿','DRAFT_PREVIEW','DRAFT_SECRET',1,0,0),(103,'Java已下架','HIDDEN_PREVIEW','HIDDEN_SECRET',1,2,0),(104,'Java已删除','DELETED_PREVIEW','DELETED_SECRET',1,1,1)");
        jdbc.update("INSERT INTO project(id,name,description,tech_stack,status,deleted,github_url) VALUES(201,'Java公开项目','Java项目正文','Java,MySQL',1,0,'https://external.invalid'),(202,'Java隐藏项目','PROJECT_SECRET',NULL,0,0,NULL),(203,'Java删除项目','DELETED_PROJECT_SECRET',NULL,1,1,NULL)");
        var login=http.postForEntity("/api/auth/login",Map.of("username","tools-reader","password","Admin@123456"),JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);token=login.getBody().path("data").path("token").asText();
    }
    ResponseEntity<String> post(Object body,String bearer) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(bearer!=null)headers.setBearerAuth(bearer);
        return http.exchange("/api/ai/tools/query",HttpMethod.POST,new HttpEntity<>(body,headers),String.class);
    }
    @Test void fourActualToolsExcludePrivateDeletedAndHiddenRecords() throws Exception {
        assertThat(tools.searchArticles("Java",5).items()).extracting(SiteReadTools.Item::sourceId).containsExactly(101L);
        assertThat(tools.searchProjects("Java",5).items()).extracting(SiteReadTools.Item::sourceId).containsExactly(201L);
        for(long id:List.of(102L,103L,104L,999L))assertThat(tools.getArticleDetail(id).detail()).isNull();
        for(long id:List.of(202L,203L,999L))assertThat(tools.getProjectDetail(id).detail()).isNull();
        String output=catalog.named("getProjectDetail").call("{\"id\":201}");
        assertThat(json.readTree(output).path("detail").has("status")).isFalse();
        assertThat(output).contains("/projects/201").doesNotContain("external.invalid","githubUrl","authorId");
    }
    @Test void authenticatedReaderWorkflowUsesFourStepsWithoutModelOrVectorOrPaidBudget() throws Exception {
        var response=post(Map.of("keyword","Java","scope","ALL","limit",3),token);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data=json.readTree(response.getBody()).path("data");assertThat(data.path("mode").asText()).isEqualTo("fixed-query");
        assertThat(data.path("steps")).hasSize(4);assertThat(data.path("details")).hasSize(2);assertThat(data.path("matches")).hasSize(2);
        assertThat(response.getBody()).doesNotContain("SECRET","PREVIEW","external.invalid","password","authorId");
        assertThat(redis.keys("ai:quota:global:*")).isEmpty();assertThat(redis.keys("ai:quota:index:*")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_index_job",Integer.class)).isZero();
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/ai-day10-workflow-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(data));
    }
    @Test void emptyResultIsExplainedWithoutCallingDetails() {
        var result=workflow.query(new ToolQueryRequest("不存在的主题",ToolQueryRequest.Scope.ALL,3),801);
        assertThat(result.matches()).isEmpty();assertThat(result.details()).isEmpty();assertThat(result.steps()).hasSize(2);
        assertThat(result.message()).contains("未找到");
    }
    @Test void explicitScopeUsesOnlyTwoAppropriateSteps() {
        var result=workflow.query(new ToolQueryRequest("Java",ToolQueryRequest.Scope.PROJECT,3),801);
        assertThat(result.steps()).extracting(SiteReadWorkflow.Step::tool).containsExactly("searchProjects","getProjectDetail");
        assertThat(result.details().getFirst().url()).isEqualTo("/projects/201");
    }
    @Test void anonymousIsDeniedEvenWhenRagPublicSwitchIsEnabled() {
        properties.setPublicEnabled(true);
        assertThat(post(Map.of("keyword","Java","scope","ALL","limit",3),null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/api/ai/tools/status",String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void illegalHttpInputAndInjectedControlParametersAreRejected() {
        for(Object body:List.of(Map.of(),Map.of("keyword","","scope","ALL","limit",3),Map.of("keyword","x".repeat(81),"scope","ALL","limit",3),
                Map.of("keyword","%","scope","ALL","limit",3),Map.of("keyword","Java","scope","ALL","limit",6),Map.of("keyword","Java","scope","SQL","limit",3),
                Map.of("keyword","Java","scope","ALL","limit",1.5),Map.of("keyword","Java","scope","ALL","limit",3,"publicOnly",false)))
            assertThat(post(body,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(redis.keys("ai:quota:tools:*")).isEmpty();
    }
    @Test void sqlLookingKeywordIsLiteralAndUrlInputCannotTriggerNetworkOrWrites() {
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM sys_user",Long.class);
        assertThat(tools.searchArticles("Java' OR 1=1 --",3).items()).isEmpty();
        assertThat(tools.searchProjects("https://external.invalid/private",3).items()).isEmpty();
        assertThatThrownBy(()->catalog.named("executeSql")).isInstanceOf(com.yufeichi.server.common.error.BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user",Long.class)).isEqualTo(before);
    }
    @Test void hiddenOrChangedOriginalNoLongerReturnsOldPublicDetail() {
        var before=tools.getArticleDetail(101L).detail();jdbc.update("UPDATE blog_article SET content='新版本正文' WHERE id=101");
        var updated=tools.getArticleDetail(101L).detail();assertThat(updated.contentHash()).isNotEqualTo(before.contentHash());assertThat(updated.text()).contains("新版本");
        jdbc.update("UPDATE blog_article SET status=2 WHERE id=101");assertThat(tools.getArticleDetail(101L).detail()).isNull();
        jdbc.update("UPDATE project SET deleted=1 WHERE id=201");assertThat(tools.getProjectDetail(201L).detail()).isNull();
    }
    @Test void toolsSwitchAndMasterSwitchBothCloseWorkflowAndCoreSiteStaysAvailable() {
        properties.setToolsEnabled(false);assertThat(post(Map.of("keyword","Java","scope","ALL","limit",3),token).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        properties.setToolsEnabled(true);properties.setEnabled(false);assertThat(post(Map.of("keyword","Java","scope","ALL","limit",3),token).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(http.getForEntity("/api/articles/101",String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/health",String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void toolsQuotaIsBoundedWithoutChargingProviderBudget() {
        for(int i=0;i<10;i++)assertThat(post(Map.of("keyword","空结果","scope","ALL","limit",1),token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(Map.of("keyword","Java","scope","ALL","limit",1),token).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(redis.keys("ai:quota:global:*")).isEmpty();
    }
}
