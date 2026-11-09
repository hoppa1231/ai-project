# Недоступные узлы, сохранение входа и гостевой резерв

## Запрос и диагностика

Пользователь сообщил: два обычных узла из трёх недоступны, приложение не видит авторизацию и не получает новый доступный узел. Проверено актуальное дерево с существующими чужими изменениями; коммиты/push не выполнялись.

Live backend moscow: health/db ok. Проверки node-agent с backend: fin и ned UNHEALTHY/Connect timed out, fra HEALTHY. Это проверка control plane, не доказательство доступности VPN с телефона. Позже подтверждён реальный трафик через fra.

В Android BackendApi.bootstrap несколько запросов выполнялись параллельно; каждый мог одновременно ротировать один refresh-токен. recoverSessionAfterUnauthorized сбрасывал сессию даже при сетевом исключении, затем создавал гостя. Гостю выдавался только DEFAULT_VPN_NODE_ID=ned-guest, который недоступен. MainActivity очищала список при ошибке, backend /nodes скрывал нездоровые узлы. На телефоне в старом com.securevpn.app подтверждены только безопасные признаки: токены есть, локальной отметки Telegram-входа уже нет. Утверждать, что конкретный прежний сброс вызван именно гонкой, без старых логов нельзя.

## Код

- android-client/.../data/SessionLock.kt и BackendApi.kt: сериализация создания/обновления сессии, повторное использование уже обновлённого токена. Сетевые ошибки не очищают вход. Невалидная refresh-сессия связанного аккаунта требует повторного Telegram-входа, без замены гостем.
- MainActivity.kt: кеш списка между запусками, сохранение списка при ошибках с пометкой недоступности, выбор здорового узла при обновлении. Ручной Telegram-вход доступен и при сохранённом аккаунте.
- AppModels.kt/Components.kt: доступность из health; нездоровые/непроверенные узлы видны, но не выбираются.
- backend NodeRepository.listPublic(includeUnavailable=true) применяется для /nodes. По умолчанию выдача конфигураций по-прежнему использует только здоровые узлы.
- AppConfig.kt, GuestNodeSelector.kt, NodeRoutes.kt, VpnRoutes.kt: FALLBACK_GUEST_NODE_IDS — список UUID дополнительных ограниченных гостевых входов. Гость видит только default+fallback, обычный пользователь их не видит. Посторонний exitNodeId не позволяет гостю выбрать обычный узел. При отказе выбранного гостевого входа используются только явно разрешённые здоровые гостевые узлы. CASCADE гостю не выдаётся.
- backend/.env.example, docker-compose.yml, docs/selective-vpn.md обновлены.

## Развёртывание

- Backend образ securevpn-backend:node-status-fix из прежнего unlimited-v14 + новый проверенный JAR. Изменён только backend-контейнер; БД не сбрасывалась. Образ сохранён в /opt/vpn-control-plane/docker-compose.prod.yml. Резервные compose/env и overlay: /opt/securevpn-staging/node-status-fix/. Старый образ сохранён для отката. Приложение собрано Gradle9.4.1 из локального кеша, поскольку загрузка штатного9.3 слишком медленная; серверные зависимости прежние.
- На fra добавлен отдельный inbound446 fra-guest с Telegram IP allowlist, TCP DNS1.1.1.1:53 и финальным block; обычный447 сохранён. БД панели и agent.env сохранены перед изменением. У node-agent добавлена карта fra-guest:2. Использован прежний фиксированный digest образа агента из памяти проекта.
- Пользователь отдельно разрешил одну отключённую служебную запись в новом inbound. Не включать её.
- fra-guest зарегистрирован штатным admin API, включён в FALLBACK_GUEST_NODE_IDS backend. Default ned-guest сохранён. Health после добавления3/8 (включая backend-entry), это динамическое значение.
- Временный /etc/sudoers.d/99-max-temporary на fra после настройки VPN удалён; sudo -n true перестал работать. Позже обнаружилась отдельная проблема relay, для его переноса повторно запрошен sudo; см. продолжение ниже.
- На телефоне установлен новый app.chspch.client («ЧСПЧ»), старый com.securevpn.app и его данные сохранены. Это отдельный пакет без автоматической миграции входа. Финальный APK: android-client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk.

## Проверки

- Android assembleDebug/testDebugUnitTest:18 tests,0 failures/errors. Новые тесты: NodeAvailabilityTest, SessionLockTest (20 конкурентных401 → одна ротация, ошибка сети и последующий retry).
- Backend test/installDist:17 tests,0 failures/errors. NodeRepositoryTest проверяет каталог vs выдачу, GuestNodeSelectorTest — ограниченный fallback и запрет выхода из allowlist.
- Relay:4 unit-теста прошли.
- Live с moscow через Xray fra446: telegram.org HTTP200; example.com отказ; тестовый клиент удалён, новое соединение старым клиентом отклонено. Root-only скрипт/backup на moscow: check-register-fra-guest.py, before-fra-guest.dump. Секреты в заметку не копировать.
- На телефоне показаны «НА СВЯЗИ», «ФРАНЦИЯ · ТЕЛЕГРАМ»; безопасное чтение кеша подтверждает активный fra-guest, в каталоге ned UNHEALTHY/fra HEALTHY. На fra446 один активный клиент и одна отключённая служебная запись. Это не подтверждает Telegram-сообщения/звонки.
- git diff --check прошёл.

