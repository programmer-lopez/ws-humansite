package com.human.site.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.Cookie
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Service
class GeneXusPayrollService {

    private val objectMapper = jacksonObjectMapper()
    private val originUrl = "https://ahr.humansite.com.mx"
    private val portalPath = "/miportalmain.aspx"

    // WebClient solo para descarga de PDFs (una vez que tenemos la URL)
    private val webClient = WebClient.builder()
        .baseUrl(originUrl)
        .defaultHeader(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
        )
        .defaultHeader("Accept-Language", "es-419,es;q=0.9,en;q=0.8")
        .build()

    fun ejecutarDescargaMasiva(sessionCookieValue: String, totalFilas: Int) {
        val cookieHeader = if (sessionCookieValue.contains("=")) sessionCookieValue
        else "ASP.NET_SessionId=$sessionCookieValue"
        val gxTokenInicial = "QCyKkwxFP36mZG64jo29yLgY27CRSYnbxPMpPx/h1Lh0BOmcT5/ssipB9G+AOeGK"

        println("[*] Iniciando proceso automatizado por lotes...")

        Playwright.create().use { playwright ->
            val browser: Browser = playwright.chromium().launch(
                BrowserType.LaunchOptions().setHeadless(true)
            )

            browser.use { br ->
                val context = br.newContext()

                // Establecer cookies de sesión (valores URL-decoded para Playwright)
                val cookiesList = cookieHeader.split(";").mapNotNull { part ->
                    val kv = part.trim().split("=", limit = 2)
                    if (kv.size == 2) {
                        val name = kv[0].trim()
                        val value = try {
                            java.net.URLDecoder.decode(kv[1].trim(), "UTF-8")
                        } catch (e: Exception) {
                            kv[1].trim()
                        }
                        Cookie(name, value).apply {
                            domain = "ahr.humansite.com.mx"
                            path = "/"
                            secure = true
                            httpOnly = false
                            sameSite = com.microsoft.playwright.options.SameSiteAttribute.NONE
                        }
                    } else null
                }
                context.addCookies(cookiesList)
                println("[*] ${cookiesList.size} cookie(s) de sesión establecidas.")

                val page = context.newPage()

                // Navegar al portal con DOMCONTENTLOADED para no esperar scripts infinitos de GeneXus
                val targetUrl = "$originUrl$portalPath?$gxTokenInicial"
                println("[*] Navegando al portal: $targetUrl")
                try {
                    page.navigate(
                        targetUrl,
                        com.microsoft.playwright.Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(60000.0)
                    )
                    println("[*] DOM cargado. Esperando inicialización de GeneXus (5s)...")
                    Thread.sleep(5000)
                } catch (e: Exception) {
                    println("[-] Advertencia en navegación: ${e.message?.take(150)}")
                }

                // Verificar estado de la página
                val gxStateLength = (page.evaluate("document.getElementById('GXState')?.value?.length || 0") as? Number)?.toInt() ?: 0
                println("[*] GXState en DOM: $gxStateLength chars")

                if (gxStateLength < 10) {
                    println("[-] ADVERTENCIA: GXState vacío. Guardando screenshot para diagnóstico...")
                    try {
                        page.screenshot(
                            com.microsoft.playwright.Page.ScreenshotOptions()
                                .setPath(java.nio.file.Paths.get("screenshot_portal.png"))
                                .setFullPage(true)
                        )
                    } catch (e: Exception) { /* ignorar */ }
                    page.content().also { File("html_dom_renderizado.html").writeText(it) }
                    println("[-] Abortando: el portal no respondió correctamente con la sesión actual.")
                    return@use
                }

                println("[+] Portal cargado exitosamente. Iniciando descarga por lotes...")

                // Bucle de descarga por fila usando fetch() nativo del navegador
                for (i in 1..totalFilas) {
                    val rowIndex = String.format("%04d", i)
                    println("[*] Procesando fila: $rowIndex")

                    try {
                        // El POST se ejecuta DESDE DENTRO del contexto del navegador con fetch()
                        // Esto garantiza que todas las cookies, tokens AJAX y headers del SPA se envíen correctamente
                        val responseBody = page.evaluate("""
                            async () => {
                                try {
                                    // 1. Leer y modificar el GXState para el evento PRINTPDF
                                    const rawGxState = document.getElementById('GXState')?.value || '{}';
                                    const gxState = JSON.parse(rawGxState);
                                    gxState._EventName = "W0099E'PRINTPDF'.$rowIndex";
                                    gxState._EventGridId = '';
                                    gxState._EventRowId = '';

                                    // 2. Construir el cuerpo del formulario (idéntico al cURL del navegador)
                                    const params = new URLSearchParams();
                                    params.append('', '');
                                    params.append('MPW0005vMB_EPR_CODM', 'AHR');
                                    params.append('MPW0005vUSUIDM', 'ML17934');
                                    params.append('MPW0005vPERFILDSCM', 'EMPLEADO');
                                    params.append('vEMP_NIE', '17934');
                                    params.append('vNOMBRE', 'LOPEZ SANCHEZ MARCOS');
                                    params.append('W0099vANIO', '2026');
                                    params.append('W0099vPLAZANOMINAID', '30O');
                                    params.append('vMB_EPR_COD', 'AHR');
                                    params.append('vUSUID', 'ML17934');
                                    params.append('GXState', JSON.stringify(gxState));

                                    // 3. Construir la URL del POST con gx-no-cache (igual que el navegador)
                                    const formAction = document.querySelector('form#MAINFORM')?.action || window.location.href;
                                    const postUrl = formAction + ',gx-no-cache=' + Date.now();

                                    // 4. Recopilar cabeceras de seguridad de GeneXus
                                    const headers = {
                                        'Content-Type': 'application/x-www-form-urlencoded',
                                        'gxajaxrequest': '1',
                                        'X-Requested-With': 'XMLHttpRequest'
                                    };

                                    // Buscar el token AJAX en variables globales de GeneXus
                                    const tokenCandidates = ['gx_ajax_sec_token', 'GXSecurityToken', 'gxtoken'];
                                    for (const c of tokenCandidates) {
                                        if (window[c]) { headers['ajax_security_token'] = String(window[c]); break; }
                                    }

                                    // Buscar el auth token JWT de GeneXus
                                    const authCandidates = ['gx_auth_token', 'GXAuthToken'];
                                    for (const c of authCandidates) {
                                        if (window[c]) { headers['x-gxauth-token'] = String(window[c]); break; }
                                    }

                                    console.log('POST URL:', postUrl);
                                    console.log('ajax_security_token:', headers['ajax_security_token'] || 'NO ENCONTRADO');

                                    // 5. Ejecutar el fetch (las cookies se envían automáticamente - credentials: 'include')
                                    const response = await fetch(postUrl, {
                                        method: 'POST',
                                        headers: headers,
                                        body: params.toString(),
                                        credentials: 'include'
                                    });

                                    const responseText = await response.text();
                                    return JSON.stringify({
                                        status: response.status,
                                        ok: response.ok,
                                        body: responseText.substring(0, 2000)
                                    });
                                } catch(e) {
                                    return JSON.stringify({ status: 0, ok: false, body: 'ERROR_JS: ' + e.toString() });
                                }
                            }
                        """.trimIndent()) as? String ?: "{}"

                        // Parsear la respuesta del fetch
                        val resultMap = objectMapper.readValue(responseBody, Map::class.java)
                        val status = resultMap["status"] as? Int ?: 0
                        val ok = resultMap["ok"] as? Boolean ?: false
                        val body = resultMap["body"] as? String ?: ""

                        println("[*] Respuesta del servidor: HTTP $status")

                        if (ok) {
                            extraerYDescargarPdf(body, cookieHeader, rowIndex)
                        } else {
                            println("[-] Error HTTP $status en fila $rowIndex.")
                            println("    [Primeros 500 chars]: ${body.take(500)}")
                        }

                    } catch (e: Exception) {
                        println("[-] Error general en fila $rowIndex: ${e.message?.take(200)}")
                    }

                    Thread.sleep(3000)
                }
            }
        }
    }

