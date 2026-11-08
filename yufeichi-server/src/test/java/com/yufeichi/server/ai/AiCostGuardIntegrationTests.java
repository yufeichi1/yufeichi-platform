package com.yufeichi.server.ai;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class AiCostGuardIntegrationTests {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    AiProperties properties;
    AiCostGuard guard;
    @BeforeAll static void connect() {
        connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));connection.afterPropertiesSet();
        redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();
    }
    @AfterAll static void close() { connection.destroy(); }
    @BeforeEach void setup() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb(); // Owned disposable Redis only.
        properties=new AiProperties();properties.setChatModel("chat");properties.getEmbedding().setModel("embedding");
        var c=properties.getCost();c.setChatModel("chat");c.setEmbeddingModel("embedding");c.setPriceDate("2026-11-08");
        c.setChatInputPerMillion(BigDecimal.ONE);c.setChatOutputPerMillion(BigDecimal.TEN);c.setEmbeddingInputPerMillion(BigDecimal.ONE);
        c.setDailyCap(new BigDecimal("0.001"));guard=new AiCostGuard(redis,properties);
    }
    long spent() { return Long.parseLong(redis.opsForValue().get(redis.keys("ai:cost:cny:*").iterator().next())); }
    @Test void sharedAtomicCapCannotBeOverspentByConcurrentInstances() throws Exception {
        var other=new AiCostGuard(redis,properties);var barrier=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs=new ArrayList<Future<Boolean>>();
            for(int i=0;i<40;i++) {var instance=i%2==0?guard:other;jobs.add(executor.submit(()->{barrier.await();
                try {instance.reserve(false,100,0,UUID.randomUUID().toString()).finish("CANCEL");return true;}
                catch(com.yufeichi.server.common.error.BusinessException e) {assertThat(e.getCode()).isEqualTo(42900);return false;}}));}
            barrier.countDown();int accepted=0;for(var job:jobs) if(job.get()) accepted++;
            assertThat(accepted).isEqualTo(10);assertThat(spent()).isEqualTo(1000);
        }
    }
    @Test void completeUsageSettlesOnceAndNeverClaimsInvoicePrecision() {
        var r=guard.reserve(false,500,40,"usage");r.usage(new DefaultUsage(20,3));r.finish("ON_COMPLETE");r.finish("ON_COMPLETE");
        assertThat(spent()).isEqualTo(50);assertThat(AiCallTelemetry.snapshot("usage").calls().getLast().inputTokens()).isEqualTo(20);
    }
    @Test void unknownAndCancelledUsageKeepFullReservations() {
        var a=guard.reserve(false,100,10,"unknown");a.finish("ON_COMPLETE");
        var b=guard.reserve(false,100,10,"cancel");b.usage(new DefaultUsage(1,1));b.finish("CANCEL");
        assertThat(spent()).isEqualTo(400);assertThat(AiCallTelemetry.snapshot("unknown").calls().getLast().inputTokens()).isNull();
    }
    @Test void partiallyReportedUsageDoesNotTurnMissingOutputIntoFreeZeroTokens() {
        var r=guard.reserve(false,500,40,"partial");
        var nativeUsage=new org.springframework.ai.openai.api.OpenAiApi.Usage(null,20,20);
        r.usage(new DefaultUsage(20,null,null,nativeUsage));r.finish("ON_COMPLETE");
        assertThat(spent()).isEqualTo(900);
        var call=AiCallTelemetry.snapshot("partial").calls().getLast();
        assertThat(call.inputTokens()).isEqualTo(20);assertThat(call.outputTokens()).isNull();
        assertThat(AiCostGuard.tokens(new DefaultUsage(20,null),false).output()).isNull();
    }
    @Test void failedRetryAttemptsAreChargedSeparately() {
        guard.reserve(false,100,10,"retry").finish("ON_ERROR");guard.reserve(false,100,10,"retry").finish("ON_COMPLETE");
        assertThat(spent()).isEqualTo(400);
    }
    @Test void embeddingAndChatShareCurrencyCap() {
        guard.reserve(true,800,0,"index").finish("ON_ERROR");guard.reserve(false,100,10,"chat").finish("CANCEL");
        assertThatThrownBy(()->guard.reserve(true,1,0,"query")).isInstanceOf(com.yufeichi.server.common.error.BusinessException.class);
        assertThat(spent()).isEqualTo(1000);
    }
    @Test void unconfiguredPricesOrWrongModelBlockBeforeIo() {
        properties.getCost().setChatModel("wrong");assertThatThrownBy(()->guard.reserve(false,100,10,"a")).isInstanceOf(com.yufeichi.server.common.error.BusinessException.class);
        properties.getCost().setChatModel("chat");properties.getCost().setChatInputPerMillion(BigDecimal.ZERO);
        assertThatThrownBy(()->guard.reserve(false,100,10,"a")).isInstanceOf(com.yufeichi.server.common.error.BusinessException.class);
        assertThat(redis.keys("ai:cost:*")).isEmpty();
    }
    @Test void redisOutageFailsClosedWithoutProviderCall() {
        var failed=org.mockito.Mockito.mock(StringRedisTemplate.class);
        org.mockito.Mockito.when(failed.execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.<Object[]>any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private"));
        assertThatThrownBy(()->new AiCostGuard(failed,properties).reserve(false,100,10,"failure"))
                .isInstanceOf(com.yufeichi.server.common.error.BusinessException.class).hasMessageNotContaining("private");
    }
}
