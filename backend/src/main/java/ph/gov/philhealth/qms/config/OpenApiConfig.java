package ph.gov.philhealth.qms.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI qmsOpenApi() {
        return new OpenAPI()
                .info(new Info().title("PhilHealth QMS API").version("v1")
                        .description("Integrated Queueing Management System REST API. See docs/api-contract.md."))
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("deviceKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Device-Key"))
                        .addSecuritySchemes("siteKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Site-Key")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
