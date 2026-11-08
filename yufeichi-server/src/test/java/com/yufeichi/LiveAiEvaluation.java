package com.yufeichi;

import com.fasterxml.jackson.databind.*;
import com.yufeichi.server.YufeichiServerApplication;
import com.yufeichi.server.ai.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit paid evaluation, NEVER included by the default Surefire naming pattern. */
@SpringBootTest(classes=YufeichiServerApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "app.ai.enabled=true", "app.ai.knowledge-enabled=true", "app.ai.vector.enabled=true",
        "app.ai.cost.enabled=true", "app.ai.cost.daily-cap=5", "app.ai.cost.chat-input-per-million=2",
        "app.ai.cost.chat-output-per-million=8", "app.ai.cost.embedding-input-per-million=0.125",
        "app.ai.cost.chat-model=deepseek-flash", "app.ai.cost.embedding-model=qwen3.7-text-embedding-flash",
        "app.ai.cost.price-date=2026-11-08", "app.ai.user-minute-limit=200", "app.ai.user-daily-limit=200", "app.ai.daily-request-limit=125"})
@ActiveProfiles("test") @Testcontainers
class LiveAiEvaluation {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("day13_eval").withUsername("eval").withPassword(UUID.randomUUID().toString());
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7").withExposedPorts(6379);
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse(AiVectorIntegrationTests.IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("day13_eval").withUsername("eval").withPassword(UUID.randomUUID().toString());
    @org.junit.jupiter.api.io.TempDir static Path uploads;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {
        assertThat(System.getenv("AI_EVAL_ENABLED")).isEqualTo("true");
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));r.add("spring.data.redis.password",()->"");
        r.add("app.ai.vector.url",PG::getJdbcUrl);r.add("app.ai.vector.username",PG::getUsername);r.add("app.ai.vector.password",PG::getPassword);
        Map.of("base-url","AI_PROVIDER_BASE_URL","api-key","AI_API_KEY","chat-model","AI_CHAT_MODEL",
                "embedding.base-url","AI_EMBEDDING_BASE_URL","embedding.api-key","AI_EMBEDDING_API_KEY",
                "embedding.model","AI_EMBEDDING_MODEL","embedding.dimensions","AI_EMBEDDING_DIMENSIONS").forEach((p,e)->{
            assertThat(System.getenv(e)!=null&&!System.getenv(e).isBlank()).as("Missing "+e).isTrue();r.add("app.ai."+p,()->System.getenv(e));
        });
        r.add("file.upload-path",()->uploads.toString());
    }
    @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired AiKnowledgeIndex index;
    @Autowired AiKnowledgeSources sources;
    @Autowired AiKnowledgeService service;
    @Autowired AiProperties properties;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    final List<Map<String,Object>> results=new ArrayList<>();
    final Map<String,Object> evidence=new LinkedHashMap<>();
    String globalCalls() { return redis.opsForValue().get("ai:quota:global:day:"+java.time.LocalDate.now(java.time.ZoneOffset.UTC)); }
    void save() throws Exception {
        evidence.put("results",results);evidence.put("reservedProviderCalls",globalCalls());
        Files.writeString(Path.of("target/ai-day13-evaluation.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
    Map<String,Object> percentiles(List<Map<String,Object>> rows,String key) {
        var times=rows.stream().filter(r->r.containsKey(key)).mapToLong(r->((Number)r.get(key)).longValue()).sorted().toArray();
        return times.length==0?Map.of("n",0):Map.of("n",times.length,"p50",times[(int)Math.ceil(times.length*.5)-1],"p95",times[(int)Math.ceil(times.length*.95)-1]);
    }
    @Test void fixedThirtyQuestionsTwoTopKConfigurations() throws Exception {
        byte[] dataset=getClass().getResourceAsStream("/ai-eval-v1.json").readAllBytes();var data=json.readTree(dataset);
        assertThat(data.path("cases")).hasSize(30);
        for(var s:data.path("sources")) {
            if(s.path("type").asText().equals("article")) jdbc.update("INSERT INTO blog_article(id,title,content,author_id,status) VALUES(?,?,?,1,?)",
                    s.path("id").asLong(),s.path("title").asText(),s.path("content").asText(),s.path("public").asBoolean()?1:0);
            else jdbc.update("INSERT INTO project(id,name,description,status) VALUES(?,?,?,?)",
                    s.path("id").asLong(),s.path("title").asText(),s.path("content").asText(),s.path("public").asBoolean()?1:0);
        }
        String job=index.submit(1);boolean ready=false;
        for(int n=0;n<300;n++) {
            var row=jdbc.queryForMap("SELECT status FROM ai_index_job WHERE id=?",job);
            if(List.of("SUCCEEDED","FAILED").contains(row.get("status"))) { assertThat(row.get("status")).isEqualTo("SUCCEEDED");ready=true;break; }
            Thread.sleep(500);
        }
        assertThat(ready).isTrue();
        evidence.put("kind","real-model-evaluation-on-synthetic-isolated-fixtures");
        evidence.put("clientDate","2026-11-08");evidence.put("datasetVersion",data.path("version").asText());
        evidence.put("datasetSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(dataset)));
        evidence.put("chatModel",properties.getChatModel());evidence.put("embeddingModel",properties.getEmbedding().getModel());
        evidence.put("dimensions",properties.getEmbedding().getDimensions());evidence.put("similarityThreshold",properties.getKnowledgeSimilarityThreshold());
        evidence.put("chunker","paragraph-v1/1024");evidence.put("sourceManifest",sources.read(false));
        evidence.put("index",jdbc.queryForMap("SELECT index_version,chunk_count FROM ai_index_job WHERE id=?",job));
        evidence.put("usageNote","Per-attempt provider usage from native stream/embedding metadata; null means unavailable, not zero. CNY values are conservative configured-price estimates, not invoices.");
        evidence.put("rubricVersion","v2-numeric-unit-contradiction-and-support-rules");
        evidence.put("rubricSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("src/test/java/com/yufeichi/AiEvaluationRubric.java")))));
        evidence.put("latencyNote","Sequential actual localhost HTTP SSE; first nonblank delta is first effective content (meta/heartbeat excluded). Total measured through stream EOF. No capacity claim.");
        jdbc.update("UPDATE sys_user SET status=1 WHERE id=1");
        String token=http.postForObject("/api/auth/login",Map.of("username","admin","password","Admin@123456"),JsonNode.class).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        var client=java.net.http.HttpClient.newHttpClient();
        List<Map<String,Object>> summaries=new ArrayList<>();
        for(int k:List.of(3,5)) {
            properties.setKnowledgeTopK(k);
            for(var test:data.path("cases")) {
                var result=new LinkedHashMap<String,Object>();result.put("topK",k);result.put("caseId",test.path("id").asText());
                result.put("kind",test.path("kind").asText());result.put("question",test.path("question").asText());
                result.put("expectedSources",test.path("expectedSources"));result.put("requiredFacts",test.path("requiredFacts"));
                long start=System.nanoTime();String requestId=null;
                try {
                    var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(http.getRootUri()+"/api/ai/knowledge/chat"))
                            .timeout(Duration.ofSeconds(70)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("question",test.path("question").asText())))).build();
                    var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
                    KnowledgeResponse answer=null;String event="";StringBuilder payload=new StringBuilder();
                    try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),java.nio.charset.StandardCharsets.UTF_8))) {
                        for(String line;(line=reader.readLine())!=null;) {
                            if(line.startsWith("event:")) event=line.substring(6).strip();
                            else if(line.startsWith("data:")) payload.append(line.substring(5).stripLeading());
                            else if(line.isEmpty() && !payload.isEmpty()) {
                                var value=json.readTree(payload.toString());
                                if(event.equals("meta")) requestId=value.path("requestId").asText();
                                if(event.equals("delta") && !value.path("text").asText().isBlank() && !result.containsKey("firstEffectiveMs"))
                                    result.put("firstEffectiveMs",(System.nanoTime()-start)/1_000_000);
                                if(event.equals("done")) answer=json.treeToValue(value,KnowledgeResponse.class);
                                if(event.equals("error")) throw new IllegalStateException("Safe SSE error code="+value.path("code").asInt());
                                payload.setLength(0);event="";
                            }
                        }
                    }
                    result.put("totalMs",(System.nanoTime()-start)/1_000_000);
                    assertThat(answer).isNotNull();result.put("response",answer);result.put("requestId",requestId);
                    Thread.sleep(50); // Allow terminal provider doFinally telemetry to finish; excluded from latency.
                    var trace=AiCallTelemetry.snapshot(requestId);result.put("calls",trace.calls());result.put("retrievalMs",trace.retrievalMs());
                    result.put("candidates",trace.candidates());
                    result.put("modelMs",trace.calls().stream().filter(c->c.kind().equals("chat")).mapToLong(AiCallTelemetry.Call::durationMs).sum());
                    boolean positive=test.path("kind").asText().equals("answerable");
                    boolean hit=positive&&trace.candidates().stream().anyMatch(c->test.path("expectedSources").toString().contains("\""+c.source()+"\""));
                    boolean facts=AiEvaluationRubric.facts(test.path("id").asText(),answer.answer());
                    boolean refs=AiEvaluationRubric.sources(test.path("expectedSources"),answer.sources().stream().map(source->source.sourceType()+":"+source.sourceId()).toList());
                    boolean noLeak=true;for(var f:data.path("forbidden")) noLeak &= !json.writeValueAsString(answer).contains(f.asText());
                    result.put("retrievalHit",hit);result.put("factsAndExpectedCitation",positive&&answer.answered()&&facts&&refs);
                    result.put("safeNoAnswer",!positive&&!answer.answered()&&answer.sources().isEmpty()&&answer.answer().equals(AiKnowledgeService.NO_ANSWER));result.put("noPrivateLeak",noLeak);
                } catch(Exception | AssertionError error) {
                    result.put("error","evaluation-request-failed"); // Never persist provider bodies, URLs or credentials.
                } finally { result.putIfAbsent("totalMs",(System.nanoTime()-start)/1_000_000);results.add(result);save(); }
                System.out.println("AI_EVAL_PROGRESS topK="+k+" case="+test.path("id").asText()+" completed="+results.size());
            }
            var rows=results.stream().filter(r->r.get("topK").equals(k)).toList();
            long hit=rows.stream().filter(r->Boolean.TRUE.equals(r.get("retrievalHit"))).count();
            long correct=rows.stream().filter(r->Boolean.TRUE.equals(r.get("factsAndExpectedCitation"))).count();
            long safe=rows.stream().filter(r->Boolean.TRUE.equals(r.get("safeNoAnswer"))).count();
            long errors=rows.stream().filter(r->r.containsKey("error")).count();
            long leaks=rows.stream().filter(r->Boolean.FALSE.equals(r.get("noPrivateLeak"))).count();
            var sorted=rows.stream().mapToLong(r->((Number)r.get("totalMs")).longValue()).sorted().toArray();
            summaries.add(Map.of("topK",k,"retrievalHits",hit,"factsAndCitationPass",correct,
                    "safeNoAnswer",safe,"errors",errors,"privateLeaks",leaks,
                    "latencyMs",Map.of("n",sorted.length,"p50",sorted[14],"p95",sorted[28]),
                    "firstEffectiveMs",percentiles(rows,"firstEffectiveMs")));
        }
        evidence.put("summaries",summaries);save();
        for(var s:summaries) {
            assertThat(((Number)s.get("retrievalHits")).longValue()).isGreaterThanOrEqualTo(15);
            assertThat(((Number)s.get("factsAndCitationPass")).longValue()).isGreaterThanOrEqualTo(15);
            assertThat(((Number)s.get("safeNoAnswer")).longValue()).isEqualTo(12);
            assertThat(((Number)s.get("errors")).longValue()).isZero();assertThat(((Number)s.get("privateLeaks")).longValue()).isZero();
        }
        assertThat(Integer.parseInt(globalCalls())).isLessThanOrEqualTo(125);
    }
}
