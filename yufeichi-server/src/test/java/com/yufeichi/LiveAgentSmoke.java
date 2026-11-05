package com.yufeichi;

import com.yufeichi.server.YufeichiServerApplication;
import com.yufeichi.server.ai.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in only, fresh databases, at most three paid Chat rounds, no Embedding. */
@SpringBootTest(classes=YufeichiServerApplication.class,properties={"app.ai.enabled=true","app.ai.tools-enabled=true","app.ai.agent-enabled=true","app.ai.daily-request-limit=3"})
@ActiveProfiles("test") @Testcontainers
class LiveAgentSmoke {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4").withDatabaseName("live_agent_test").withUsername("smoke").withPassword(UUID.randomUUID().toString());
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7").withExposedPorts(6379);
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path uploads;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {
        assertThat(System.getenv("AI_AGENT_SMOKE_ENABLED")).isEqualTo("true");
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));r.add("spring.data.redis.password",()->"");
        for(var entry:Map.of("base-url","AI_PROVIDER_BASE_URL","api-key","AI_API_KEY","chat-model","AI_CHAT_MODEL").entrySet()) {
            assertThat(System.getenv(entry.getValue())!=null&&!System.getenv(entry.getValue()).isBlank()).as("Missing "+entry.getValue()).isTrue();
            r.add("app.ai."+entry.getKey(),()->System.getenv(entry.getValue()));
        }
        r.add("file.upload-path",()->uploads.toString());
    }
    @Autowired AiAgentService service;@Autowired AiProperties properties;@Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;@Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Test void realModelSelectsSearchThenDetailThenAnswer() throws Exception {
        jdbc.update("INSERT INTO blog_article(id,title,content,author_id,status) VALUES(711,'AgentComet 公开验收夹具','本测试的构建标识是 COMET-731。使用 Java21。这是独立测试文本，不是线上事实。',1,1),(712,'AgentComet 私密草稿','PRIVATE_AGENT_SMOKE_MUST_NOT_LEAVE_DATABASE',1,0)");
        String question="请查询标题含 AgentComet 的文章，读取详情后告诉我构建标识。";
        var event=service.stream(service.prepare(new AgentRequest(question),1)).filter(e->e.name().equals("done")).blockFirst(Duration.ofSeconds(65));
        assertThat(event).isNotNull();var result=(AiAgentService.Response)event.data();
        assertThat(result.answered()).isTrue();assertThat(result.answer()).contains("COMET-731").doesNotContain("PRIVATE_AGENT");
        assertThat(result.sources()).hasSize(1);assertThat(result.sources().getFirst().url()).isEqualTo("/articles/711");
        assertThat(result.steps()).extracting(AiAgentService.Trace::tool).containsSubsequence("searchArticles","getArticleDetail");
        assertThat(result.steps().size()).isLessThanOrEqualTo(4);assertThat(result.modelRounds()).isBetween(2,3);
        String paid=redis.opsForValue().get("ai:quota:global:day:"+java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        assertThat(Integer.parseInt(paid)).isEqualTo(result.modelRounds());
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/ai-agent-live-evidence.json"),json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("chatModel",properties.getChatModel(),"question",question,"response",result,"reservedProviderCalls",paid)));
        System.out.println("LIVE_AGENT_PASS search_detail_answer=true source=/articles/711 rounds="+result.modelRounds()+" tools="+result.steps().size());
    }
}
