# Отозванный кеш VPN и иконка плитки: Android1.3.1

## Задача
Пользователь сообщил о красной третьей лампе/подозрении на отказ VPN, затем поручил заменить иконку плитки приложенным PNG и сократить название до «ЧСПЧ». Перед диагностикой перечитаны PROJECT_MEMORY, заметка Android1.3, git status и актуальный код. Чужие изменения сохранены.

## Установленная причина
Третья лампа действительно проверяет HTTPS HEAD telegram.org,4s connect/read; адрес и TLS-проверка не менялись. Служба Android была foreground/runtime ON, Telegram-правила включены, сохранён вход и обычный fra447. Но этот кешированный VLESS-клиент в backend имел REVOKED/reason=replace_config; /v1/clients реальной панели не содержал его UUID. Конкретный инициатор прежней замены в этой задаче не установлен.

Первый instrumentation запуск перезапустил процесс приложения и остановил VPN: его Telegram timeout результаты относятся к прямой сети. Это не VPN-проверка. Следующие диагностические прогоны явно запускали кешированный туннель: ya/Google/example отвечали, Telegram давал TLS/DNS errors. Приватный временный log ядра показал DNS remote Telegram read response EOF. На fra direct HTTPS Telegram200 и TCP DNS1.1.1.1:53 работали. После /vpn/issue без forceRotate актуальный клиент имеет ACTIVE, присутствует/enable=true в панели и передаёт трафик; Telegram200/API302. Это связывает отказ с отозванным кешем, а не с раскраской лампы или доказанной поломкой DNS на ноде.

## Изменения
- `android-client/app/src/main/java/app/chspch/client/MainActivity.kt`, resolveVpnLaunchProfile: убраны ранние возвраты локального JSON/URI без серверной проверки. Для управляемого приложением конфига каждый обычный connect вызывает /vpn/issue; backend проверяет актуальность и переиспользует только active. forceRotate не включается без прежних условий. Выбор уже существующих Telegram-конфигов сохранён отдельно. При недоступном backend запуск не маскируется бесконечным использованием potentially revoked cache; это tradeoff текущего исправления.
- `vpn/VpnTileService.kt`, Manifest: label «ЧСПЧ», иконка `drawable-nodpi/ic_qs_chspch.png` — оригинальный пользовательский PNG с сохранённой alpha. Android сам окрашивает маску. Визуально подтверждены новая иконка-паутина, подпись «ЧСПЧ», статус «Подключён».
- `app/src/androidTest/java/app/chspch/client/ConnectivityDiagnosticTest.kt`: opt-in live test, revalidate issue→start cached policy→wait new ON→assert real Telegram/API/example HTTP2xx/3xx. Никаких UUID, токенов/профиля в вывод. Временный debug log из ранних проб в финальном тесте отсутствует.

## Проверки и телефон
Android assembleDebug/testDebugUnitTest PASS21 JVM tests; assembleDebugAndroidTest PASS. Финальный instrumented smoke PASS1 test/4.206s на установленной1.3.1: Telegram200/1219ms, API302/863ms, example200/273ms. В отличие от ранних диагностических прогонов этот тест проверяет ответы assertions, а не только печатает ошибки.
После завершения instrumentation его процесс/служба остановились: VPN затем запущен обычной кнопкой production MainActivity. UI показывает «НА СВЯЗИ» и «Телеграм: работает»; плитка «ЧСПЧ/Подключён» с новой иконкой подтверждена снимком. Был единичный ya.ru DNS timeout (Google/Telegram уже успешны), не выдавать все три индикатора за постоянно зелёные. Отдельная причина нестабильности прямой ya.ru здесь не установлена.
1.3.1/code7 установлен поверхcode6, данные входа не очищались. Временный cache/connectivity-core.log и /sdcard/chspch-diag.* удалены. Raw logs/профиль не перенесены в repo. git diff --check PASS. Backend/nodes не изменялись и не перезапускались, commit/push не выполнялись.

## Релиз
Штатный publisher:1.3.1/code7 ARM64, прежняя подпись. Локальный artifact `dist/android-updates/7/ChSPCh-1.3.1-7-arm64-v8a-aa40b154e458.apk`.
SHA256 aa40b154e45877b3430765e1f75ac636ac163bc8109fc6e6b1d539103eb8b7b2; 80633127bytes.

## Ограничения
Проверены HTTPS Telegram/API и прямой example, не Telegram-звонки/сообщения/все389 доменов. UI ON по-прежнему означает запуск ядра, а не постоянный end-to-end health; исправлена конкретная причина повторного старта с отозванным managed credential. Отзыв во время уже активного соединения не обнаруживается мгновенно: при новом подключении конфиг проверяется сервером. Первый VPN permission/OEM и выбор сторонних Telegram URI в этом патче не проверялись. Следующий шаг — пользовательская проверка обычной работы после обновления.

Публикация завершена: public latest.json1.3.1/code7 и immutable APK HEAD/size проверены штатным publisher. Постоянная ссылка https://tech-supp-test.ru/updates/android/latest-arm64-v8a.apk; прежний сертификат подтверждён apksigner.
