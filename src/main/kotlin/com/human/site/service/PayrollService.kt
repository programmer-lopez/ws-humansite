package com.human.site.service

import com.human.site.repository.IPayrollService
import com.human.site.repository.IPayrollScraper
import com.human.site.model.DownloadStatus
import com.human.site.model.PayrollFile
import com.human.site.model.ProcessState
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@Service
class PayrollService(
    private val scraper: IPayrollScraper
) : IPayrollService {

    private val currentStatus = AtomicReference(
        DownloadStatus(ProcessState.IDLE, "Listo para iniciar", LocalDateTime.now().toString())
    )

    override fun startDownloadProcess(): DownloadStatus {
        val status = currentStatus.get()
        if (status.state == ProcessState.RUNNING) {
            return status
        }

        updateStatus(ProcessState.RUNNING, "Iniciando proceso de descarga automatizada...")

        thread(start = true) {
            try {
                scraper.executeScraping()
                updateStatus(ProcessState.COMPLETED, "Proceso finalizado exitosamente.")
            } catch (e: Exception) {
                updateStatus(ProcessState.FAILED, "Error durante la ejecución: ${e.message}")
            }
        }

        return currentStatus.get()
    }

    override fun getCurrentStatus(): DownloadStatus {
        return currentStatus.get()
    }

    override fun getDownloadedFiles(): List<PayrollFile> {
        val pdfDir = Paths.get("pdf").toFile()
        if (!pdfDir.exists() || !pdfDir.isDirectory) {
            return emptyList()
        }

        return pdfDir.listFiles { file -> file.extension == "pdf" }
            ?.map {
                PayrollFile(
                    fileName = it.name,
                    sizeBytes = it.length(),
                    absolutePath = it.absolutePath
                )
            }
            ?: emptyList()
    }

    private fun updateStatus(state: ProcessState, message: String) {
        currentStatus.set(DownloadStatus(state, message, LocalDateTime.now().toString()))
    }
}
