package com.yufeichi.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiSummaryServiceTest {
    private final AiProperties properties = new AiProperties();
    private final AiRequestGuard guard = mock(AiRequestGuard.class);
    private final AiRequestGuard.Lease lease = mock(AiRequestGuard.Lease.class);
    private final ObjectProvider<AiModelGateway> provider = mock(ObjectProvider.class);
    private final AiModelGateway gateway = mock(AiModelGateway.class);
    private Scheduler worker;
    private Scheduler timer;
    private AiSummaryService service;

    @BeforeEach void setup() {
        properties.setEnabled(true);
        properties.setRequestTimeoutSeconds(1);
        properties.setHeartbeatSeconds(1);
        when(provider.getIfAvailable()).thenReturn(gateway);
        when(provider.getObject()).thenReturn(gateway);
        when(guard.acquire(1)).thenReturn(lease);
        worker = Schedulers.newBoundedElastic(2, 8, "ai-unit");
        timer = Schedulers.newSingle("ai-unit-timer");
        service = new AiSummaryService(properties, provider, guard, new ObjectMapper(), worker, timer);
    }
    @AfterEach void stop() { worker.dispose(); timer.dispose(); }

    @Test void validatesBeforeQuotaAndDisabledModeHasNoModelCall() {
        assertThatThrownBy(() -> service.prepare(new SummaryRequest(" "), 1))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(40000));
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.prepare(new SummaryRequest("正文"), 1))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(63001));
        verifyNoInteractions(gateway, guard);
    }

    @Test void structuredSummaryPreservesSnapshotAndClosesLease() {
        when(gateway.generate(anyString(), eq("正文"), eq(true)))
                .thenReturn(Flux.just("{\"summary\":", "\"摘要\"}"));
        var response = service.summarize(service.prepare(new SummaryRequest("正文"), 1)).block();
        assertThat(response.summary()).isEqualTo("摘要");
        assertThat(response.contentHash()).matches("[a-f0-9]{64}");
        verify(lease, timeout(500)).close();
    }

    @Test void invalidStructuredOutputCannotBecomeSuccess() {
        for (String raw : new String[]{"摘要", "{}", "{\"summary\":123}", "{\"summary\":\"\"}",
                "{\"summary\":\"合法\",\"extra\":1}", "{\"summary\":\"合法\"} PRIVATE-TAIL",
                "{\"summary\":\"一\",\"summary\":\"二\"}", "{\"summary\":\"" + "长".repeat(501) + "\"}"}) {
            when(gateway.generate(anyString(), anyString(), eq(true))).thenReturn(Flux.just(raw));
            assertThatThrownBy(() -> service.summarize(service.prepare(new SummaryRequest("正文"), 1)).block())
                    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(63003));
        }
    }

    @Test void retriesOnceOnlyBeforeReceivingAnyContent() {
        var calls = new AtomicInteger();
        when(gateway.generate(anyString(), anyString(), eq(true))).thenAnswer(invocation ->
                calls.incrementAndGet() == 1 ? Flux.error(new AiProviderException(ErrorCode.AI_UNAVAILABLE, true))
                        : Flux.just("{\"summary\":\"摘要\"}"));
        assertThat(service.summarize(service.prepare(new SummaryRequest("正文"), 1)).block().summary()).isEqualTo("摘要");
        assertThat(calls).hasValue(2);
        verify(guard).reserveRetry();
    }

    @Test void partiallyReceivedStreamIsNeverReplayed() {
        when(gateway.generate(anyString(), anyString(), eq(false))).thenReturn(Flux.concat(Flux.just("半段"),
                Flux.error(new AiProviderException(ErrorCode.AI_UNAVAILABLE, true))));
        StepVerifier.create(service.stream(service.prepare(new SummaryRequest("正文"), 1)))
                .expectNextMatches(event -> event.name().equals("meta"))
                .expectNextMatches(event -> event.name().equals("delta"))
                .expectError(AiProviderException.class).verify(Duration.ofSeconds(3));
        verify(guard, never()).reserveRetry();
        verify(lease, timeout(500)).close();
    }

    @Test void overallDeadlineAppliesEvenWhileProviderKeepsSending() {
        when(gateway.generate(anyString(), anyString(), eq(false))).thenReturn(
                Flux.interval(Duration.ofMillis(100)).map(tick -> "字"));
        StepVerifier.create(service.stream(service.prepare(new SummaryRequest("正文"), 1)))
                .thenConsumeWhile(event -> true)
                .expectErrorMatches(error -> error instanceof BusinessException business && business.getCode() == 63002)
                .verify(Duration.ofSeconds(3));
        verify(lease, timeout(500)).close();
    }

    @Test void cancelClosesUpstreamAndLease() {
        var cancelled = new AtomicInteger();
        when(gateway.generate(anyString(), anyString(), eq(false)))
                .thenReturn(Flux.<String>never().doOnCancel(cancelled::incrementAndGet));
        StepVerifier.create(service.stream(service.prepare(new SummaryRequest("正文"), 1)))
                .expectNextMatches(event -> event.name().equals("meta"))
                .thenAwait(Duration.ofMillis(100)).thenCancel().verify();
        assertThat(cancelled).hasValue(1);
        verify(lease, timeout(500)).close();
    }
}
