package com.yufeichi.server.ai;

import reactor.core.publisher.Flux;

public interface AiModelGateway {
    Flux<String> generate(String system, String content, boolean structured);
}
