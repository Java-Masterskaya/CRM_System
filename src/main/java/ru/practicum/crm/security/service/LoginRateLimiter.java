
package ru.practicum.crm.security.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.practicum.crm.common.error.RateLimitExceededException;
import ru.practicum.crm.security.config.LoginRateLimitProperties;

@Service
@RequiredArgsConstructor
public class LoginRateLimiter {

    private final LoginRateLimitProperties properties;
    private final Clock clock;

    private final ConcurrentMap<AttemptKey, AttemptState> attempts =
            new ConcurrentHashMap<>();

    public void checkAllowed(String tenantSlug, String email, String sourceIp) {
        AttemptKey key = createKey(tenantSlug, email, sourceIp);

        while (true) {
            AttemptState state = attempts.get(key);

            if (state == null) {
                return;
            }

            synchronized (state) {
                if (attempts.get(key) != state) {
                    continue;
                }

                Instant now = clock.instant();

                if (state.blockedUntil != null) {
                    if (now.isBefore(state.blockedUntil)) {
                        throw rateLimited(state.blockedUntil, now);
                    }

                    state.reset(now);
                    return;
                }

                if (!now.isBefore(state.windowStartedAt.plus(properties.window()))) {
                    state.reset(now);
                }

                return;
            }
        }
    }

    public void recordFailure(String tenantSlug, String email, String sourceIp) {
        AttemptKey key = createKey(tenantSlug, email, sourceIp);

        while (true) {
            AttemptState state = attempts.computeIfAbsent(
                    key,
                    ignored -> new AttemptState(clock.instant())
            );

            synchronized (state) {
                if (attempts.get(key) != state) {
                    continue;
                }

                Instant now = clock.instant();

                if (state.blockedUntil != null) {
                    if (now.isBefore(state.blockedUntil)) {
                        return;
                    }

                    state.reset(now);
                } else if (!now.isBefore(
                        state.windowStartedAt.plus(properties.window()))) {
                    state.reset(now);
                }

                state.failedAttempts++;

                if (state.failedAttempts >= properties.maxAttempts()) {
                    state.blockedUntil = now.plus(properties.blockDuration());
                }

                return;
            }
        }
    }

    public void recordSuccess(String tenantSlug, String email, String sourceIp) {
        AttemptKey key = createKey(tenantSlug, email, sourceIp);

        while (true) {
            AttemptState state = attempts.get(key);

            if (state == null) {
                return;
            }

            synchronized (state) {
                if (attempts.get(key) != state) {
                    continue;
                }

                state.reset(clock.instant());
                return;
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredEntries() {
        Instant now = clock.instant();

        attempts.forEach((key, state) -> {
            synchronized (state) {
                boolean expired = state.blockedUntil != null
                        ? !now.isBefore(state.blockedUntil)
                        : !now.isBefore(
                        state.windowStartedAt.plus(properties.window()));

                if (expired) {
                    attempts.remove(key, state);
                }
            }
        });
    }

    private AttemptKey createKey(String tenantSlug, String email, String sourceIp) {
        return new AttemptKey(
                normalize(tenantSlug),
                normalize(email),
                normalize(sourceIp)
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private RateLimitExceededException rateLimited(
            Instant blockedUntil,
            Instant now
    ) {
        Duration remaining = Duration.between(now, blockedUntil);

        long retryAfterSeconds = Math.max(
                1,
                remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0)
        );

        return new RateLimitExceededException(retryAfterSeconds);
    }

    private record AttemptKey(String tenantSlug, String email, String sourceIp) {
    }

    private static final class AttemptState {

        private int failedAttempts;
        private Instant windowStartedAt;
        private Instant blockedUntil;

        private AttemptState(Instant now) {
            this.windowStartedAt = now;
        }

        private void reset(Instant now) {
            failedAttempts = 0;
            windowStartedAt = now;
            blockedUntil = null;
        }
    }
}
