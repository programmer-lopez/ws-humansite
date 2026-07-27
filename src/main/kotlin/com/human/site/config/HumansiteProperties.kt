package com.human.site.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "humansite")
class HumansiteProperties {
    var credentials = Credentials()
    var baseUrl: String = ""
    var downloadDir: String = ""

    class Credentials {
        var username: String = ""
        var password: String = ""
    }
}
