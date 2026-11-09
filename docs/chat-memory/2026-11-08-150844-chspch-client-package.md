# Пакет app.chspch.client

Поручение: пользователь выбрал предложенный app.chspch.client для «ЧСПЧ».

Изменены applicationId/namespace в android-client/app/build.gradle.kts. Kotlin-исходники main/test/androidTest, импорты и action-строки сервиса перенесены с com.securevpn.app на app.chspch.client; каталоги теперь app/src/*/java/app/chspch/client/. В backend/src/main/kotlin/com/example/modules/auth/AuthRoutes.kt обновлён package в intent-ссылке Telegram. Схема securevpn сохранена. PROJECT_MEMORY.md обновлён с текущими путями и решением.

Проверки: assembleDebug testDebugUnitTest — BUILD SUCCESSFUL; aapt подтвердил package app.chspch.client, label ЧСПЧ, launchable activity app.chspch.client.MainActivity; git diff --check успешен. Backend-тесты не запускались (заменён только literal пакета). На устройство APK не устанавливался, VPN и Telegram-вход новой установки не проверялись. Backend не развёрнут, поэтому production intent-ссылка пока указывает на старый пакет. Старая установка и данные автоматически не мигрируют. Предыдущий APK в dist не заменён.

Следующий шаг: установка новой сборки и проверка входа/подключения; для серверной кнопки возврата потребуется обновление backend. Commit/push не выполнялись.
