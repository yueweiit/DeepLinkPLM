package com.yuewei.plm.module.integration.crm.moldschedule.client;

import java.time.Duration;

public class CrmMoldScheduleDeliveryException extends RuntimeException {
    private final boolean retryable;
    private final Integer httpStatus;
    private final Duration retryAfter;

    public CrmMoldScheduleDeliveryException(String message, boolean retryable, Integer httpStatus, Duration retryAfter) {
        super(message);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public boolean isRetryable() { return retryable; }
    public Integer getHttpStatus() { return httpStatus; }
    public Duration getRetryAfter() { return retryAfter; }
}
