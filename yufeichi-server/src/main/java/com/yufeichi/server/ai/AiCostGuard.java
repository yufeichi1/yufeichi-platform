package com.yufeichi.server.ai;

import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Atomic CNY micro-unit ledger shared by instances; this is a conservative estimate, not a vendor invoice. */
@Component
public class AiCostGuard {
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local used=tonumber(redis.call('GET',KEYS[1]) or '0')
            if used+tonumber(ARGV[1])>tonumber(ARGV[2]) then return 0 end
            redis.call('INCRBY',KEYS[1],ARGV[1]); redis.call('PEXPIRE',KEYS[1],259200000)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> SETTLE = new DefaultRedisScript<>("""
            if redis.call('EXISTS',KEYS[1])==0 then return 0 end
            redis.call('INCRBY',KEYS[1],ARGV[1]); return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final AiProperties properties;
    public AiCostGuard(StringRedisTemplate redis, AiProperties properties) { this.redis=redis; this.properties=properties; }
    public Reservation reserve(boolean embedding, long inputBound, int outputBound, String requestId) {
        var cost=properties.getCost();
        String model=embedding?properties.getEmbedding().getModel():properties.getChatModel();
        var inputRate=embedding?cost.getEmbeddingInputPerMillion():cost.getChatInputPerMillion();
        var outputRate=embedding?BigDecimal.ZERO:cost.getChatOutputPerMillion();
        String ratedModel=embedding?cost.getEmbeddingModel():cost.getChatModel();
        boolean enabled=cost.isEnabled();
        if (inputBound<1 || inputBound>1_000_000 || outputBound<0 || outputBound>2000) throw unavailable();
        if (enabled && (!model.equals(ratedModel) || model.isBlank() || cost.getPriceDate().isBlank()
                || !valid(inputRate) || !embedding && !valid(outputRate) || !valid(cost.getDailyCap()))) throw unavailable();
        long reserved=enabled?units(inputBound,outputBound,inputRate,outputRate):0;
        String key="ai:cost:cny:"+LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if(enabled) try {
            Long accepted=redis.execute(RESERVE,List.of(key),Long.toString(reserved),
                    cost.getDailyCap().movePointRight(6).setScale(0,RoundingMode.FLOOR).toPlainString());
            if(accepted==null) throw unavailable();
            if(accepted!=1)
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        } catch(org.springframework.dao.DataAccessException failure) { throw unavailable(); }
        return new Reservation(key,requestId,embedding?"embedding":"chat",model,reserved,inputRate,outputRate,enabled);
    }
    private static boolean valid(BigDecimal n) { return n!=null && n.signum()>0 && n.compareTo(new BigDecimal("100000"))<=0; }
    private static long units(long input,long output,BigDecimal ir,BigDecimal or) {
        return ir.multiply(BigDecimal.valueOf(input)).add(or.multiply(BigDecimal.valueOf(output)))
                .setScale(0,RoundingMode.CEILING).longValueExact(); // CNY/1M tokens -> micro-CNY
    }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.AI_UNAVAILABLE); }
    record Tokens(Integer input, Integer output) { }
    static Tokens tokens(org.springframework.ai.chat.metadata.Usage usage, boolean embedding) {
        if(usage==null || usage instanceof org.springframework.ai.chat.metadata.EmptyUsage) return new Tokens(null,null);
        // Spring AI DefaultUsage normalizes a missing completion count to zero. Prefer native nullability.
        if(usage.getNativeUsage() instanceof org.springframework.ai.openai.api.OpenAiApi.Usage nativeUsage) {
            Integer input=nativeUsage.promptTokens();Integer output=nativeUsage.completionTokens();
            if(input!=null && input<=0) input=null;
            if(embedding) output=input==null?null:Integer.valueOf(0);
            else if(output!=null && output<0) output=null;
            return new Tokens(input,output);
        }
        Integer input=usage.getPromptTokens(),output=usage.getCompletionTokens();
        if(input!=null && input<=0) input=null;
        if(embedding) output=input==null?null:Integer.valueOf(0);
        else if(output!=null && output<=0) output=null;
        return new Tokens(input,output);
    }
    public final class Reservation {
        private final String key,id,kind,model;
        private final long reserved,start=System.nanoTime();
        private final BigDecimal inputRate,outputRate;
        private final boolean enabled;
        private final AtomicBoolean settled=new AtomicBoolean();
        private volatile Integer input,output;
        Reservation(String key,String id,String kind,String model,long reserved,BigDecimal ir,BigDecimal or,boolean enabled) {
            this.key=key;this.id=id;this.kind=kind;this.model=model;this.reserved=reserved;this.inputRate=ir;this.outputRate=or;this.enabled=enabled;
        }
        public void usage(org.springframework.ai.chat.metadata.Usage usage) {
            var values=tokens(usage,kind.equals("embedding"));
            if(values.input()!=null) input=values.input();
            if(values.output()!=null) output=values.output();
        }
        public Integer inputTokens() { return input; }
        public Integer outputTokens() { return output; }
        public void finish(String outcome) {
            if(!settled.compareAndSet(false,true)) return;
            long charged=reserved;
            // Partial/cancelled/error usage may omit billed tokens. Only a successful complete call can release excess.
            if(enabled && outcome.equals("ON_COMPLETE") && input!=null && (kind.equals("embedding") || output!=null)) {
                charged=units(input,kind.equals("embedding")?0:output,inputRate,outputRate);
                try {
                    if(!Long.valueOf(1).equals(redis.execute(SETTLE,List.of(key),Long.toString(charged-reserved)))) charged=reserved;
                } catch(org.springframework.dao.DataAccessException failure) { charged=reserved; }
            }
            AiCallTelemetry.record(id,new AiCallTelemetry.Call(kind,model,input,output,outcome,(System.nanoTime()-start)/1_000_000,
                    enabled?BigDecimal.valueOf(reserved,6):null,enabled?BigDecimal.valueOf(charged,6):null));
        }
    }
}
