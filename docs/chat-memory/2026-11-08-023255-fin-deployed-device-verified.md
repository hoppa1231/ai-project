# fin развёрнут и проверен на телефоне

Поручение: сделать fin дефолтным, обеспечить доступ из приложения, проверить телефон. Пользователь явно одобрил обновление backend на moscow и продолжение SSH/ADB после таймаутов автоматической проверки. Предыдущая промежуточная заметка 2026-11-08-022853 описывает прошлое незавершённое состояние; блокировка снята и работа завершена.

Результат:
- Backend /opt/vpn-control-plane на moscow работает из securevpn-backend:selective-v14. test/installDist SUCCESS; Flyway 14 success=true, /health db=ok. Резервные копии БД/env/исходников и прежний image сохранены в защищённых серверных путях, перечисленных в предыдущей заметке.
- backend/src/main/kotlin/com/example/db/repo/UserRepository.kt использует boxed Long для nullable telegram_id. Добавлены regression-тесты UserRepositoryTest.kt: SQL NULL больше не становится 0, гостевой сценарий корректно определяется.
- V14__shared_node_agent.sql разрешает отдельные inbound одного agent endpoint. fin-guest:446 и fin-exit:447 зарегистрированы, оба HEALTHY. DEFAULT_VPN_NODE_ID указывает на гостевой fin. Старые production inbound сохранены.
- На fin node-agent с автозапуском и ограничением 9090 firewall только loopback/backend. Гостевой Xray inbound: Telegram CIDR allowlist, TCP DNS, затем blocked; IPOnDemand. REALITY target koel.dev проверен сетевым запросом. Обычный inbound отдельный.
- Android assembleDebug/testDebugUnitTest SUCCESS, 11 JVM tests без ошибок. APK установлен adb -r -t с сохранением данных.
- android-client/app/src/androidTest/java/com/securevpn/app/FinNodeDeviceSmokeTest.kt: opt-in запуск finNodeSmoke=true, OK (1 test), 10 секунд. Проверены guest DIRECT/Telegram-only policy, игнорирование постороннего exitNodeId/CASCADE (SINGLE fin:446), HTTPS api.telegram.org через VPN, ipify с прямым egress телефона, отказ example.com при принудительном направлении через guest. finally восстанавливает серверную политику.
- После перезапуска приложения UI проверен через uiautomator: НА СВЯЗИ, FINLAND · TELEGRAM, квота отображается. Скриншот /tmp/securevpn-fin-final.png. Показание UI дополняет сетевой тест, не заменяет его.
- Share проверен на телефоне: example.com/article -> подтверждение example.com и Telegram-вход для гостя; example.ru -> отказ без добавления.
- Отдельный серверный smoke-config отозван (REVOKED), защищённый файл его сессии удалён. Локальный временный Maven proxy остановлен; stay_on_while_plugged_in восстановлен в 0. Службы fin и бэкапы сохранены.

Ограничения: реальные Telegram-вход, сообщения/звонки, авторизованный Share и обычный inbound с телефона не проверялись; личный Telegram не трогали. Riga остаётся нездоровой прежней нодой, вне этого поручения; /health 3 healthy из 4. Численные CPU/RAM/батарея не измерялись. Коммит/push не выполнялись.

Следующий шаг при необходимости: с участием пользователя проверить Telegram-вход и обычный fin, затем авторизованное добавление сайта из браузера.
