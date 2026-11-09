# Встроенные обновления Android

## Задача

Пользователь согласовал собственный HTTPS-сервер обновлений: уведомление, кнопка скачивания, системное подтверждение установки, сохранение входа/настроек, публикация одной командой. До начала прочитаны PROJECT_MEMORY.md, имена заметок, заметки о предыдущем APK и восстановлении узлов, актуальный git status. Чужие изменения backend/admin/desktop сохранены. Коммит/push не выполнялись.

## Реализация

- android-client/app/src/main/java/app/chspch/client/update/UpdateManifest.kt: schema1, явный packageName, versionCode/versionName, release notes, артефакты по ABI. Только HTTPS того же origin в /updates/android/, без credentials/query/fragment/path traversal; контрольная сумма64 hex, размер1..250MiB.
- AppUpdater.kt: публичный latest.json без Bearer, ответ до64KiB, таймауты/без редиректов. DownloadManager сохраняет загрузку независимо от Activity. До установки проверяются размер/SHA256/package/version и сертификат установленного приложения. Проверенный APK копируется во внутренний cache/updates; FileProvider выдаёт только этот каталог. Ошибочная загрузка удаляется для повтора. После установки новой версии очищаются только собственные файлы обновления и завершённый download.
- AppUpdateHost.kt, MainActivity.kt, Screens.kt: проверка при открытии (не чаще30мин внутри одной Activity), диалог, прогресс, разрешение «установка из ЧСПЧ», явная кнопка установки через системный installer. «Устав → Обновление приложения» показывает текущую версию и ручную проверку. Вход и VPN-конфигурации не включаются в APK и не очищаются при обновлении.
- UpdateJobService.kt: периодический persisted JobScheduler job48101 раз в6ч при наличии сети; уведомление о новой версии один раз на versionCode. Отдельный persisted job48103 отслеживает завершение скачивания и проверяет файл/показывает ready-уведомление (либо ошибку с возможностью повтора). Android сам выбирает время фонового запуска; это не push. После force-stop фон заблокирован Android до открытия приложения.
- Manifest: REQUEST_INSTALL_PACKAGES, RECEIVE_BOOT_COMPLETED, закрытый FileProvider, JobService с BIND_JOB_SERVICE. Приёмник DownloadManager остался exported=false. Попытка сделать его exported=true была отклонена auto-review как излишняя внешняя точка входа; обход не выполнялся. Альтернатива — системное фоновое задание без экспорта приёмника.

## Раздача и публикация

На moscow добавлен /etc/nginx/snippets/chspch-android-updates.conf (копия ops/android-updates/nginx.conf), include в существующий HTTPS server tech-supp-test.ru. Исходный nginx-конфиг сохранён в /opt/securevpn-staging/nginx-before-android-updates-*.conf; nginx -t/reload успешны. Backend/VPN-контейнеры для обновлений не менялись и не перезапускались.

Публичный каталог /var/www/chspch-updates/android: только GET/HEAD, без листинга, Cache-Control:no-store, правильный MIME APK. Метаданные https://tech-supp-test.ru/updates/android/latest.json. Постоянная ссылка первой установки ARM64: https://tech-supp-test.ru/updates/android/latest-arm64-v8a.apk. Стабильная ссылка и latest.json меняются атомарно; приложения скачивают immutable releases/имя-с-хешем.apk. Старые артефакты сохраняются, чтобы уже начатые загрузки не ломались.

ops/android-updates/publish.py: команда `python3 ops/android-updates/publish.py --version 1.3 --notes "Описание изменений"` увеличивает versionCode, собирает debug APK/запускает JVM-тесты, проверяет пакет/ABI/подпись, передаёт gzip по SSH alias moscow, сверяет хеш на сервере и атомарно публикует. По умолчанию ARM64, дополнительные ABI через повтор --abi. Локальные релизы — dist/android-updates/<code>/. Есть --dry-run и --skip-build для повтора передачи; одинаковый versionCode с другим содержимым и downgrade запрещены. Ключи/пароли не включены. Сохранён прежний debug-сертификат для совместимости установок; его публичный SHA256 закреплён в скрипте. Не пересоздавать keystore без плана миграции подписи. Инструкция: ops/android-updates/README.md.

## Артефакты и проверки

