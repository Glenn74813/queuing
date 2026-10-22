package ph.gov.philhealth.qms.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ph.gov.philhealth.qms.counter.DeviceService;
import ph.gov.philhealth.qms.security.AuthUserLoader;
import ph.gov.philhealth.qms.security.DeviceAuthenticationFilter;
import ph.gov.philhealth.qms.security.JsonAuthHandlers;
import ph.gov.philhealth.qms.security.JwtAuthenticationFilter;
import ph.gov.philhealth.qms.security.JwtService;
import ph.gov.philhealth.qms.security.MetricsTokenFilter;
import ph.gov.philhealth.qms.security.PasswordChangeRequiredFilter;
import ph.gov.philhealth.qms.security.SiteKeyAuthenticationFilter;
import ph.gov.philhealth.qms.sync.SiteKeyService;

/**
 * Stateless API security. CSRF protection is disabled because no cookie-based session or
 * ambient credential exists: every call carries an explicit bearer token or key header.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtService jwt, AuthUserLoader loader, DeviceService devices,
                            SiteKeyService siteKeys, JsonAuthHandlers handlers,
                            com.fasterxml.jackson.databind.ObjectMapper mapper, QmsProperties props) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> { })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'"))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.deniedHandler()))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/api/v1/kiosk/**").hasAuthority("DEVICE_KIOSK")
                        .requestMatchers("/api/v1/display/**").hasAuthority("DEVICE_DISPLAY")
                        .requestMatchers("/api/v1/sync/ingest", "/api/v1/sync/config", "/api/v1/sync/media/**")
                                .hasAuthority("SYNC_SITE")
                        .requestMatchers("/actuator/prometheus").hasAnyAuthority("SYSTEM_MONITOR",
                                MetricsTokenFilter.AUTHORITY)
                        .requestMatchers("/actuator/**").hasAuthority("SYSTEM_MONITOR")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwt, loader), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new DeviceAuthenticationFilter(devices), JwtAuthenticationFilter.class)
                .addFilterAfter(new SiteKeyAuthenticationFilter(siteKeys), DeviceAuthenticationFilter.class)
                .addFilterAfter(new PasswordChangeRequiredFilter(mapper), SiteKeyAuthenticationFilter.class)
                .addFilterAfter(new MetricsTokenFilter(props.security().metricsToken()),
                        PasswordChangeRequiredFilter.class);
        return http.build();
    }

    /** Only the explicitly configured frontend origins may call the API from a browser. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(QmsProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.security().corsOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Device-Key", "X-Requested-With"));
        cfg.setExposedHeaders(List.of("Content-Disposition"));
        cfg.setAllowCredentials(false);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
