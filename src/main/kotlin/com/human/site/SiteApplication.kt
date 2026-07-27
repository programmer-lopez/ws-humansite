package com.human.site

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.human.site.service.GeneXusPayrollService
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class SiteApplication(private val payrollService: GeneXusPayrollService) : CommandLineRunner {

    @Bean
    fun objectMapper(): ObjectMapper {
        return jacksonObjectMapper()
    }

    override fun run(vararg args: String) {
        println("[*] Cargando datos de MARCOS LOPEZ SANCHEZ para el login y payload AJAX...")

        // Credenciales y datos del empleado objetivo
        val usuarioTarget = "ML17934"
        val contrasenaTarget = "5314Humansite"

        // Incorporación de tus datos extraídos del GXState / Payload
        val empleadoTarget =
            GeneXusPayrollService.EmpleadoInfo(
                idUsuario = usuarioTarget, // MPW0005vUSUIDM / vUSUID
                nie = "17934", // vEMP_NIE / W0101vUSUEMPNIE
                nombre = "LOPEZ SANCHEZ MARCOS", // vNOMBRE
                plazaId = "30O", // W0099vPLAZANOMINAID (O de Ojo, no cero)
                anio = "2026", // W0099vANIO
            )

        println("[*] Iniciando proceso automatizado por lotes con Login Orgánico...")

        // Nota: Tu análisis muestra "W0099nRC_GXsfl_22 : 2", lo que significa que tienes
        // 2 recibos cargados en la página actual. Procesaremos esos 2 de golpe.
        payrollService.ejecutarDescargaMasiva(
            empleado = empleadoTarget,
            contrasenaUsuario = contrasenaTarget,
        )
    }
}

fun main(args: Array<String>) {
    runApplication<SiteApplication>(*args)
}
