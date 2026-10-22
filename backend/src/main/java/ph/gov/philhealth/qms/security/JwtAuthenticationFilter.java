package ph.gov.philhealth.qms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute holding the caller's sign-in session id (UUID), when the token carries one. */
    public static final String SESSION_ATTRIBUTE = "qms.sessionId";

    private final JwtService jwt;
    private final AuthUserLoader loader;

    public JwtAuthenticationFilter(JwtService jwt, AuthUserLoader loader) {
        this.jwt = jwt;
        this.loader = loader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            // A revoked session (sign-out, admin revoke, password change) also ends its access tokens at once.
            jwt.verify(header.substring(7).trim())
                    .filter(v -> v.sessionId() == null || loader.sessionLive(v.sessionId()))
                    .flatMap(v -> {
                        if (v.sessionId() != null) {
                            request.setAttribute(SESSION_ATTRIBUTE, v.sessionId());
                        }
                        return loader.loadActive(v.userId());
                    })
                    .ifPresent(user -> {
                        var auth = new UsernamePasswordAuthenticationToken(user, null, user.authorities());
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
        }
        chain.doFilter(request, response);
    }
}
