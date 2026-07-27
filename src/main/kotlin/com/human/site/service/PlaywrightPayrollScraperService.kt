package com.human.site.service

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.human.site.repository.IPayrollScraper
import com.human.site.config.HumansiteProperties
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths

@Service
class PlaywrightPayrollScraperService(
    private val properties: HumansiteProperties
) : IPayrollScraper {

    override fun executeScraping() {
        println("[*] Iniciando proceso automatizado por lotes con Interacción Visual...")

        Playwright.create().use { playwright ->
            val launchOptions =
                BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setArgs(
                        listOf(
                            "--disable-blink-features=AutomationControlled",
                            "--start-maximized",
                            "--disable-print-preview", // Bloqueamos la ventana de impresión desde el inicio
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
                    "Object.defineProperty(navigator, 'webdriver', {get: () => undefined}); window.print = function(){};"
                )

                val page = context.newPage()
                val loginUrl = "${properties.baseUrl}/hlogin.aspx"
                println("[*] Navegando a la pantalla de inicio de sesión ($loginUrl)...")

                try {
                    page.navigate(
                        loginUrl,
                        Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.LOAD)
                            .setTimeout(60000.0),
                    )

                    page.waitForSelector("#vUSUID")
                    page.locator("#vUSUID").fill(properties.credentials.username.uppercase())

                    page.waitForSelector("#vUSUPSW")
                    page.locator("#vUSUPSW").fill(properties.credentials.password)
                    page.locator("#vUSUPSW").press("Enter")

                    println("[*] Esperando validación de credenciales...")
                    page.waitForSelector(
                        "#W0099vANO_PROCF",
                        Page.WaitForSelectorOptions().setTimeout(45000.0),
                    )
                    println("[+] ¡Autenticación completada y panel de control visible!")

                    // OBTENER TODAS LAS FECHAS DE PAGO
                    val fechasPago = page.locator("#W0099vCPFECPAGF option").all()
                        .mapNotNull { it.getAttribute("value") }
                        .filter { it.contains("/") }

                    println("[*] Fechas de pago encontradas: $fechasPago")

                    // Crear carpeta pdf si no existe
                    val pdfDir = Paths.get(properties.downloadDir)
                    if (!Files.exists(pdfDir)) {
                        Files.createDirectories(pdfDir)
                    }

                    for (fechaVal in fechasPago) {
                        println("[*] Consultando Fecha de pago: $fechaVal...")
                        
                        page.selectOption("#W0099vCPFECPAGF", fechaVal)
                        page.locator("#W0099vCPFECPAGF").evaluate("el => el.dispatchEvent(new Event('change'))")
                        page.waitForTimeout(1000.0)

                        page.locator("#W0099BUTTON2").click()
                        page.waitForTimeout(3000.0)

                        // --- BUCLE DE PROCESAMIENTO DINÁMICO ---
                        val totalFilas = page.locator("img[id^='W0099vIMPR_']").count()
                        println("[*] Se encontraron $totalFilas recibos para la fecha $fechaVal")

                        for (i in 1..totalFilas) {
                            val rowIndex = String.format("%04d", i)
                            val pdfIconSelector = "#W0099vIMPR_$rowIndex, img[id='W0099vIMPR_$rowIndex']"

                            if (page.locator(pdfIconSelector).isVisible) {
                                try {
                                    val isPlaceholder = fechaVal.replace("/", "").trim().isEmpty()
                                    val fechaLimpia = if (isPlaceholder) "ultima_nomina" else fechaVal.replace("/", "_")
                                    val nombreArchivo = "recibo_nomina_Fecha_${fechaLimpia}_$rowIndex.pdf"
                                    val rutaDestino = pdfDir.resolve(nombreArchivo)

                                    println("[*] Fila $rowIndex detectada. Ejecutando extracción inteligente...")

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
                                    page.waitForTimeout(2500.0) // Tiempo de colchón para carga de scripts de GeneXus

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
                                        println("[!] ¡RECIBO DE LA FILA $rowIndex EXTRAÍDO CORRECTAMENTE! -> ${rutaDestino.fileName}")
                                    } else {
                                        throw Exception("El flujo binario regresó vacío.")
                                    }

                                    // 5. Cerramos la pestaña activa limpiamente
                                    try {
                                        nuevaPestana.close()
                                    } catch (_: Exception) {}
                                    println("[+] Fila $rowIndex completada con éxito.\n")
                                } catch (tabException: Exception) {
                                    println("[-] Error al procesar la fila $rowIndex: ${tabException.message}")
                                }
                            } else {
                                println("[-] ADVERTENCIA: El ícono de PDF no está visible para la fila $rowIndex.")
                            }
                            page.waitForTimeout(1000.0)
                        }
                    }

                    println("[+] Proceso de descarga masiva finalizado exitosamente.")
                } catch (e: Exception) {
                    println("[-] Error crítico durante el flujo automatizado: ${e.message}")
                    throw e
                }
            }
        }
    }
}