## Отдельная проблема Telegram-входа, ещё в работе

Пользователь не может войти: бот не отвечает. Служба relay на fin active, но журнал показывает повторные URLError. Разделение сетевых направлений: fin→api.telegram.org HTTP200, fin→backend HTTPS timeout, fin→backend SSH22 timeout; fra→Telegram и fra→backend доступны. Нужен перенос единственного relay на fra с сохранением offset и root-only env. Не запускать два getUpdates consumer одновременно. Код/unit подготовлены на fra в /tmp без секретов; пользователь получил запрос повторного sudo. Перенос, авторизованный список трёх обычных узлов и сохранение входа после перезапуска пока не проверены. После переноса нужен свежий app-login challenge и пользовательский «Старт».

### Продолжение: relay перенесён

Пользователь повторно выдал временный sudo на fra. На fin securevpn-telegram-relay.service остановлен и disabled до переноса. Root-only env и числовой offset переданы напрямую по SSH без локального файла и без вывода содержимого. На fra установлен прежний код/unit с DynamicUser/StateDirectory, служба enabled/active. Журнал подтвердил доставку трёх накопленных updates в backend; fin disabled/inactive. Это подтверждает канал доставки, но не свежий завершённый пользовательский вход. Пользователю открыт свежий challenge, ожидается «Старт» и возврат в новый пакет. Временный sudo ещё должен быть удалён после этой проверки. README relay обновлён.

### Итог: свежий вход и полное подключение проверены

Пользователь подтвердил «завершил вход». Безопасное чтение prefs нового приложения: Telegram linked=true; после force-stop/повторного запуска значение сохранилось. Каталог содержит обычные fin/ned/Riga с UNHEALTHY и fra HEALTHY; UI показывает недоступные строки с «НЕДОСТУПЕН», Франция выбрана.

Обнаружен дополнительный серверный дефект: /vpn/issue повторно возвращал прежний ISSUED-конфиг на fin, игнорируя недоступность, смену выбранного узла и режима. Отзыв перед ротацией синхронно требовал ответа недоступного node-agent, а REVOKING и ACTIVE удерживали уникальные слоты устройства.

Исправлено в VpnRoutes.kt, VpnConfigReuse.kt, VpnRepository.kt, VpnRevocationWorker.kt, Application.kt. Повторное использование только здорового, неистёкшего конфига нужной ноды/режима. При недоступности старого узла удаление не ожидается; транзакция помечает старые локальные client/config EXPIRED и error_code=REVOCATION_PENDING, освобождая слот для нового. **Это не подтверждение физического отзыва на недоступном узле**: исходные записи и явный pending-marker сохранены. Фоновый worker каждые20сек повторяет удаление после восстановления health; только успешный ответ агента приводит к REVOKED и снятию pending-marker. Ошибка удаления достижимого узла тоже откладывается; отмена coroutine не проглатывается. Повторный Idempotency-Key старого недоступного конфига возвращает409 вместо попытки создать дубликат.

Новых миграций БД нет. Перед rollout сохранён root-only /opt/securevpn-staging/before-failover-fix.dump. Финальный образ securevpn-backend:node-status-fix-v2, выбран в рабочем compose и staging overlay. Предыдущие образы/compose сохранены. Финальные backend test/installDist прошли:22 tests,0 failures/errors; Android18 и relay4 прошли. Один запуск Gradle падал из-за файлов incremental/test-cache; повтор с --max-workers=1, -Pkotlin.compiler.execution.strategy=in-process и -Pkotlin.incremental=false успешен.

На moscow отдельная синтетическая гостевая сессия проверила durable pending + worker: test config переведён в pending, worker выполнил удаление на fra и выставил REVOKED. После проверки вызван штатный revoke cleanup. Скрипт без сохранённых токенов: /opt/securevpn-staging/check-deferred-revoke.py. Реальный отложенный fin-конфиг может оставаться на недоступном узле до восстановления и фонового удаления; не называть его физически отозванным.

На телефоне после повторного подключения: Telegram linked=true, active location Франция, обычный VLESS447, runtime-state ON. UI «НА СВЯЗИ»; реальные HTTPS-индикаторы ya.ru, Google generate_204, telegram.org показывают «работает». На fra447 один активный клиент и одна disabled-служебная запись. Это не проверка всех сайтов, Telegram-звонков или восстановления fin/ned.

На fra relay enabled/active, env0600. Временные root-only переносные копии env/offset и /tmp-код удалены; рабочие env/state сохранены. Повторный временный sudo удалён после проверки входа, sudo -n true снова не проходит. Старый relay fin disabled/inactive. Git diff --check прошёл; коммит/push не выполнялись. Следующий отдельный шаг при необходимости — расследование сетевой недоступности fin/ned; причина не установлена.
