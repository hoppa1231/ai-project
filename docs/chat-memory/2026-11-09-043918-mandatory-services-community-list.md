# Обязательные базовые VPN-сервисы и GitHub community-список

Задача: пользователь явно подтвердил обязательность базовых сервисов; расширить список заблокированных ресурсов из популярных GitHub источников. Выполнялась агентом routing_resources в составе общей UI/VPN задачи.

Изменения:
- `backend/src/main/kotlin/com/example/db/repo/PolicyRepository.kt`: DEFAULT VPN rules выдаются editable=false; effective policy игнорирует legacy default overrides, принудительно включает базовые правила; самостоятельные USER rules остаются управляемыми. Существующая PolicyRoutes нормализация отклоняет изменения locked default, неизменённые DEFAULT пропускает без создания override.
- `backend/src/main/resources/db/migration/V15__mandatory_services_community_domains.sql`: все встроенные VPN правила обязательны, старые default overrides удаляются; новый vpn-blocked-community DOMAIN_SUFFIX содержит389 доменов; версия политики повышается. Гостевой фильтр по двум Telegram keys сохранён.
- `ops/routing-lists/import.py`, `test_import.py`: воспроизводимый импортер, immutable SHA, IDNA/валидация/фильтры .ru/.su/.рф, явных российских сервисов и shared DNS, dedupe и suffix compaction без расширения набора.
- `backend/src/main/resources/routing/`: source.json, upstream-community.txt, blocked-community.txt, LICENSE-GPL-3.0.txt, README.md с лицензией/источником/воспроизведением/обновлением через следующую миграцию.

Выбор источника: live GitHub API показал itdoginfo/allow-domains1814 stars, license отсутствует; runetfreedom/russia-blocked-geosite604 stars, GPL-3.0. Выбран компактный antifilter-download-community.txt release e4a533393591266062b60beed3406dca4962e024 (7351 bytes,486 исходных строк); полный ru-blocked1.67MB и all41MB не включены. После фильтра389 доменов/5671 bytes, SQL7422 bytes. SHA256 upstream1fbb9b3b8862814c84371ff96b59f7b14bd3bcc7462a8396886d222ed8973e94. Это поддерживаемая community-выборка, не полный реестр и не доказательство текущей блокировки каждого домена.

Проверки: backend `bash ./gradlew --no-daemon test jar` PASS (24 теста, включая2 новых обработки mandatory/legacy override и сохранения disabled USER). Python importer3 теста PASS; offline воспроизведение миграции и cmp PASS. git diff --check для PolicyRepository PASS. Runtime VPN/production migration этим агентом не выполнялись; родитель координирует rollout и проверку.

Для patch текущего production jar копировать ВСЕ entries с prefix `com/example/db/repo/PolicyRepository` (включая PolicyRepositoryKt и generated lambdas), новую V15 миграцию и `routing/*`; устаревшие same-prefix classes сначала удалить из target. Учитывать существующие несвязанные изменения других чатов. Коммит/push не выполнялись. Следующий шаг: общий rollout с резервной копией БД, проверка обязательных enabled/editable=false правил API и runtime Android.
