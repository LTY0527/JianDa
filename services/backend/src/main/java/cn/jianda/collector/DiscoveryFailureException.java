package cn.jianda.collector;

import cn.jianda.common.BusinessException;

/** Stable discovery failure used by background jobs and the human-readable operations UI. */
public final class DiscoveryFailureException extends BusinessException {
    private final String reasonCode;
    private final boolean retryable;

    public DiscoveryFailureException(int httpStatus, String reasonCode, String message, boolean retryable) {
        super(httpStatus, message);
        this.reasonCode = reasonCode;
        this.retryable = retryable;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
