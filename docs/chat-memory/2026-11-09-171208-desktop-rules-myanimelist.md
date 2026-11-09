# Desktop 1.0.1: состав правил и myanimelist.net

Пользователь сообщил, что добавленный myanimelist.net не виден на ПК и сайт недоступен; уточнил: Android показывает нормально, исправлять отображение только на ПК.

Подтверждено live: существующее enabled VPN правило myanimelist.net уже сохранено в аккаунте. Через реальный Linux TUN к Франции сайт вернул HTTPS200 и HTML, www —301; safe VPN outbound counter вырос в обоих запросах. WebsiteSmoke в finally остановил TUN и отозвал выданный конфиг. Серверная политика при этой проверке не менялась, так как правило уже было. С fra HEAD сайта200. Прямой локальный curl остановился на DNS timeout, поэтому SNI-блокировка здесь не подтверждена.

Исправления desktop-client:
- DesktopController.kt: полученная при connect серверная policy теперь обновляет UI (раньше конфигурация получала свежие правила, а список оставался прежним); refreshPolicy для открытия Маршрутов/ручного обновления. Добавление получает свежую версию policy, не дублирует точное правило; callback очищает ввод только после успешного сохранения.
- Main.kt: обновление при открытии Маршрутов, кнопки Обновить/Применить к туннелю, Состав для каждой группы, все значения с поиском и копированием. Изменения с другого устройства применяются к активному туннелю после явного переподключения.
- WebsiteSmoke.kt + Gradle websiteSmoke: opt-in диагностика указанного пользователем публичного сайта с lock профиля и finally cleanup. При отсутствии может сохранить именно запрошенное правило. Реквизиты/адрес выхода не печатает.
- API/native package version1.0.1, упаковщики и README обновлены. Android/backend не менялись.

Проверки: Gradle test/createDistributable/prepareWindowsJars PASS,13 JVM tests, реальные core JSON checks включены. websiteSmoke PASS. git diff --check PASS. Windows ZIP CRC PASS, session.json/.env entries0. Linux установлен штатным установщиком с сохранением аккаунта и открыт. Реального Windows запуска/UAC/TUN не было. UI поиска/раскрытия не проверялся физическим нажатием; компиляция успешна.

Артефакты:
- desktop-client/build/distributions/chspch-linux-x64-1.0.1.tar.gz SHA256 2d5b5df88dbbe99e2b5d7df36752df082f64842fb3ddab2c362aa7729f6cc4c0
- desktop-client/build/distributions/chspch-windows-x64-1.0.1.zip SHA256 9c7675bb96dec6cd4638ce7b56a89b05322d2cbc081e25f95d82aeb2f2da2856

Следующий шаг: пользователь проверяет Состав/myanimelist.net в обновлённом ПК-клиенте; Windows установить новый архив. Недоступность сайта в иной сети/на Windows этим Linux тестом не исключается. Commit/push не выполнялись.
