package com.yufeichi;

import com.fasterxml.jackson.databind.*;
import com.yufeichi.server.YufeichiServerApplication;
import com.yufeichi.server.ai.*;
import com.yufeichi.server.common.error.ErrorCode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.springframework.ai.chat.messages.*;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes={YufeichiServerApplication.class,AiAgentIntegrationTests.ModelFixture.class},webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"app.ai.enabled=true","app.ai.tools-enabled=true","app.ai.agent-enabled=true","app.ai.base-url=","app.ai.api-key=","app.ai.chat-model=","app.ai.request-timeout-seconds=2","app.ai.connect-timeout-seconds=1","app.ai.heartbeat-seconds=1"})
@ActiveProfiles("test") @Testcontainers
class AiAgentIntegrationTests {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4").withDatabaseName("agent_test").withUsername("agent_test").withPassword(UUID.randomUUID().toString());
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7").withExposedPorts(6379);
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path uploads;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));r.add("spring.data.redis.password",()->"");r.add("file.upload-path",()->uploads.toString());
    }
    static final AtomicInteger calls=new AtomicInteger();static final AtomicBoolean cancelled=new AtomicBoolean();static volatile String mode="normal";
    static final List<List<Message>> histories=new java.util.concurrent.CopyOnWriteArrayList<>();
    @TestConfiguration static class ModelFixture {
        @Bean AiToolModelGateway fixtureModel() {return (history,tools)->Mono.defer(()->{
            calls.incrementAndGet();histories.add(history);
            if(mode.equals("stall")) return Mono.<AiToolModelGateway.Round>never().doOnCancel(()->cancelled.set(true));
            if(mode.equals("provider-failure")) return Mono.error(new AiProviderException(ErrorCode.AI_UNAVAILABLE,true));
            long results=history.stream().filter(m->m instanceof ToolResponseMessage).count();
            if(mode.equals("write")) return Mono.just(tool("c1","deleteArticle","{\"id\":1}"));
            if(mode.equals("args")) return Mono.just(tool("c1","searchArticles","{\"keyword\":\"Java\",\"limit\":1,\"userId\":1}"));
            if(results==0) return Mono.just(tool("c1","searchArticles","{\"keyword\":\"Java\",\"limit\":1}"));
            if(results==1) return Mono.just(tool("c2","getArticleDetail","{\"id\":101}"));
            return Mono.just(new AiToolModelGateway.Round(AssistantMessage.builder().content("{\"answered\":true,\"answer\":\"公开构建标识是 AGENT_INTEGRATION_731。\",\"sourceIds\":[\"S1\"]}").build(),"STOP",11,8));
        });}
    }
    static AiToolModelGateway.Round tool(String id,String name,String args) {return new AiToolModelGateway.Round(AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall(id,"function",name,args))).build(),"TOOL_CALLS",12,4);}
    @Autowired JdbcTemplate jdbc;@Autowired TestRestTemplate http;@Autowired AiProperties properties;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;String token;
    @BeforeEach void fixtures() {
        mode="normal";calls.set(0);cancelled.set(false);histories.clear();properties.setEnabled(true);properties.setToolsEnabled(true);properties.setAgentEnabled(true);properties.setPublicEnabled(false);properties.setDailyRequestLimit(100);
        var keys=redis.keys("ai:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
        jdbc.update("INSERT IGNORE INTO sys_user(id,username,password,status) SELECT 811,'agent-reader',password,1 FROM sys_user WHERE id=1");jdbc.update("UPDATE sys_user SET status=1 WHERE id=811");
        jdbc.update("DELETE FROM blog_article_tag");jdbc.update("DELETE FROM blog_article");
        jdbc.update("INSERT INTO blog_article(id,title,content,author_id,status) VALUES(101,'Java公开夹具','公开构建标识 AGENT_INTEGRATION_731',1,1),(102,'Java私密草稿','PRIVATE_AGENT_DATA',1,0)");
        var login=http.postForEntity("/api/auth/login",Map.of("username","agent-reader","password","Admin@123456"),JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);token=login.getBody().path("data").path("token").asText();
    }
    ResponseEntity<String> post(Object body,String bearer) {var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(bearer!=null)headers.setBearerAuth(bearer);
        return http.exchange("/api/ai/agent/chat",HttpMethod.POST,new HttpEntity<>(body,headers),String.class);}
    String ask() {var response=post(Map.of("question","查询 Java 文章的构建标识"),token);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);return response.getBody();}
    @Test void authenticatedReaderExecutesRealPublicServicesWithExplicitIdentityAndBudget() {
        String body=ask();assertThat(body).contains("event:done","AGENT_INTEGRATION_731","/articles/101","searchArticles","getArticleDetail").doesNotContain("PRIVATE_AGENT_DATA",token,"agent-reader");
        assertThat(calls.get()).isEqualTo(3);assertThat(histories).hasSize(3);
        assertThat(histories.toString()).doesNotContain("PRIVATE_AGENT_DATA",token,"agent-reader");
        assertThat(redis.opsForValue().get("ai:quota:global:day:"+java.time.LocalDate.now(java.time.ZoneOffset.UTC))).isEqualTo("3");
        assertThat(redis.keys("ai:quota:user:minute:"+com.yufeichi.server.security.RedisSecurityStore.digest("811"))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT content FROM blog_article WHERE id=101",String.class)).isEqualTo("公开构建标识 AGENT_INTEGRATION_731");
    }
    @Test void anonymousDeniedEvenWhenPublicRagIsEnabled() {
        properties.setPublicEnabled(true);assertThat(post(Map.of("question","Java"),null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/api/ai/agent/status",String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);assertThat(calls.get()).isZero();
    }
    @Test void malformedClientParametersRejectedBeforePaidReservation() {
        for(Object body:List.of(Map.of(),Map.of("question",""),Map.of("question","x".repeat(501)),Map.of("question",3),Map.of("question","Java","userId",1)))
            assertThat(post(body,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(calls.get()).isZero();assertThat(redis.keys("ai:quota:global:*")).isEmpty();
    }
    @Test void threeSwitchesFailClosedWhileCoreWebsiteWorks() {
        properties.setAgentEnabled(false);assertThat(post(Map.of("question","Java"),token).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        properties.setAgentEnabled(true);properties.setToolsEnabled(false);assertThat(post(Map.of("question","Java"),token).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        properties.setToolsEnabled(true);properties.setEnabled(false);assertThat(post(Map.of("question","Java"),token).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(http.getForEntity("/api/health",String.class).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(calls.get()).isZero();
    }
    @Test void maliciousToolNamesAndExtraIdentityArgumentsTerminateWithoutAnswer() {
        for(String scenario:List.of("write","args")) {mode=scenario;String body=ask();assertThat(body).contains("event:error","63003").doesNotContain("event:done","PRIVATE_AGENT_DATA");}
        assertThat(calls.get()).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_article",Integer.class)).isEqualTo(2);
    }
    @Test void providerFaultNeverRetriesAndOnlyOneRoundIsReserved() {
        mode="provider-failure";assertThat(ask()).contains("event:error","63001").doesNotContain("event:done");assertThat(calls.get()).isEqualTo(1);
    }
    @Test void globalBudgetExhaustionStopsBeforeThirdProviderRound() {
        properties.setDailyRequestLimit(2);assertThat(ask()).contains("event:error","42900").doesNotContain("event:done");assertThat(calls.get()).isEqualTo(2);
    }
    @Test void disabledUsersOldTokenCannotStartAgent() {
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=811");assertThat(post(Map.of("question","Java"),token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);assertThat(calls.get()).isZero();
    }
    @Test void deadlineCancelsModelAndSendsSafeErrorEvent() {
        mode="stall";assertThat(ask()).contains("event:error","63002").doesNotContain("event:done");assertThat(cancelled).isTrue();
        mode="normal";assertThat(ask()).contains("event:done");assertThat(http.getForEntity("/api/health",String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
