package ph.gov.philhealth.qms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// No in-memory default user: authentication is JWT/device-key only (see SecurityConfig).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class QmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(QmsApplication.class, args);
    }
}
