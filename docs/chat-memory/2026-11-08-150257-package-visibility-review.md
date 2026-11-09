# Обсуждение определения клиента по метаданным

Задача: обдумать нейтральное имя пакета и метаданные для снижения узнаваемости VPN-клиента другими приложениями. Изменять приложение пользователь пока не поручал.

Проверены PROJECT_MEMORY.md, заметка об APK для друга, git status и актуальные исходники. В android-client/app/build.gradle.kts applicationId/namespace = com.securevpn.app. В app/src/main/AndroidManifest.xml label = «впн», схема ссылки securevpn, сервис .vpn.SingBoxVpnService с BIND_VPN_SERVICE и android.net.VpnService. В strings.xml app_name = «ВПН трудящихся», но manifest задаёт label непосредственно. В SingBoxVpnService.kt есть соответствующие имя сессии и канал уведомлений. Release уже включает minify/shrinkResources, но использует debug signing.

Предложение, не согласованное решение: стабильный нейтральный applicationId, нейтральный label, согласованное переименование публичных компонентов и deep link с проверкой Telegram-входа. Смена applicationId создаёт отдельное приложение; миграцию данных и старую установку нужно учитывать. Обязательные декларации VpnService остаются отличительным признаком, если проверяющее приложение получило видимость пакета. Поэтому переименование помогает против поиска по именам, но не гарантирует скрытие типа приложения даже при выключенном туннеле.

Изменений кода, сборки и проверки на устройстве не было. В checkout уже много сторонних незакоммиченных изменений; они сохранены. Следующий шаг: выбрать нейтральное имя и согласовать переименование, затем проверить итоговый APK и сценарий входа. Документация: https://developer.android.com/reference/android/net/VpnService и https://developer.android.com/training/package-visibility/declaring .
