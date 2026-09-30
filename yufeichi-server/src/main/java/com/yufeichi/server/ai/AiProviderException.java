package com.yufeichi.server.ai;

import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;

// Deliberately discard the provider body, message and cause at the boundary.
public final class AiProviderException extends BusinessException {
    private final boolean retryable;

    public AiProviderException(ErrorCode code, boolean retryable) {
        super(code);
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }
}
