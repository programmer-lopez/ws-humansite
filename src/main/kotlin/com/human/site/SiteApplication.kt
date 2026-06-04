package com.human.site

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.human.site.service.GeneXusPayrollService
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class SiteApplication(
    private val payrollService: GeneXusPayrollService
) : CommandLineRunner {

    // CORRECCIÓN: Definimos el Bean de ObjectMapper para que Spring lo pueda inyectar en tu servicio
    @Bean
    fun objectMapper(): ObjectMapper {
        return jacksonObjectMapper()
    }

    override fun run(vararg args: String) {
        val sessionCookie = System.getenv("HUMANSITE_SESSION_COOKIE")
            ?: "GX_CLIENT_ID=d50eb382-0032-432d-ba38-bc54865951b5; GX_SESSION_ID=lhXevmO48STNRujp56B6Xk%2f%2fb9CFKwKJbddhsRucQu8%3d; ASP.NET_SessionId=fabs3edwo4raztaok0oacxoq"

        println("[*] Iniciando proceso automatizado por lotes...")

        // Ejecutamos la automatización para las primeras 5 filas detectadas en tu Grid3
        payrollService.ejecutarDescargaMasiva(sessionCookie, totalFilas = 5)
    }
}

fun main(args: Array<String>) {
    runApplication<SiteApplication>(*args)
}