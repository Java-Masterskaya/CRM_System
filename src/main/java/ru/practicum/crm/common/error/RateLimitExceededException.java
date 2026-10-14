
package ru.practicum.crm.common.error;

public class RateLimitExceededException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(
                ErrorCode.RATE_LIMITED,
                ErrorCode.RATE_LIMITED.getDefaultDetail()
        );

        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
