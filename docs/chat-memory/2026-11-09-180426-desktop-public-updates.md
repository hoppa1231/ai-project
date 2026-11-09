# Desktop1.0.4: уведомления и публичная загрузка обновлений

Запрос пользователя: уведомление о новой версии и возможность больше не отправлять архив вручную.

Результат:
- Публичная страница https://tech-supp-test.ru/updates/desktop/
- Постоянные ссылки latest-windows-x64.zip и latest-linux-x64.tar.gz в этом каталоге, feed latest.json.
- Версия1.0.4 опубликована на moscow: /var/www/chspch-updates/desktop. Сборки с immutable именами/хешами, stable symlinks, атомарный feed и история предыдущих. Нет авторизации/VPN-реквизитов. Android feed отдельно и продолжает200.
- DesktopUpdates.kt: проверка при старте и раз6ч пока GUI/трей запущен, numeric semver без downgrade, schema/product/platform/точный HTTPS origin/release path/размер/SHA256 validation. Скачать в приватный updates каталог профиля, ограничение объёма/места/таймауты, удаление неполной загрузки.
- DesktopController.kt/Main.kt: notification через Compose Tray при поддержке ОС (однократно для версии), постоянная карточка/прогресс/ошибки, ручная проверка в настройках, открытие папки проверенного архива. При отсутствии tray остаётся карточка. Текущая версия не предлагается себе.
- Установка явная: полная распаковка, закрыть ЧСПЧ, Install.exe Windows / install-linux.sh Linux. Тихой установки нет. Версии до1.0.4 требуют однократной установки клиента с обновлениями. Закрытый процесс не проверяет новые версии.
- launcher/install.go и main_windows.go: staged Windows replace вместо copy поверх (чтобы старые JAR не оставались), один rollback, защита чужого каталога. Физическая Windows-установка/уведомления ещё не проверены.
- ops/desktop-updates/{publish.py,configure.py,nginx.conf,README.md,test_publish.py}: воспроизводимая сборка/публикация и nginx location рядом с Android. Перед конфигом backup, nginx -t PASS, reload. Backend/Android не пересобирались и не перезапускались.
- Первичная SSH передача оборвалась; реализованы rate limiting/смещение/retry/--resume-stage, публикация после проверки обоих архивов. UUID staging сохранён в dist/.../staging.json. Перенос успешно завершён.

Проверки:
- Gradle test/createDistributable/prepareWindowsJars PASS,22 JVM tests/0 failures: версии/недоверенные ссылки/обрезанный/увеличенный/повреждённый payload.
- Go launcher2 tests PASS: fresh/update2 раза удаляет stale JAR, rollback сохранён, чужой каталог не трогается. Windows launcher кросс-собран.
- publisher test PASS: обычная публикация, downgrade/смена содержимого того же version/неверный SHA отклонены и feed сохранён.
- Python py_compile и git diff --check PASS.
- Server SHA256 обоих архивов сверены до publish. Public latest.json равен manifest; HEAD immutable/stable архивов200 с точными размерами; страница200/no-store; Android feed200.
- Live DesktopUpdatesPublicTest PASS: опубликованный Linux-архив полностью скачан тем же DesktopUpdates.download, размер и SHA256 подтверждены; DesktopUpdates.check() не предлагает текущую версию. BUILD SUCCESSFUL, полный сетевой тест6m15s. Временный скачанный архив удалён finally.
- Linux1.0.4 установлен штатно с сохранением профиля и открыт. Physical Windows notification/install QA ещё впереди; статические/JVM проверки не являются runtime VPN тестом. Маршрутизация VPN в этой задаче не менялась.

Артефакты:
- desktop-client/build/distributions/chspch-linux-x64-1.0.4.tar.gz SHA256 9945e6254d9b8d478f276d8a3e7e02c46df42a0633c79d3b6156df0b64f07349
- desktop-client/build/distributions/chspch-windows-x64-1.0.4.zip SHA256 16cbfae1bad094388dd8eb26b9ffd92a8b9cbc186a64f17a0547db22c649648c
- dist/desktop-updates/1.0.4/latest.json содержит опубликованный manifest.

Следующий шаг: установить Windows1.0.4 по постоянной ссылке; будущие версии публиковать по ops/desktop-updates/README.md. Пользователь получает уведомление/скачивание, распаковка и запуск установщика остаются ручными. Коммит/push не выполнялись; чужие изменения сохранены.