Финальный APK — dist/ChSPCh-1.2.2-arm64.apk, versionCode5, Android8+,ARM64, около77MiB, прежний applicationId app.chspch.client. SHA256 из dist/ChSPCh-1.2.2-arm64.apk.sha256. Публикация скриптом выполнена и проверена через публичный HTTPS: версия1.2.2/code5, stable APK HEAD200/application/vnd.android.package-archive/80584311bytes, локальный хеш совпадает с метаданными. APK не содержит .env/keystore/ключевых файлов/shared_prefs.

assembleDebug/testDebugUnitTest успешны,21 JVM-тест без failures/errors (новые UpdateManifestTest — ABI/пакет/недопустимые URL/хеш/размер). assembleDebugAndroidTest успешен. На телефоне до обновления подтверждено наличие Telegram-входа; для сравнения после установки во временном /tmp/chspch-update-checkpoint.json сохранён только хеш ID/булево наличие входа, не токены/профиль.

Для живой проверки установлена локальная базовая версия code3 с обновлятором; она не публикуется поверх immutable code3. Первая опубликованная промежуточная code4 показала проблему фонового ready-уведомления при закрытом receiver; исправление через job48103 входит в финальную code5. Первое opt-in device-прохождение подтвердило загрузку APK без VPN и проверки SHA/пакета/версии/подписи, но провалилось на ready-уведомлении. Отдельно принудительный запуск job48101 показал реальное уведомление о новой версии.

Во время финального теста телефон отключался от ADB; пользователь снова подключил. Повтор выявил активный VPN старого com.securevpn.app; это не ошибка обновлятора. Прежнее приложение временно force-stop для проверки именно без VPN; после проверки нужно подключить новый клиент. Opt-in тест UpdateDeviceSmokeTest в работе: ничего не устанавливает, не меняет вход, проверяет публичный HTTPS без TRANSPORT_VPN, DownloadManager, проверенный APK и фоновое уведомление. Системная установка/сохранение входа и окончательное включение VPN пока ещё проверяются; добавить итог ниже.

## Итог живой проверки

После остановки старого VPN opt-in UpdateDeviceSmokeTest прошёл: OK(1 test),44.762s. Подтверждены отсутствие TRANSPORT_VPN, публичная загрузка code5, проверки размера/SHA256/пакета/версии/сертификата и реальное ready-уведомление из фонового JobScheduler. Закрытый receiver не экспортировался.

На телефоне открыт тот же show_app_update intent, который использует уведомление. Диалог показал ЧСПЧ1.2.2 и «Обновление загружено и проверено». Нажатие «Установить» открыло штатное «Обновить приложение?»; после подтверждения прошла OEM-проверка. Без adb install финальной версии установлен app.chspch.client versionCode5/versionName1.2.2. Сравнение хеша Telegram ID до/после и булева наличия входа подтвердило тот же вход. Временный checkpoint с хешем удалён; токены/личный профиль не выводились.

После запуска установленной версии pending/download_id очищены автоматически. Периодическое update-задание зарегистрировано. Восстановлен именно новый app.chspch.client/.vpn.SingBoxVpnService, runtime ON; старый com.securevpn.app force-stop, без удаления данных/пакета.

Ограничение, существовавшее до задачи: замена APK останавливает VPN-сервис, но старый runtime snapshot может остаться ON. В этой проверке потребовалось выключить/включить VPN в новом приложении; не считать старую надпись «НА СВЯЗИ» подтверждением существования службы. Механизм обновления сохраняет вход/настройки, но не обещает непрерывный туннель/автоматическое переподключение после установки. Самостоятельное исправление общей синхронизации VPN lifecycle в эту задачу не включалось.

Финальный SHA256 dist/ChSPCh-1.2.2-arm64.apk:1f74076e3f2129757afe8e4a3914dee62eddc9d93325ff9021d0ae853c7df50e. Публичные metadata и stable APK link проверены, hash/размер совпали.21 JVM-тест,1 live instrumented test, nginx -t и git diff --check прошли. Промежуточная неудача фонового уведомления и последующее успешное повторение сохранены в этой заметке; не объявлять первое прохождение успешным. У друга эта версия не проверялась. Отправка другу, Git commit/push не выполнялись.
