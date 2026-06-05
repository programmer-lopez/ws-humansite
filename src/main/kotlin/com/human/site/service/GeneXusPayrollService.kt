package com.human.site.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.nio.file.Files
import java.nio.file.Paths
import org.springframework.stereotype.Service

@Service
class GeneXusPayrollService {

    private val objectMapper = jacksonObjectMapper()
    private val originUrl = "https://ahr.humansite.com.mx"

    data class EmpleadoInfo(
        val idUsuario: String,
        val nie: String,
        val nombre: String,
        val plazaId: String,
        val anio: String = "2026",
        val mes: String = "5",
    )

    fun ejecutarDescargaMasiva(totalFilas: Int, empleado: EmpleadoInfo, contrasenaUsuario: String) {
        println("[*] Iniciando proceso automatizado por lotes con Interacción Visual...")

        Playwright.create().use { playwright ->
            val launchOptions =
                BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setArgs(
                        listOf(
                            "--disable-blink-features=AutomationControlled",
                            "--start-maximized",
                            "--disable-print-preview", // Bloqueamos la ventana de impresión desde el
                            // inicio
                        )
                    )

            val browser: Browser = playwright.chromium().launch(launchOptions)

            browser.use { br ->
                val contextOptions =
                    Browser.NewContextOptions()
                        .setUserAgent(
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
                        )
                        .setViewportSize(1366, 768)

                val context = br.newContext(contextOptions)
                context.addInitScript(
                    "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"
                )

                val page = context.newPage()
                val loginUrl = "$originUrl/hlogin.aspx"
                println("[*] Navegando a la pantalla de inicio de sesión...")

                try {
                    page.navigate(
                        loginUrl,
                        Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.LOAD)
                            .setTimeout(60000.0),
                    )

                    page.waitForSelector("#vUSUID")
                    page.locator("#vUSUID").fill(empleado.idUsuario.uppercase())

                    page.waitForSelector("#vUSUPSW")
                    page.locator("#vUSUPSW").fill(contrasenaUsuario)
                    page.locator("#vUSUPSW").press("Enter")

                    println("[*] Esperando validación de credenciales...")
                    page.waitForSelector(
                        "#W0099vANO_PROCF",
                        Page.WaitForSelectorOptions().setTimeout(45000.0),
                    )
                    println("[+] ¡Autenticación completada y panel de control visible!")

                    // --- MANIPULACIÓN DE FILTROS ---
                    page.selectOption("#W0099vMESF", empleado.mes)
                    page
                        .locator("#W0099vMESF")
                        .evaluate("el => el.dispatchEvent(new Event('change'))")
                    page.waitForTimeout(1500.0)

                    page.selectOption("#W0099vANO_PROCF", empleado.anio)
                    page
                        .locator("#W0099vANO_PROCF")
                        .evaluate("el => el.dispatchEvent(new Event('change'))")
                    page.waitForTimeout(1500.0)

                    println("[*] Presionando botón 'BUSCAR'...")
                    page.locator("#W0099BUTTON2").click()
                    page.waitForTimeout(5000.0)

                    // --- BUCLE DE PROCESAMIENTO UNIFICADO (CORREGIDO Y BLINDADO) ---
                    for (i in 1..totalFilas) {
                        val rowIndex = String.format("%04d", i)
                        val pdfIconSelector =
                            "#W0099vIMPR_$rowIndex, img[id='W0099vIMPR_$rowIndex']"

                        if (page.locator(pdfIconSelector).isVisible) {
                            try {
                                val nombreArchivo =
                                    "recibo_nomina_Mes_${empleado.mes}_Anio_${empleado.anio}_$rowIndex.pdf"
                                val rutaDestino = Paths.get(nombreArchivo)

                                println(
                                    "[*] Fila $rowIndex detectada. Ejecutando extracción inteligente..."
                                )

                                // 1. Hacemos clic y esperamos la apertura de la nueva pestaña
                                val nuevaPestana =
                                    page.context().waitForPage(
                                        BrowserContext.WaitForPageOptions().setTimeout(60000.0)
                                    ) {
                                        page.locator(pdfIconSelector).click()
                                    }

                                // 2. Esperamos a que la pestaña asiente su carga básica
                                nuevaPestana.waitForLoadState(
                                    com.microsoft.playwright.options.LoadState.LOAD
                                )
                                page.waitForTimeout(
                                    2500.0
                                ) // Tiempo de colchón para carga de scripts de GeneXus

                                // 3. ESTRATEGIA INTELIGENTE AUTOMÁTICA (Sintaxis JS Pura .includes)
                                val rawBytes =
                                    nuevaPestana.evaluate(
                                        """
                                    async () => {
                                        let urlObjetivo = window.location.href;
                                        
                                        // Validamos usando sintaxis JavaScript nativa (.includes)
                                        if (urlObjetivo.toLowerCase().includes("mostrarformatopdf") || document.getElementById("PDFtoPrint")) {
                                            const iframe = document.getElementById("PDFtoPrint");
                                            if (iframe && iframe.src) {
                                                // Extraemos la URL y le removemos el '#toolbar=0' para obtener el PDF limpio
                                                urlObjetivo = iframe.src.split('#')[0];
                                            } else {
                                                const spanLink = document.getElementById("span_vLIGAPDF");
                                                if (spanLink && spanLink.innerText) {
                                                    urlObjetivo = spanLink.innerText.trim();
                                                }
                                            }
                                        }
                                        
                                        // Hacemos el fetch al binario real del PDF directamente desde el navegador
                                        const response = await fetch(urlObjetivo);
                                        const buffer = await response.arrayBuffer();
                                        return Array.from(new Uint8Array(buffer));
                                    }
                                """
                                    ) as List<*>

                                // 4. Conversión y escritura a disco
                                val byteArray =
                                    ByteArray(rawBytes.size) { idx ->
                                        (rawBytes[idx] as Number).toByte()
                                    }

                                if (byteArray.isNotEmpty()) {
                                    Files.write(rutaDestino, byteArray)
                                    println(
                                        "[!] ¡RECIBO DE LA FILA $rowIndex EXTRAÍDO CORRECTAMENTE! -> ${rutaDestino.fileName}"
                                    )
                                } else {
                                    throw Exception("El flujo binario regresó vacío.")
                                }

                                // 5. Cerramos la pestaña activa limpiamente
                                try {
                                    nuevaPestana.close()
                                } catch (_: Exception) {}
                                println("[+] Fila $rowIndex completada con éxito.\n")
                            } catch (tabException: Exception) {
                                println(
                                    "[-] Error al procesar la fila $rowIndex: ${tabException.message}"
                                )
                            }
                        } else {
                            println(
                                "[-] ADVERTENCIA: El ícono de PDF no está visible para la fila $rowIndex."
                            )
                        }
                        page.waitForTimeout(2000.0)
                    }

                    println("[+] Proceso de descarga masiva finalizado exitosamente.")
                } catch (e: Exception) {
                    println("[-] Error crítico durante el flujo automatizado: ${e.message}")
                }
            }
        }
    }
}