    private fun extraerYDescargarPdf(jsonBody: String, cookieHeader: String, rowIndex: String) {
        val pattern = java.util.regex.Pattern.compile("(?i)\"URL\"\\s*:\\s*\"([^\"]+\\.pdf|[^\"]+blob[^\"]+)\"")
        val matcher = pattern.matcher(jsonBody)

        if (matcher.find()) {
            var rawUrl = matcher.group(1).replace("\\/", "/")
            val downloadUrl = if (rawUrl.startsWith("http")) rawUrl else "$originUrl/$rawUrl"
            println("[+] URL de descarga localizada: $downloadUrl")

            var pdfBytes: ByteArray? = null
            var success = false

            for (attempt in 1..5) {
                try {
                    println("[*] Intento de descarga $attempt/5...")
                    Thread.sleep(3000)

                    pdfBytes = webClient.get()
                        .uri(downloadUrl)
                        .header("Cookie", cookieHeader)
                        .retrieve()
                        .bodyToMono(ByteArray::class.java)
                        .block()

                    if (pdfBytes != null && pdfBytes.size > 4 &&
                        pdfBytes[0] == '%'.code.toByte() &&
                        pdfBytes[1] == 'P'.code.toByte() &&
                        pdfBytes[2] == 'D'.code.toByte() &&
                        pdfBytes[3] == 'F'.code.toByte()
                    ) {
                        success = true
                        break
                    } else {
                        println("[-] Archivo no listo aún. Reintentando...")
                    }
                } catch (e: Exception) {
                    println("[-] Error en intento $attempt: ${e.message?.take(100)}")
                }
            }

            if (success && pdfBytes != null) {
                val outputFile = File("recibo_nomina_$rowIndex.pdf")
                outputFile.writeBytes(pdfBytes)
                println("[!] ¡Éxito! PDF guardado: ${outputFile.absolutePath}")
            } else {
                println("[-] Se agotaron los intentos. El servidor no generó el PDF a tiempo.")
            }
        } else {
            println("[-] No se localizó URL de PDF en la respuesta AJAX.")
            println("    [Primeros 400 chars]: ${jsonBody.take(400)}")
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }
}