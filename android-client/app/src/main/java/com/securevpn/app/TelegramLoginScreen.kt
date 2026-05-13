package com.securevpn.app

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.securevpn.app.data.TelegramAuthData
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TelegramLoginScreen(
    nightTheme: Boolean,
    onClose: () -> Unit,
    onAuth: (TelegramAuthData) -> Unit
) {
    val primary = if (nightTheme) Gold else Burgundy
    val text = if (nightTheme) BoneLight else Ink
    val soft = if (nightTheme) Bone else InkSoft
    val panel = if (nightTheme) Color(0xFF200808) else Color(0xFFE9DDC0)
    val html = remember { telegramLoginHtml(BuildConfig.TELEGRAM_BOT_USERNAME) }

    PosterFrame(background = if (nightTheme) BurgundyDark else Paper, dark = nightTheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
                .padding(top = 12.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScreenHeader(
                title = "ТЕЛЕГРАМЪ\nПРОПУСКЪ",
                subtitle = "войдите черезъ ботъ для привязки тарифовъ",
                color = primary,
                subtitleColor = soft,
                titleSize = 25
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(2.dp, primary)
                    .background(panel)
                    .padding(3.dp)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            addJavascriptInterface(
                                TelegramAuthBridge { payload -> onAuth(payload) },
                                "SecureVpnTelegram"
                            )
                            loadDataWithBaseURL(
                                BuildConfig.API_BASE_URL,
                                html,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .border(2.dp, primary)
                    .background(if (nightTheme) Burgundy else Color.White.copy(alpha = 0.38f))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "ОТМЕНА",
                    color = primary,
                    fontFamily = Russo,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClose() }
                )
            }
            Text(
                "после подтверждения Telegram выдастъ подписанный пропускъ",
                color = text,
                fontFamily = Playfair,
                fontStyle = FontStyle.Italic,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun JSONObject.optStringOrNull(name: String): String? {
    return optString(name).takeIf { it.isNotBlank() }
}

private class TelegramAuthBridge(
    private val onAuth: (TelegramAuthData) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onTelegramAuth(rawJson: String) {
        val json = JSONObject(rawJson)
        val payload = TelegramAuthData(
            id = json.getLong("id"),
            authDate = json.getLong("auth_date"),
            hash = json.getString("hash"),
            firstName = json.optStringOrNull("first_name"),
            lastName = json.optStringOrNull("last_name"),
            username = json.optStringOrNull("username"),
            photoUrl = json.optStringOrNull("photo_url")
        )
        main.post { onAuth(payload) }
    }
}

private fun telegramLoginHtml(botUsername: String): String = """
<!doctype html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <style>
    html, body {
      margin: 0;
      min-height: 100%;
      background: transparent;
      color: #2b2118;
      font-family: sans-serif;
    }
    body {
      display: flex;
      align-items: center;
      justify-content: center;
      text-align: center;
      padding: 18px;
      box-sizing: border-box;
    }
    .wrap { width: 100%; }
    .hint { margin-top: 18px; font-size: 13px; opacity: .72; line-height: 1.35; }
  </style>
</head>
<body>
  <div class="wrap">
    <script async src="https://telegram.org/js/telegram-widget.js?22"
      data-telegram-login="$botUsername"
      data-size="large"
      data-radius="6"
      data-request-access="write"
      data-onauth="SecureVpnTelegram.onTelegramAuth(JSON.stringify(user));">
    </script>
    <div class="hint">Если кнопка не появилась, проверьте интернет и домен, заданный в BotFather.</div>
  </div>
</body>
</html>
""".trimIndent()
