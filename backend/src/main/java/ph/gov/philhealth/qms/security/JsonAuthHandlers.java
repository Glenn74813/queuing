package ph.gov.philhealth.qms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import ph.gov.philhealth.qms.common.GlobalExceptionHandler;

/** Writes 401/403 responses raised by the security filter chain in the standard error format. */
@Component
public class JsonAuthHandlers {

    private final ObjectMapper mapper;

    public JsonAuthHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (req, res, ex) -> write(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required",
                req.getRequestURI());
    }

    public AccessDeniedHandler deniedHandler() {
        return (req, res, ex) -> write(res, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You are not allowed to perform this action", req.getRequestURI());
    }

    private void write(HttpServletResponse res, HttpStatus status, String code, String msg, String path)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), GlobalExceptionHandler.body(status, code, msg, path));
    }
}
