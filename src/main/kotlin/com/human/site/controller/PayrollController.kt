package com.human.site.controller

import com.human.site.repository.IPayrollService
import com.human.site.model.DownloadStatus
import com.human.site.model.PayrollFile
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/payroll")
@Tag(name = "Payroll", description = "Endpoints para gestionar la descarga masiva de recibos de nómina")
class PayrollController(
    private val payrollService: IPayrollService
) {

    @PostMapping("/download")
    @Operation(summary = "Inicia el proceso asíncrono de descarga de PDFs", description = "Ejecuta un navegador headless (Playwright) para descargar todos los recibos de nómina.")
    fun startDownload(): ResponseEntity<DownloadStatus> {
        val status = payrollService.startDownloadProcess()
        return ResponseEntity.accepted().body(status)
    }

    @GetMapping("/status")
    @Operation(summary = "Obtiene el estado actual del proceso", description = "Permite consultar si el robot está ejecutándose, finalizó o falló.")
    fun getStatus(): ResponseEntity<DownloadStatus> {
        return ResponseEntity.ok(payrollService.getCurrentStatus())
    }

    @GetMapping("/files")
    @Operation(summary = "Lista los recibos de nómina descargados", description = "Muestra una lista de los archivos PDF encontrados en la carpeta local.")
    fun getFiles(): ResponseEntity<List<PayrollFile>> {
        return ResponseEntity.ok(payrollService.getDownloadedFiles())
    }
}
