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
import ph.gov.philhealth.qms.counter.DeviceService;

/** Authenticates kiosks and displays by their X-Device-Key header (stored only as a SHA-256 hash). */
public class DeviceAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Device-Key";

    private final DeviceService devices;

    public DeviceAuthenticationFilter(DeviceService devices) {
        this.devices = devices;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key != null && !key.isBlank() && SecurityContextHolder.getContext().getAuthentication() == null) {
            devices.authenticate(Hashing.sha256Hex(key.trim())).ifPresent(device -> {
                var authority = new SimpleGrantedAuthority("DEVICE_" + device.type());
                var auth = new UsernamePasswordAuthenticationToken(device, null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }
}
