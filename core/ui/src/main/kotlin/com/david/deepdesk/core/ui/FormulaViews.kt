package com.david.deepdesk.core.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color as AwtColor
import ru.noties.jlatexmath.awt.Insets

@Composable
internal fun LatexBlock(latex: String, display: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val textSize = with(density) { (if (display) 20.sp else 16.sp).toPx() }
    if (!display) {
        val bitmap = remember(latex, color, textSize) {
            renderLatexBitmap(context, latex, textSize, color, paddingPx = 2, display = false)
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = latex,
                contentScale = ContentScale.Fit,
                modifier = modifier.padding(vertical = 2.dp),
            )
        } else {
            Text(
                text = latexFallback(latex, display = false),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.padding(vertical = 4.dp),
            )
        }
        return
    }
    var widthPx by remember { mutableIntStateOf(0) }
    val bitmap = remember(latex, color, textSize, widthPx) {
        if (widthPx <= 0) return@remember null
        renderLatexBitmap(
            context = context,
            latex = latex,
            textSizePx = textSize,
            color = color,
            paddingPx = 8,
            display = true,
            maxWidthPx = widthPx,
        )
    }
    val scroll = rememberScrollState()
    val wider = bitmap != null && widthPx > 0 && bitmap.width > widthPx
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = latex,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .then(if (wider) Modifier.horizontalScroll(scroll) else Modifier),
            )
        } else if (widthPx > 0) {
            Text(
                text = latexFallback(latex, display = true),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

private val ceCommand = Regex("""\\ce\{([^{}]*)\}""")

/**
 * 行内公式把 `\displaystyle` 收成 `\textstyle`，避免一个分式把整句撑开。
 * 绘制仍用 DISPLAY：求和、积分的上下限留在符号上下，和官方一致。
 * `\ce{H2O}` 这条化学宏 JLatexMath 不认识，换成普通上下标。
 */
internal fun normalizeLatex(latex: String, display: Boolean): String {
    val withoutCe = ceCommand.replace(latex) { match -> ceToLatex(match.groupValues[1]) }
    return if (display) withoutCe else withoutCe.replace("\\displaystyle", "\\textstyle")
}

private fun ceToLatex(body: String): String = buildString {
    var i = 0
    while (i < body.length) {
        val ch = body[i]
        when {
            ch.isLetter() -> {
                append("\\mathrm{").append(ch).append('}')
                i++
            }
            ch.isDigit() -> {
                val start = i
                while (i < body.length && body[i].isDigit()) i++
                append("_{").append(body, start, i).append('}')
            }
            else -> i++
        }
    }
}

internal fun latexFallback(latex: String, display: Boolean): String {
    val mark = "\$".repeat(if (display) 2 else 1)
    return mark + latex + mark
}

internal fun renderLatexBitmap(
    context: Context,
    latex: String,
    textSizePx: Float,
    color: Int,
    paddingPx: Int,
    display: Boolean,
    maxWidthPx: Int = 0,
): Bitmap? {
    if (latex.isBlank()) return null
    val prepared = normalizeLatex(latex, display)
    val rendered = runCatching {
        drawLatex(context, prepared, textSizePx, color, paddingPx, display, maxWidthPx)
    }.onFailure { error ->
        Log.w("DeepDeskLatex", "render failed: ${error.javaClass.simpleName}: ${error.message} :: ${prepared.take(240)}")
    }.getOrNull()
    if (rendered != null || !display || maxWidthPx <= 0) return rendered
    return runCatching {
        drawLatex(context, prepared, textSizePx, color, paddingPx, display = true, maxWidthPx = 0)
    }.onFailure { error ->
        Log.w("DeepDeskLatex", "render retry failed: ${error.javaClass.simpleName}: ${error.message}")
    }.getOrNull()
}

private fun drawLatex(
    context: Context,
    latex: String,
    textSizePx: Float,
    color: Int,
    paddingPx: Int,
    display: Boolean,
    maxWidthPx: Int,
): Bitmap {
    JLatexMathAndroid.init(context.applicationContext)
    val formula = TeXFormula(latex)
    val style = TeXConstants.STYLE_DISPLAY
    val innerWidth = maxWidthPx - paddingPx * 2
    val icon = if (display && innerWidth > textSizePx) {
        formula.createTeXIcon(
            style,
            textSizePx,
            TeXConstants.UNIT_PIXEL,
            innerWidth.toFloat(),
            TeXConstants.ALIGN_CENTER,
            TeXConstants.UNIT_PIXEL,
            textSizePx * 0.45f,
        )
    } else {
        formula.createTeXIcon(style, textSizePx)
    }
    icon.setForeground(AwtColor(color))
    icon.setInsets(Insets(paddingPx, paddingPx, paddingPx, paddingPx))
    val w = icon.iconWidth.coerceAtLeast(1)
    val h = icon.iconHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val graphics = SolidGlyphGraphics()
    graphics.setCanvas(Canvas(bmp))
    icon.paintIcon(null, graphics, 0, 0)
    return bmp
}

/**
 * `\boxed` 先画矩形，会把画笔留在描边。引擎接着用同一支画笔画字母，
 * 字形就只剩轮廓。画字母前改回填充。
 */
private class SolidGlyphGraphics : AndroidGraphics2D() {
    override fun drawChars(chars: CharArray, offset: Int, length: Int, x: Int, y: Int) {
        paintField?.let { field ->
            (field.get(this) as? Paint)?.style = Paint.Style.FILL
        }
        super.drawChars(chars, offset, length, x, y)
    }

    private companion object {
        val paintField: java.lang.reflect.Field? = runCatching {
            AndroidGraphics2D::class.java.getDeclaredField("paint").apply { isAccessible = true }
        }.getOrNull()
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
