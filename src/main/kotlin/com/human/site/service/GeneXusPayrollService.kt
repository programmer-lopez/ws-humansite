package com.human.site.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.io.File
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
                        listOf("--disable-blink-features=AutomationControlled", "--start-maximized")
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

                // BLINDAJE ANTICIPADO: Desactiva el cuadro de diálogo de impresión de Chrome en las
                // pestañas secundarias
                context.onPage { nuevaPagina ->
                    nuevaPagina.addInitScript(
                        "() => { window.print = () => { console.log('Ventana de impresión anulada preventivamente.'); }; }"
                    )
                }

                val page = context.newPage()
                val loginUrl = "$originUrl/hlogin.aspx"
                println("[*] Navegando a la pantalla de inicio de sesión: $loginUrl")

                try {
                    page.navigate(
                        loginUrl,
                        Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.LOAD)
                            .setTimeout(60000.0),
                    )

                    println("[*] Pantalla de login detectada. Insertando credenciales...")
                    page.waitForSelector("#vUSUID")
                    page.locator("#vUSUID").fill(empleado.idUsuario.uppercase())

                    page.waitForSelector("#vUSUPSW")
                    page.locator("#vUSUPSW").fill(contrasenaUsuario)

                    println("[*] Enviando formulario mediante pulsación Enter...")
                    page.locator("#vUSUPSW").press("Enter")

                    println(
                        "[*] Esperando que el portal procese el login y monte los componentes..."
                    )
                    page.waitForSelector(
                        "#W0099vANO_PROCF",
                        Page.WaitForSelectorOptions().setTimeout(45000.0),
                    )
                    println("[+] ¡Autenticación completada y panel de control visible!")

                    // --- MANIPULACIÓN DE FILTROS REALES (MES Y AÑO) ---
                    println("[*] Seleccionando Mes en el filtro visual: ${empleado.mes}")
                    page.selectOption("#W0099vMESF", empleado.mes)
                    page
                        .locator("#W0099vMESF")
                        .evaluate("el => el.dispatchEvent(new Event('change'))")
                    page.waitForTimeout(1500.0)

                    println("[*] Seleccionando Año en el filtro visual: ${empleado.anio}")
                    page.selectOption("#W0099vANO_PROCF", empleado.anio)
                    page
                        .locator("#W0099vANO_PROCF")
                        .evaluate("el => el.dispatchEvent(new Event('change'))")
                    page.waitForTimeout(1500.0)

                    println("[*] Presionando botón 'BUSCAR'...")
                    page.locator("#W0099BUTTON2").click()

                    println("[*] Esperando actualización de la lista de recibos en pantalla...")
                    page.waitForTimeout(5000.0)

                    // --- BUCLE DE PROCESAMIENTO INDEPENDIENTE ---
                    for (i in 1..totalFilas) {
                        val rowIndex = String.format("%04d", i)
                        println("[*] Procesando recibo de la fila indexada: $rowIndex")

                        val pdfIconSelector =
                            "#W0099vIMPR_$rowIndex, img[id='W0099vIMPR_$rowIndex']"

                        if (page.locator(pdfIconSelector).isVisible) {
                            try {
                                val nombreArchivo =
                                    "recibo_nomina_Mes_${empleado.mes}_Anio_${empleado.anio}_$rowIndex.pdf"
                                val rutaDestino = Paths.get(nombreArchivo)

                                // 1. Interceptamos la apertura de la nueva pestaña
                                val nuevaPestana =
                                    page.context().waitForPage {
                                        page.locator(pdfIconSelector).click()
                                    }

                                // Espera prudencial para que resuelva la redirección interna del
                                // IIS
                                nuevaPestana.waitForLoadState(
                                    com.microsoft.playwright.options.LoadState.NETWORKIDLE
                                )
                                nuevaPestana.waitForTimeout(3000.0)

                                val urlDocumento = nuevaPestana.url().lowercase()
                                println(
                                    "[*] URL detectada en pestaña secundaria: ${nuevaPestana.url()}"
                                )

                                // 2. Enrutamiento 100% independiente basado en la URL destino
                                if (
                                    urlDocumento.contains("/cargas/") ||
                                        urlDocumento.contains(".pdf")
                                ) {
                                    // CASO 1: Redirige a un archivo físico .pdf (Código funcional
                                    // original sin contaminar)
                                    procesarReciboEstatico(nuevaPestana, rutaDestino)
                                } else {
                                    // CASO 2: Redirige al script .aspx (Código nuevo calibrado para
                                    // pantallas anchas/CFDI)
                                    procesarReciboDinamico(nuevaPestana, rutaDestino)
                                }

                                // 3. Cierre limpio de la pestaña procesada
                                nuevaPestana.close()
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

                        page.waitForTimeout(4000.0)
                    }
                    println("[+] Proceso de descarga masiva finalizado exitosamente.")
                } catch (e: Exception) {
                    println("[-] Error crítico durante el flujo automatizado: ${e.message}")
                    val htmlFallo = page.content()
                    File("debug_portal_error.html").writeText(htmlFallo)
                }
            }
        }
    }

    /**
     * ESTRATEGIA A: Extracción binaria directa mediante JS Fetch. Diseñado exclusivamente para el
     * archivo PDF físico estático (Fila 1). Mantiene el archivo original intacto, sin alteraciones
     * y legible en Adobe Reader.
     */
    private fun procesarReciboEstatico(pestana: Page, rutaDestino: java.nio.file.Path) {
        println("[*] -> Ejecutando Estrategia A: Descarga binaria pura de PDF estático...")

        val rawBytes =
            pestana.evaluate(
                """
            async () => {
                const response = await fetch(window.location.href);
                const buffer = await response.arrayBuffer();
                return Array.from(new Uint8Array(buffer));
            }
        """
            ) as List<*>

        val byteArray = ByteArray(rawBytes.size) { idx -> (rawBytes[idx] as Number).toByte() }
        Files.write(rutaDestino, byteArray)
        println("[!] ¡DOCUMENTO ESTÁTICO GUARDADO INTEGRALMENTE!")
    }

    /**
     * ESTRATEGIA B — Triple Bypass para ASPX Dinámico (Fila 2).
     *
     * Problema raíz: `page.pdf()` de Chromium activa internamente `@media print` SIEMPRE,
     * ignorando `emulateMedia(SCREEN)`. GeneXus inyecta reglas `@media print` que colapsan
     * el layout de dos columnas (Percepciones | Deducciones + Puesto).
     *
     * Solución en tres capas quirúrgicas antes de llamar a page.pdf():
     *
     *   Capa 1 — PURGA DE CSSOM: Itera todos los CSSStyleSheet del documento y elimina
     *             físicamente cada CSSMediaRule cuya condición contenga "print". Esto
     *             anula en memoria las hojas compiladas por GeneXus sin tocar el DOM.
     *
     *   Capa 2 — INYECCIÓN DE OVERRIDE: Inserta un <style> con `@media print` propio
     *             al final del <head>, con mayor especificidad (selectores !important)
     *             que fuerza el layout de pantalla: floats, widths, display, overflow.
     *
     *   Capa 3 — PDF LANDSCAPE A4: Renderiza en orientación horizontal para disponer
     *             de ≈1123px de ancho útil; sin escala < 1 para no comprimir tipografía.
     */
    private fun procesarReciboDinamico(pestana: Page, rutaDestino: java.nio.file.Path) {
        println("[*] -> Ejecutando Estrategia B (Triple Bypass) para vista ASPX dinámica...")

        // Viewport ancho para que los elementos responsivos se expandan al máximo antes del PDF
        pestana.setViewportSize(1920, 1080)
        pestana.waitForTimeout(1500.0)

        // ── CAPA 1: Purga quirúrgica de @media print del CSSOM de GeneXus ──────────────────────
        println("[*]    Capa 1: Purgando reglas @media print del CSSOM...")
        pestana.evaluate(
            """
            () => {
                let removedCount = 0;
                for (const sheet of Array.from(document.styleSheets)) {
                    try {
                        const rules = Array.from(sheet.cssRules || []);
                        // Recorremos en reversa para no alterar índices al borrar
                        for (let i = rules.length - 1; i >= 0; i--) {
                            const rule = rules[i];
                            // CSSMediaRule tiene type === 4
                            if (rule.type === CSSRule.MEDIA_RULE) {
                                const mediaText = rule.media?.mediaText || '';
                                if (mediaText.includes('print')) {
                                    sheet.deleteRule(i);
                                    removedCount++;
                                }
                            }
                        }
                    } catch (e) {
                        // Hojas cross-origin lanzan SecurityError, las ignoramos
                    }
                }
                console.log('[Capa1] Reglas @media print eliminadas del CSSOM: ' + removedCount);
            }
            """.trimIndent()
        )

        // ── CAPA 2: Inyección de hoja de estilos override de alta especificidad ────────────────
        println("[*]    Capa 2: Inyectando CSS override de layout de pantalla...")
        pestana.evaluate(
            """
            () => {
                const css = `
                    /* ── Override GeneXus @media print — generado por scraper Kotlin ── */
                    @media print {

                        /* Eliminar saltos de página automáticos entre columnas */
                        * {
                            page-break-inside: avoid !important;
                            break-inside: avoid !important;
                        }

                        /* Tamaño de página personalizado: landscape A4 (297mm x 210mm) */
                        @page {
                            size: A4 landscape;
                            margin: 8mm 10mm 8mm 10mm;
                        }

                        /* Ancho del body y contenedor raíz al 100% del papel */
                        html, body {
                            width: 100% !important;
                            max-width: 100% !important;
                            overflow: visible !important;
                            margin: 0 !important;
                            padding: 0 !important;
                        }

                        /* Preservar floats y anchos de columnas laterales */
                        table, tr, td, th {
                            display: revert !important;
                            width: auto !important;
                            max-width: none !important;
                            overflow: visible !important;
                            white-space: normal !important;
                        }

                        /* Columnas flotantes: mantener el layout side-by-side */
                        td[width], th[width],
                        [style*="float: left"], [style*="float:left"],
                        [style*="float: right"], [style*="float:right"] {
                            float: revert !important;
                            width: revert !important;
                            max-width: none !important;
                            overflow: visible !important;
                        }

                        /* Tablas principales: expandir al 100% del contenedor */
                        table[width="100%"], table[style*="width:100%"],
                        table[style*="width: 100%"] {
                            width: 100% !important;
                            table-layout: auto !important;
                        }

                        /* Suprimir elementos de UI que no pertenecen al recibo */
                        button, input[type="button"], input[type="submit"],
                        .no-print, #btnImprimir {
                            display: none !important;
                        }
                    }
                `;
                const styleEl = document.createElement('style');
                styleEl.setAttribute('id', 'scraper-print-override');
                styleEl.setAttribute('media', 'all');
                styleEl.textContent = css;
                document.head.appendChild(styleEl);
                console.log('[Capa2] Hoja override inyectada correctamente.');
            }
            """.trimIndent()
        )

        // Pausa para que Blink re-calcule el árbol de renderizado con los estilos actualizados
        pestana.waitForTimeout(1500.0)

        // ── CAPA 3: Exportación PDF en A4 Landscape sin compresión de escala ─────────────────
        println("[*]    Capa 3: Generando PDF en A4 Landscape sin compresión de escala...")
        pestana.pdf(
            Page.PdfOptions()
                .setPath(rutaDestino)
                .setFormat("A4")
                .setLandscape(true)       // 297mm × 210mm → ≈1123px útiles a 96dpi
                .setScale(1.0)            // Sin compresión: tipografía y celdas en tamaño real
                .setDisplayHeaderFooter(false)
                .setPrintBackground(true)
                .setMargin(
                    com.microsoft.playwright.options.Margin()
                        .setTop("8mm")
                        .setBottom("8mm")
                        .setLeft("10mm")
                        .setRight("10mm")
                )
        )
        println("[!] ¡RECIBO DINÁMICO GENERADO — COLUMNAS ALINEADAS, SIN CORTES LATERALES!")
    }
}
