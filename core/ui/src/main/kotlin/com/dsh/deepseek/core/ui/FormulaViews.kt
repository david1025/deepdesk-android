package com.dsh.deepseek.core.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

@Composable
internal fun LatexBlock(latex: String, display: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val bitmap = remember(latex, display, color) {
        if (latex.isBlank()) return@remember null
        runCatching {
            JLatexMathAndroid.init(context.applicationContext)
            val textSize = with(density) { (if (display) 20.sp else 16.sp).toPx() }
            val drawable = JLatexMathDrawable.builder(latex)
                .textSize(textSize)
                .color(color)
                .padding(if (display) 8 else 2)
                .align(if (display) JLatexMathDrawable.ALIGN_CENTER else JLatexMathDrawable.ALIGN_LEFT)
                .build()
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bmp
        }.getOrNull()
    }
    if (bitmap != null) {
        val imageMod = if (display) {
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .horizontalScroll(rememberScrollState())
        } else {
            modifier.padding(vertical = 2.dp)
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = latex,
            contentScale = ContentScale.Fit,
            modifier = imageMod,
        )
    } else {
        Text(
            text = if (display) "$$latex$$" else "$$latex$",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
internal fun MermaidBlock(source: String, modifier: Modifier = Modifier) {
    var png by remember(source) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val bmp = png
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "图表",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            failed -> Text(
                text = source,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            else -> {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                HiddenMermaidWebView(
                    source = source,
                    dark = dark,
                    onPng = { png = it },
                    onError = { failed = true },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HiddenMermaidWebView(
    source: String,
    dark: Boolean,
    onPng: (Bitmap) -> Unit,
    onError: () -> Unit,
) {
    val html = remember(source, dark) { mermaidHtml(source, dark) }
    val main = remember { Handler(Looper.getMainLooper()) }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(
                    MermaidBridge(
                        onPng = { dataUrl ->
                            val bmp = decodePngDataUrl(dataUrl)
                            main.post {
                                if (bmp != null) onPng(bmp) else onError()
                            }
                        },
                        onError = { main.post(onError) },
                    ),
                    "Android",
                )
                webViewClient = object : WebViewClient() {}
                loadDataWithBaseURL(
                    "https://cdn.jsdelivr.net/",
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
        modifier = Modifier.size(1.dp),
        onRelease = { it.destroy() },
    )
}

internal class MermaidBridge(
    private val onPng: (String) -> Unit,
    private val onError: () -> Unit,
) {
    @JavascriptInterface
    fun onPng(dataUrl: String) = onPng.invoke(dataUrl)

    @JavascriptInterface
    fun onError(message: String) = onError.invoke()
}

private fun decodePngDataUrl(dataUrl: String): Bitmap? {
    val b64 = dataUrl.substringAfter("base64,", missingDelimiterValue = "").trim()
    if (b64.isEmpty()) return null
    return runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

private fun mermaidHtml(source: String, dark: Boolean): String {
    val srcJs = toJsString(source)
    val theme = if (dark) "dark" else "neutral"
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1"/>
        <script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js"></script>
        </head>
        <body>
        <script>
        (function() {
          var src = $srcJs;
          function fail(e) {
            try { Android.onError(String(e)); } catch (x) {}
          }
          try {
            mermaid.initialize({ startOnLoad: false, theme: '$theme', securityLevel: 'strict' });
            mermaid.render('mmd', src).then(function(out) {
              var wrap = document.createElement('div');
              wrap.innerHTML = out.svg;
              document.body.appendChild(wrap);
              var svg = wrap.querySelector('svg');
              if (!svg) { fail('no-svg'); return; }
              var xml = new XMLSerializer().serializeToString(svg);
              var img = new Image();
              img.onload = function() {
                var w = img.width || 800;
                var h = img.height || 400;
                var c = document.createElement('canvas');
                c.width = w;
                c.height = h;
                var ctx = c.getContext('2d');
                ctx.drawImage(img, 0, 0);
                Android.onPng(c.toDataURL('image/png'));
              };
              img.onerror = function() { fail('image'); };
              img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(xml);
            }).catch(fail);
          } catch (e) { fail(e); }
        })();
        </script>
        </body>
        </html>
    """.trimIndent()
}

internal fun toJsString(s: String): String = buildString(s.length + 2) {
    append('"')
    for (c in s) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '<' -> append("\\u003c")
            '>' -> append("\\u003e")
            '&' -> append("\\u0026")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}
