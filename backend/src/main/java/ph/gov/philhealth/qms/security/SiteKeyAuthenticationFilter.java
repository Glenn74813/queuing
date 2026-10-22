package ph.gov.philhealth.qms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ph.gov.philhealth.qms.common.Hashing;
import ph.gov.philhealth.qms.sync.SiteKeyService;

/** Authenticates site nodes calling the central synchronization endpoints (X-Site-Key). */
public class SiteKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Site-Key";

    private final SiteKeyService siteKeys;

    public SiteKeyAuthenticationFilter(SiteKeyService siteKeys) {
        this.siteKeys = siteKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key != null && !key.isBlank() && SecurityContextHolder.getContext().getAuthentication() == null) {
            siteKeys.authenticate(Hashing.sha256Hex(key.trim())).ifPresent(site -> {
                var auth = new UsernamePasswordAuthenticationToken(site, null,
                        List.of(new SimpleGrantedAuthority("SYNC_SITE")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }
}
