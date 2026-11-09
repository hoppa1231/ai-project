# Desktop1.0.3: несколько EXE, качество рубильника, авторизация TUN

Запрос: Windows-программа бывает из нескольких EXE; картинка рубильника плохого качества; уменьшить повторные запросы пароля и автоматически вызывать UAC из обычного Windows GUI.

Изменения:
- InstalledApps.kt: InstalledApp.paths, связанные EXE в папке программы/до2 уровней подпапок, максимум300 файлов; исключены типовые updater/installer/uninstaller/crash имена и общие системные папки. Группировка эвристическая, не гарантирует все дочерние процессы вне каталога.
- AppPicker.kt: отметка всей группы, раскрытие Файлы, отдельный выбор EXE, частичное состояние TriStateCheckbox; все выбранные пути сохраняются локально. Main.kt группирует выбранные файлы по имени программы.
- PowerSwitch.kt + Main.kt: низкокачественный PNG заменён рисуемым Compose Canvas рубильником, латунная палитра/винты/подписи и анимация; независимость от DPI, текстовые метки. Отрендерены оба состояния и визуально осмотрены: desktop-client/build/screenshots/power-switch-vector.png. Android не менялся.
- helper/main.go: один авторизованный помощник на время GUI, валидирует каждую новую конфигурацию, удерживает проверенные SHA-pinned байты core в admin-owned temp. Команды stop останавливают core/удаляют config, но оставляют авторизацию; quit/EOF/12s heartbeat lease завершают помощник с cleanup. Общего listener или постоянной службы нет; пароль не сохраняется, UAC не отключается.
- ElevatedTunnel.kt/TunnelRuntime.kt: reuse соединения, отдельные stop и shutdown, синхронизация сообщений, новый READY/STOPPED цикл. Windows Start-Process -Verb RunAs -ErrorAction Stop вызывает UAC автоматически; Linux pkexec. Отмена/таймаут прав не повторяет автоматические попытки. Shutdown GUI/standalone smoke освобождает помощник, после потери связи/следующего запуска GUI потребуется новая авторизация.
- platform_windows.go: освобождение Job Object между core сессиями.
- API/native version1.0.3, README/WINDOWS-CHECK/упаковщики обновлены.

Проверки:
- Gradle test/createDistributable/prepareWindowsJars PASS:18 JVM tests/0 failures, core checks включены, render PNG получен. Новый тест EXE группы включает helper, исключает uninstaller/соседнюю программу.
- Go test -race ./... PASS: повторные start/stop через одну сессию, отказ опасному config, EOF active core cleanup и прежние allowlist/hash tests.
- Реальный Linux websiteSmoke с linked аккаунтом: существующее myanimelist.net правило сохранено; HTTP200/HTML, www301, VPN outbound events. Затем stop/start тем же runtime: myanimelist.net200; elevationRequests=1. Shutdown и revoke выполнены.
- Packaged abrupt JVM lifecycle smoke: Telegram200/ordinary DIRECT200, exit23; TUN interface/rules удалены, сеть после cleanup200, guest config revoke confirmed. Изолированный guest профиль, пользовательские маршруты не менялись.
- Linux штатно обновлён, профиль сохранён, новая GUI запущена. Windows helper кросс-собран, ZIP CRC PASS, session.json/.env entries0. Реального Windows UAC/процессных соединений нет — требуется проверка на Windows-ПК. git diff --check PASS.

Артефакты:
- desktop-client/build/distributions/chspch-linux-x64-1.0.3.tar.gz SHA256 88dde8172cb96ae661fa8c3dcec655aec4ba1e17647a3f985204a0985814bcfa
- desktop-client/build/distributions/chspch-windows-x64-1.0.3.zip SHA256 c37a89b9f5d58de4296a6441dd7e755adf3f10c53ebea409b9d207ab7ee3cd38

Следующий шаг: пользователь проверяет новый рубильник/Файлы группы и Windows UAC (обычное открытие Chspch.exe; два соединения без второго prompt). После закрытия GUI запрос снова ожидаем. Сложные EXE вне каталога/песочницы и физический sleep/Wi-Fi требуют отдельной проверки. Commit/push нет, чужие Android/backend изменения сохранены.
