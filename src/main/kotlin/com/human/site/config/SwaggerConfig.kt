package com.human.site.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SwaggerConfig {

    @Bean
    fun customOpenAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("HumanSite Payroll Downloader API")
                    .version("1.0.0")
                    .description("Microservicio para la descarga automatizada de recibos de nómina desde el portal de HumanSite.")
            )
    }
}
