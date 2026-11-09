# Выкладка админ-панели без видимых cp.local

## Поручение
Выкатить обновление; технические имена cp.local больше не должны отображаться в панели.

## Изменения
`backend/src/main/kotlin/com/example/modules/admin/AdminRoutes.kt`: в мониторинге и списке клиентов вместо технического email отображаются Telegram username/имя/ID или гостевая подпись. В подтверждении удаления технический email также убран. Внутренние идентификаторы/API не переименованы, конфигурации не перевыдавались. Нулевой inventory Telegram ID не считается привязкой. Формы создания ноды/выдачи квот отсутствуют по предыдущему поручению.

## Развёртывание
Перед выкладкой проверен live backend: securevpn-backend:node-status-fix-v2. Чтобы сохранить свежие исправления других чатов, использован его jar; заменены только entries `com/example/modules/admin/` из локальной сборки. Побайтово проверено совпадение всех остальных entries. UserRepository уже содержал telegramProfile в действующем jar, его менять не понадобилось. Контрольная сумма baseline совпала с jar действующего контейнера.

Новый образ securevpn-backend:admin-telegram-20261109 создан FROM node-status-fix-v2, заменяет только /app/lib/vpn-control-plane-0.1.0.jar. Обновлён image в /opt/vpn-control-plane/docker-compose.prod.yml; docker-compose up --no-build --no-deps backend успешно пересоздал только backend. Staging /opt/securevpn-staging/admin-telegram-20261109; compose-before.yml и предыдущий образ сохранены для отката. Миграций БД не добавлялось.

## Проверки
- Локально Gradle9.4.1 backend test jar: BUILD SUCCESSFUL,22 tests/0 failures/errors. Это число включает актуальные тесты параллельных изменений checkout; их классы в релиз не переносились.
- node --check, VM-проверки отрисовки/ссылки Telegram/экранирования/поиска, git diff --check прошли.
- На сервере health status/db ok,8 totalNodes,3 healthyNodes (динамические показатели).
- /admin содержит мониторинг, не содержит форм Create node/Grant quota и выражений вывода client.email/c.email.
- Неавторизованный POST /admin/monitor/sync вернул401.
- Через штатный /auth/login административными реквизитами из окружения: мониторинг вернул15 конфигураций,1 Telegram-linked,1 с username;3 failedNodes. Реквизиты/содержимое пользовательских записей в заметку и вывод не включались.
- Контейнер running на новом image. HTTPS https://tech-supp-test.ru/admin открыт в браузере, визуально проверен экран до входа.

## Ограничения
В браузере проверена только публичная оболочка панели, авторизованный API проверен отдельно.3 узла не ответили на опрос; причину их недоступности в этом задании не исследовали. VPN на телефоне, трафик и Telegram-сообщения не проверялись.
