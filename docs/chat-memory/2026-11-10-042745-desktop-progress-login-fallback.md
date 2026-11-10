# Desktop1.0.7: неподвижный прогресс и резервный Telegram-вход

Поручение: полоса загрузки сдвигает весь UI; Telegram открывается нестабильно, «Через браузер» не открывает окно; всегда дать ссылку для ручного перехода.

Причины по коду: Main.kt добавлял/удалял LinearProgressIndicator в Column, меняя высоту при каждом busy (в том числе polling входа раз2сек). DesktopController открывал tg/https только через AWT browse и считал отсутствие исключения доказательством открытого окна. Причина отказа на конкретном Windows-ПК не воспроизведена.

Изменения:
- Main.kt: общая полоса, полоса скорости и загрузки обновления имеют постоянные4dp slots; отмена замера48dp slot, footer status фиксирован на2строки. Появление полосы не меняет размеры соседних элементов.
- ExternalLinks.kt: Windows PowerShell Start-Process с одним quoted URI и ErrorAction Stop; Linux xdg-open, затем gio open; резерв AWT BROWSE. Только https/tg, payload сохранён, конечный timeout, дочерние stdout/stderr не выводятся. Вызов с Dispatchers.IO, loginOpening защищает от повторных кликов; сообщение говорит об отправленном запросе, а не подтверждает открытие окна.
- Account в Main.kt всегда показывает выделяемую HTTPS-ссылку текущего challenge и кнопку копирования, независимо от результата автооткрытия. Нет логирования/сохранения реальных ссылок входа. После READY/истечения ссылка очищается прежним polling.
- Версия повышена до1.0.7 в DesktopApi/build.gradle/упаковщиках. README дополнен.

Проверки: Gradle test/createDistributable/prepareWindowsJars PASS28/0. Пять новых ExternalLinksTest: Windows сохраняет полный payload, отсутствие Linux handler ведёт к gio, резерв AWT, полный отказ возвращает false, опасная схема отвергается. Эти тесты используют fake dispatch, реальное окно браузера не доказывают. Оба архива собраны, publisher CRC/размеры/отсутствие private state PASS. Дополнительно в Windows app JAR подтверждены новые ExternalLinks/Main/Controller classes (фактическое имя ChspchDesktop.jar; первоначальный filename-фильтр desktop-client был ошибочным и исправлен без изменений артефакта). git diff --check PASS.

Публикация в ранее согласованный канал обновлений: ops/desktop-updates/publish.py --dry-run, затем --skip-build,1.0.7 Windows/Linux. Сервер сверил SHA256 до переключения feed. Публичные latest.json совпали; stable HEAD Windows/Linux200/размеры совпали.
Windows127764660bytes SHA256ef1c857cee958344162e2262752378c97a9bb6bf9b73273340d6f4ad05de6f70.
Linux110349103bytes SHA256d53ac25b4625e3d9b61dbbcf42802954c52ec1ce80f5a919f9a25d1912c256e7.

Ограничения: фактический Windows переход tg/браузер/clipboard и визуальная неподвижность на машине пользователя не проверены; текущая машина/Windows VM новым клиентом не обновлялись. VPN заново не проверялся. Android/backend/узлы в этой задаче не менялись. Старые чужие правки сохранены, коммит/push нет. Следующий шаг: обновить ПК до1.0.7 и проверить вход; если системное открытие не сработало, пользователь берёт видимую ссылку из карточки.
