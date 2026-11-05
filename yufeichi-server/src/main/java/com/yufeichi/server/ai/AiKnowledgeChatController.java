package com.yufeichi.server.ai;

import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import com.yufeichi.server.common.result.Result;
import com.yufeichi.server.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposables;
import java.io.IOException;

@RestController
@RequestMapping("/api/ai/knowledge")
public class AiKnowledgeChatController {
    private final AiKnowledgeService service;
    private final AiProperties properties;
    private final com.yufeichi.server.security.ClientAddress addresses;
    public AiKnowledgeChatController(AiKnowledgeService service, AiProperties properties, com.yufeichi.server.security.ClientAddress addresses) {
        this.service = service; this.properties = properties; this.addresses = addresses;
    }
    @GetMapping("/status")
    public Result<java.util.Map<String, Boolean>> status() {
        return Result.success(java.util.Map.of("available", service.available(), "publicEnabled", properties.isPublicEnabled(), "loginRequired", !properties.isPublicEnabled()));
    }
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody KnowledgeRequest request, @AuthenticationPrincipal LoginUser user,
                          jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.http.HttpServletRequest servletRequest) {
        var prepared = user == null ? service.prepareAnonymous(request, addresses.resolve(servletRequest))
                : service.prepare(request, user.getUser().getId());
        response.setHeader("Cache-Control", "no-cache, no-store"); response.setHeader("X-Accel-Buffering", "no");
        var emitter = new SseEmitter((properties.getRequestTimeoutSeconds() + 5L) * 1000);
        var subscription = Disposables.swap();
        emitter.onCompletion(() -> { subscription.dispose(); prepared.lease().close(); });
        emitter.onError(error -> { subscription.dispose(); prepared.lease().close(); });
        emitter.onTimeout(() -> { subscription.dispose(); prepared.lease().close(); emitter.complete(); });
        subscription.update(service.stream(prepared).subscribe(event -> {
            try { emitter.send(SseEmitter.event().name(event.name()).data(event.data(), MediaType.APPLICATION_JSON)); }
            catch (IOException | IllegalStateException disconnected) { subscription.dispose(); prepared.lease().close(); emitter.complete(); }
        }, error -> {
            ErrorCode code = ErrorCode.AI_UNAVAILABLE;
            if (error instanceof BusinessException business)
                for (var candidate : new ErrorCode[]{ErrorCode.AI_TIMEOUT, ErrorCode.AI_INVALID_RESPONSE, ErrorCode.TOO_MANY_REQUESTS})
                    if (candidate.getCode() == business.getCode()) code = candidate;
            try { emitter.send(SseEmitter.event().name("error").data(Result.error(code), MediaType.APPLICATION_JSON)); }
            catch (IOException | IllegalStateException disconnected) { /* No private exception output. */ }
            emitter.complete();
        }, emitter::complete));
        return emitter;
    }
}
