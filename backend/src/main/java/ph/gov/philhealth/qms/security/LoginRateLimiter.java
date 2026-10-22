package ph.gov.philhealth.qms.security;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.config.QmsProperties;

/**
 * Sliding-window limiter for authentication endpoints, keyed by client IP. Complements the
 * per-account lockout in {@code AuthService}. In-memory: each site node limits its own traffic.
 */
@Component
public class LoginRateLimiter {

    private final int perMinute;
    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(QmsProperties props) {
        this.perMinute = props.security().loginRatePerMinute();
    }

    public void check(String clientIp) {
        Instant now = Instant.now();
        Deque<Instant> window = attempts.computeIfAbsent(clientIp, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minusSeconds(60))) {
                window.pollFirst();
            }
            if (window.size() >= perMinute) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many attempts. Please wait a minute and try again.");
            }
            window.addLast(now);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    void evictIdle() {
        Instant cutoff = Instant.now().minusSeconds(120);
        attempts.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(cutoff);
            }
        });
    }
}
