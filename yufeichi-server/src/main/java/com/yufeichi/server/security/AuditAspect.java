package com.yufeichi.server.security;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
@Aspect @Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 1)
public class AuditAspect {
    @Around("@annotation(action)")
    public Object record(ProceedingJoinPoint point,AuditAction action) throws Throwable {
        Long target=point.getArgs().length>0 && point.getArgs()[0] instanceof Long id ? id : null;
        try {
            Object result=point.proceed();
            if(result instanceof com.yufeichi.server.vo.FileVO file)target=file.id();
            Long actor=AuditEvents.actor(), resource=target;
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) {
                            AuditEvents.record(action.value(),status==STATUS_COMMITTED ? "success" : "failure",actor,resource);
                        }
                    });
            } else AuditEvents.record(action.value(),"success",actor,resource);
            return result;
        } catch(Throwable failure){
            AuditEvents.record(action.value(),"failure",AuditEvents.actor(),target);
            throw failure;
        }
    }
}
