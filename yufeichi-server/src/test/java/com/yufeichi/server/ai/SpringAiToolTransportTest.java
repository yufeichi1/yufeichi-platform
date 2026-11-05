package com.yufeichi.server.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.chat.messages.UserMessage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class SpringAiToolTransportTest {
    @Test void cancellationClosesIdleNativeProviderConnectionAndManagedResources() throws Exception {
        var connected=new CountDownLatch(1);var closed=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"idle-provider-fixture");t.setDaemon(true);return t;});server.setExecutor(executor);
        server.createContext("/v1/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()) {
                output.write("data: {\"id\":\"idle\",\"object\":\"chat.completion.chunk\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));output.flush();connected.countDown();
                // Comments leave the model response idle while detecting whether its TCP connection closes.
                for(int i=0;i<150;i++) {Thread.sleep(20);output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));output.flush();}
            } catch(java.io.IOException cancelled) {closed.set(true);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(AiConfiguration.class).withPropertyValues("app.ai.enabled=true",
                    "app.ai.base-url=http://127.0.0.1:"+server.getAddress().getPort(),"app.ai.api-key=transport-fixture-not-real","app.ai.chat-model=fixture")
                    .run(context->{
                        assertThat(context).hasNotFailed();
                        var resources=context.getBean("aiReactiveResources",org.springframework.http.client.ReactorResourceFactory.class);
                        assertThat(resources.isUseGlobalResources()).isFalse();
                        var subscription=context.getBean(AiToolModelGateway.class).round(List.of(new UserMessage("fixture")),List.of()).subscribe(v->{},e->{});
                        try {
                            assertThat(connected.await(3,TimeUnit.SECONDS)).isTrue();Thread.sleep(100);subscription.dispose();
                            for(int i=0;i<100&&!closed.get();i++)Thread.sleep(20);
                            assertThat(closed).as("An idle upstream connection must close after cancellation").isTrue();
                        } finally {subscription.dispose();}
                    });
        } finally {server.stop(0);executor.shutdownNow();}
    }
}
