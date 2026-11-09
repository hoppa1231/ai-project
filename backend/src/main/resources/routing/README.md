# Источник VPN-доменов

Данные: [runetfreedom/russia-blocked-geosite](https://github.com/runetfreedom/russia-blocked-geosite),
набор `antifilter-download-community.txt`, GPL-3.0 (LICENSE-GPL-3.0.txt).
Авторы upstream собирают community.antifilter.download и другие списки; подробности в их README.
Сохранены оригинальный snapshot, нормализованный список и source.json с SHA256 и неизменяемой ревизией.
Данные распространяются отдельно от кода приложения с исходным snapshot и лицензией.

Выбран компактный community-набор, а не полный реестр сотен тысяч доменов.
Это рекомендации маршрутизации, не доказательство актуальной блокировки каждого домена.
Сохранён DIRECT по умолчанию; .ru/.su/.рф, явные российские сервисы и общие DNS исключены.
Дочерние домены удаляются только если их уже покрывает родитель в исходном наборе.
Гостю выдаются только прежние Telegram-правила. После входа базовые сервисы обязательны;
пользовательские приложения и сайты по-прежнему можно включать и отключать.

Воспроизведение без сети из корня:

```
python3 ops/routing-lists/import.py --source backend/src/main/resources/routing/upstream-community.txt --output /tmp/V15-reproduced.sql
cmp /tmp/V15-reproduced.sql backend/src/main/resources/db/migration/V15__mandatory_services_community_domains.sql
```

Обновление: выбрать полный commit SHA release-ветки upstream, выполнить
`python3 ops/routing-lists/import.py --revision SHA --output backend/src/main/resources/db/migration/V16__community_domains_refresh.sql`,
проверить diff/исключения/число доменов и сохранить новую миграцию. Не переписывать уже применённую V15.
Импорт оставляет пользовательские самостоятельные правила, но удаляет устаревшие overrides
обязательных базовых правил. Встроенных сетевых обновлений на телефоне нет: проверенный snapshot приходит от backend.
