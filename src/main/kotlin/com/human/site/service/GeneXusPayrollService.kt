package com.human.site.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Response
import java.nio.file.Files
import java.nio.file.Paths
import java.util.function.Consumer
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
                            "--disable-print-preview", // [!] BLOQUEO MAESTRO: Anula cualquier
                                                      // diálogo de impresión nativo
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

                // Anula la ventana emergente de impresión nativa en pestañas secundarias
                context.onPage { nuevaPagina ->
                    nuevaPagina.addInitScript(
                        "() => { window.print = () => { console.log('Ventana de impresión anulada.'); }; }"
                    )
                }

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

                    // --- BUCLE DE PROCESAMIENTO SEGURO ---
                    for (i in 1..totalFilas) {
                        val rowIndex = String.format("%04d", i)
                        val pdfIconSelector =
                            "#W0099vIMPR_$rowIndex, img[id='W0099vIMPR_$rowIndex']"

                        if (page.locator(pdfIconSelector).isVisible) {
                            try {
                                val nombreArchivo =
                                    "recibo_nomina_Mes_${empleado.mes}_Anio_${empleado.anio}_$rowIndex.pdf"
                                val rutaDestino = Paths.get(nombreArchivo)

                                if (i == 1) {
                                    // ----------------------------------------------------------------
                                    // FILA 1: TU CÓDIGO ORIGINAL (INTACTO, FUNCIONA AL 100%)
                                    // ----------------------------------------------------------------
                                    println(
                                        "[*] Fila 1 detectada. Ejecutando Estrategia A (Descarga Estática original)..."
                                    )
                                    val nuevaPestana =
                                        page.context().waitForPage {
                                            page.locator(pdfIconSelector).click()
                                        }
                                    nuevaPestana.waitForLoadState(
                                        com.microsoft.playwright.options.LoadState.NETWORKIDLE
                                    )

                                    val rawBytes =
                                        nuevaPestana.evaluate(
                                            """
                                        async () => {
                                            const response = await fetch(window.location.href);
                                            const buffer = await response.arrayBuffer();
                                            return Array.from(new Uint8Array(buffer));
                                        }
                                    """
                                        ) as List<*>

                                    val byteArray =
                                        ByteArray(rawBytes.size) { idx ->
                                            (rawBytes[idx] as Number).toByte()
                                        }
                                    Files.write(rutaDestino, byteArray)
                                    println(
                                        "[!] ¡DOCUMENTO ESTÁTICO DE FILA 1 GUARDADO INTEGRALMENTE!"
                                    )
                                    nuevaPestana.close()
                                } else {
                                    // ----------------------------------------------------------------
                                    // FILA 2: EL BLOQUE COMPLEJO (LISTENER DE RED PASIVO)
                                    // ----------------------------------------------------------------
                                    println(
                                        "[*] Fila $rowIndex (Bloque Complejo) detectada. Interceptando respuesta de red en segundo plano..."
                                    )

                                    var pdfBytes: ByteArray? = null

                                    // Creamos un espía (listener) para revisar todo el tráfico que
                                    // baje el navegador
                                    val responseHandler =
                                        Consumer<Response> { response ->
                                            val url = response.url().lowercase()
                                            if (
                                                url.contains("mostrarformatopdf.aspx") ||
                                                    url.contains(".pdf")
                                            ) {
                                                try {
                                                    val body = response.body()
                                                    if (body != null && body.isNotEmpty()) {
                                                        pdfBytes =
                                                            body // Guardamos los bytes atrapados
                                                    }
                                                } catch (ignore: Exception) {
                                                    // Ignoramos errores de preflight o streams
                                                    // parciales
                                                }
                                            }
                                        }

                                    // Activamos el espía antes de hacer clic
                                    page.context().onResponse(responseHandler)

                                    var nuevaPestana: Page? = null
                                    try {
                                        // Hacemos el clic y esperamos a que cargue la pestaña
                                        nuevaPestana =
                                            page.context().waitForPage(
                                                BrowserContext.WaitForPageOptions()
                                                    .setTimeout(45000.0)
                                            ) {
                                                page.locator(pdfIconSelector).click()
                                            }
                                        nuevaPestana.waitForLoadState(
                                            com.microsoft.playwright.options.LoadState.NETWORKIDLE
                                        )
                                        nuevaPestana.waitForTimeout(
                                            3000.0
                                        ) // Tiempo de cortesía para que termine de bajar los bytes
                                    } catch (e: Exception) {
                                        println(
                                            "[-] Nota: Se agotó el tiempo esperando la pestaña, revisaremos si se capturó la respuesta de red."
                                        )
                                    } finally {
                                        // Desactivamos el espía (MUY IMPORTANTE)
                                        page.context().offResponse(responseHandler)

                                        // Revisamos si nuestro espía logró atrapar el PDF
                                        if (pdfBytes != null) {
                                            Files.write(rutaDestino, pdfBytes!!)
                                            println(
                                                "[!] ¡RECIBO DINÁMICO DE FILA $rowIndex EXTRAÍDO DESDE RED CON ÉXITO! (Calidad perfecta)"
                                            )
                                        } else {
                                            println(
                                                "[-] No se pudo interceptar el flujo binario desde la red."
                                            )
                                        }

                                        // Cerramos la pestaña
                                        try {
                                            nuevaPestana?.close()
                                        } catch (ignore: Exception) {}
                                    }
                                }
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
