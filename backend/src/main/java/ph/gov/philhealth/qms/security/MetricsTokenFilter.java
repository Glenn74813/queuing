package ph.gov.philhealth.qms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a Prometheus server scrape /actuator/prometheus with a static bearer token
 * (QMS_METRICS_TOKEN). Disabled when no token is configured. The public nginx proxy does not
 * expose /actuator/prometheus; scraping happens on the internal network.
 */
public class MetricsTokenFilter extends OncePerRequestFilter {

    public static final String AUTHORITY = "METRICS_SCRAPE";

    private final byte[] token;

    public MetricsTokenFilter(String token) {
        this.token = token == null || token.isBlank() ? null : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return token == null || !"/actuator/prometheus".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            byte[] presented = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(presented, token)) {   // constant-time comparison
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "prometheus", null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
            }
        }
        chain.doFilter(request, response);
    }
}
