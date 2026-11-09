# Android 1.3: UI, обязательные сервисы, плитка и публикация

## Поручение и распределение
Пользователь поручил распределить исправления агентам и выкатить обновление. Задачи и зависимости: `docs/tasks/android-1.3-patch.md`. Исполнители ui_patch, vpn_tile, routing_resources; координатор объединил, проверил, установил и опубликовал. Пользователь отдельно подтвердил: базовые сервисы обязательны, отключать их нельзя. Чужие исходные изменения checkout сохранены, commit/push не выполнялись.

## Изменения
- `android-client/app/src/main/java/app/chspch/client/MainActivity.kt`, `Components.kt`, `Screens.kt`: прозрачная status bar с фоном приложения, согласованный бордовый navigation/нижний фон; удалён общий screen-зависимый off-overlay, вызывавший вспышку при свайпе. Telegram nickname с marquee. Нижняя НАЗАД и системный back из узлов ведут домой. Удалён блок «Общий ход». Добавление приложения — заметная кнопка; диалог поиска со списком и иконками. DEFAULT без переключателей, пользовательские правила управляются.
- `vpn/VpnTileService.kt`, `vpn/SingBoxVpnService.kt`, Manifest: Quick Settings плитка, запуск через штатную Activity/permission, прямая остановка, отображение состояния, Android14 PendingIntent; persisted ON без живой службы не считается подключением. Tile обновляется после записи prefs.
- `backend/.../db/repo/PolicyRepository.kt`: обязательные defaults, старые overrides игнорируются. V15 удаляет старые overrides, включает/блокирует базовые VPN сервисы, добавляет389 suffix-доменов. `backend/src/main/resources/routing/` содержит исходный community snapshot, GPL3, source URL/commit/SHA и атрибуцию. Источник runetfreedom/russia-blocked-geosite. `.ru/.su/.рф`, явные российские сервисы и общий DNS исключены; DIRECT по умолчанию и guest Telegram-only сохранены. Импортер `ops/routing-lists/import.py` воспроизводим; будущие обновления новой миграцией, V15 не переписывать.

## Проверки
- Android assembleDebug/testDebugUnitTest/assembleDebugAndroidTest PASS,21 JVM tests/0 failures/errors. Backend test jar PASS24 tests/0 failures/errors. Importer3 tests и offline reproduce/cmp PASS. git diff --check PASS.
- На телефоне установлен code6/version1.3 поверх code5 без очистки; сохранён Telegram-вход (проверены только boolean, профиль/токены в заметки не записаны). Визуально проверены панели, кнопка приложения и диалог с реальными иконками. Узлы содержат НАЗАД; системный back вернул главный экран. «Общий ход» отсутствует.
- Плитка добавлена в шторку телефона; реальным tap включён VPN, служба foreground и плитка «Подключён»; повторным tap служба остановлена/плитка «Выключен». Затем VPN снова включён, финальный runtime ON. Телефон получил новую политику:389 community domains,0 disabled DEFAULT,DIRECT default. Это подтверждает получение политики и запуск ядра, но не сетевую доступность каждого домена.

## Развёртывание
Backend baseline securevpn-backend:admin-telegram-20261109 проверен по SHA; patch jar меняет только `com/example/db/repo/PolicyRepository*`, V15 и `routing/*`. Все остальные zip entries побайтово идентичны baseline, включая admin/failover. Новый image `securevpn-backend:android13-routing-20261109`. Перед rollout сохранены compose и pg_dump в `/opt/securevpn-staging/android13-routing-20261109`; предыдущий image оставлен. Пересоздан только backend. Flyway15 success=true; DB community enabled=true/editable=false/count389;0 отключаемых или выключенных VPN-defaults. HTTPS health status/db ok.

Android выпущен штатным `ops/android-updates/publish.py`:1.3/code6 ARM64, прежние package и сертификат. Public latest.json и immutable APK HEAD/size подтверждены. Постоянная ссылка https://tech-supp-test.ru/updates/android/latest-arm64-v8a.apk. Metadata https://tech-supp-test.ru/updates/android/latest.json.
APK SHA256: `cd74dec2f0bb11dd5ed45d234f51fd47572d414aa955e728db7d1db556c60130`; bytes: 80600867. Локальный artifact: `dist/android-updates/6/ChSPCh-1.3-6-arm64-v8a-cd74dec2f0bb.apk`.

## Ограничения
Полный список не является доказательством блокировки каждого адреса; сетевой smoke каждого из389 ресурсов не выполнялся. Свежий Telegram login и гостевой VPN runtime в этой задаче не повторялись. Устранение причины вспышки проверено по коду, покадровый экранный замер не выполнялся. Окно другого приложения частично перекрывало верх телефона при визуальном просмотре. Другие телефоны/OEM и первый запрос VPN разрешения через плитку не проверялись. APK установлен через adb; системный update-installer повторно не тестировался. На других устройствах плитка добавляется через редактирование шторки. Следующий шаг — пользовательская оценка свайпов/оформления и обычное обновление остальных установок.

## Финальная проверка раздачи
Полный APK прочитан через публичный HTTPS URL с сервера moscow:80600867 bytes, SHA256 cd74dec2f0bb11dd5ed45d234f51fd47572d414aa955e728db7d1db556c60130, совпадает с manifest. Проверка public latest/HEAD выполнялась и с рабочей машины. Дополнительная полная загрузка с рабочей машины оказалась медленной и остановлена после успешной серверной HTTPS-сверки; не считать её завершённой. Устройство в конце:1.3/code6, Telegram account present=true, runtime ON; временные файлы на /sdcard удалены, шторка закрыта.
