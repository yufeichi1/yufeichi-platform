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
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposables;

import java.io.IOException;

@RestController
@RequestMapping("/api/admin/ai/summary")
@PreAuthorize("hasAnyAuthority('article:add','article:update')")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "BearerAuth")
public class AiSummaryController {
    private final AiSummaryService service;
    private final AiProperties properties;

    public AiSummaryController(AiSummaryService service, AiProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping
    public DeferredResult<Result<SummaryResponse>> summarize(@Valid @RequestBody SummaryRequest request,
                                                             @AuthenticationPrincipal LoginUser user) {
        var prepared = service.prepare(request, user.getUser().getId());
        var result = new DeferredResult<Result<SummaryResponse>>((properties.getRequestTimeoutSeconds() + 5L) * 1000);
        var subscription = Disposables.swap();
        result.onCompletion(() -> { subscription.dispose(); prepared.lease().close(); });
        result.onTimeout(() -> {
            subscription.dispose();
            prepared.lease().close();
            result.setErrorResult(new BusinessException(ErrorCode.AI_TIMEOUT));
        });
        result.onError(error -> { subscription.dispose(); prepared.lease().close(); });
        subscription.update(service.summarize(prepared).subscribe(
                response -> result.setResult(Result.success(response)), result::setErrorResult));
        return result;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody SummaryRequest request, @AuthenticationPrincipal LoginUser user,
                             jakarta.servlet.http.HttpServletResponse response) {
        var prepared = service.prepare(request, user.getUser().getId());
        response.setHeader("Cache-Control", "no-cache, no-store");
        response.setHeader("X-Accel-Buffering", "no");
        var emitter = new SseEmitter((properties.getRequestTimeoutSeconds() + 5L) * 1000);
        var subscription = Disposables.swap();
        emitter.onCompletion(() -> { subscription.dispose(); prepared.lease().close(); });
        emitter.onTimeout(() -> { subscription.dispose(); prepared.lease().close(); emitter.complete(); });
        emitter.onError(error -> { subscription.dispose(); prepared.lease().close(); });
        subscription.update(service.stream(prepared).subscribe(event -> {
            try {
                emitter.send(SseEmitter.event().name(event.name()).data(event.data(), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException disconnected) {
                subscription.dispose();
                prepared.lease().close();
                emitter.complete();
            }
        }, error -> {
            ErrorCode code = error instanceof BusinessException business && business.getCode() == ErrorCode.AI_TIMEOUT.getCode()
                    ? ErrorCode.AI_TIMEOUT : error instanceof BusinessException business
                    && business.getCode() == ErrorCode.AI_INVALID_RESPONSE.getCode() ? ErrorCode.AI_INVALID_RESPONSE
                    : error instanceof BusinessException business && business.getCode() == ErrorCode.TOO_MANY_REQUESTS.getCode()
                    ? ErrorCode.TOO_MANY_REQUESTS : ErrorCode.AI_UNAVAILABLE;
            try {
                emitter.send(SseEmitter.event().name("error").data(Result.error(code), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException disconnected) { /* No provider or request data logged. */ }
            emitter.complete();
        }, emitter::complete));
        return emitter;
    }
}
