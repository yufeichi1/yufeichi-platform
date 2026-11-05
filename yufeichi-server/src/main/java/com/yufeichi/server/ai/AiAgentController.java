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
import java.util.Map;

@RestController @RequestMapping("/api/ai/agent") @PreAuthorize("isAuthenticated()")
public class AiAgentController {
    private final AiAgentService service;private final AiProperties properties;
    public AiAgentController(AiAgentService service,AiProperties properties) {this.service=service;this.properties=properties;}
    @GetMapping("/status") public Result<Map<String,Object>> status() {
        return Result.success(Map.of("available",service.available(),"maxRounds",AiAgentService.MAX_ROUNDS,"maxTools",AiAgentService.MAX_TOOLS));
    }
    @PostMapping(value="/chat",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody AgentRequest request,@AuthenticationPrincipal LoginUser user,
                           jakarta.servlet.http.HttpServletResponse response) {
        var prepared=service.prepare(request,user.getUser().getId());
        response.setHeader("Cache-Control","no-cache, no-store");response.setHeader("X-Accel-Buffering","no");
        var emitter=new SseEmitter((properties.getRequestTimeoutSeconds()+5L)*1000);var subscription=Disposables.swap();
        emitter.onCompletion(()->{subscription.dispose();prepared.lease().close();});
        emitter.onError(error->{subscription.dispose();prepared.lease().close();});
        emitter.onTimeout(()->{subscription.dispose();prepared.lease().close();emitter.complete();});
        subscription.update(service.stream(prepared).subscribe(event->{
            try {emitter.send(SseEmitter.event().name(event.name()).data(event.data(),MediaType.APPLICATION_JSON));}
            catch(IOException | IllegalStateException disconnected) {subscription.dispose();prepared.lease().close();emitter.complete();}
        },error->{
            ErrorCode code=ErrorCode.SYSTEM_ERROR;
            if(error instanceof BusinessException business) {
                code=ErrorCode.AI_UNAVAILABLE;
                for(var candidate:ErrorCode.values()) if(candidate.getCode()==business.getCode()) code=candidate;
            }
            try {emitter.send(SseEmitter.event().name("error").data(Result.error(code),MediaType.APPLICATION_JSON));}
            catch(IOException | IllegalStateException disconnected) { /* Safe error only. */ }
            emitter.complete();
        },emitter::complete));
        return emitter;
    }
}
