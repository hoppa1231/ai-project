# Настольный прототип ЧСПЧ

## Задача и решения

Пользователь согласился попробовать клиент для Windows и Linux после обсуждения
этапов переноса. Первый этап — отдельный `desktop-client/`, Kotlin/Compose Desktop,
общий существующий API и sing-box. Android, backend и чужие изменения не менялись.
В текущем дереве уже были существенные незакоммиченные изменения; commit/push нет.

## Реализация

- `desktop-client/build.gradle.kts`, wrapper/settings: самостоятельная сборка,
  JDK21, Kotlin1.9.24, Compose1.6.11; автономный app image с Java.
- `src/main/kotlin/app/chspch/desktop/Main.kt`: русское окно, серверы и health,
  выбор ядра, локальный прокси/TUN, подключение/отключение, повторный отзыв,
  Telegram app-login с открытием браузера и ручной проверкой подтверждения.
- DesktopApi.kt: guest/refresh/nodes/policy/issue/revoke/login; последовательные
  операции, отказ refresh не стирает аккаунт. ID неудачного отзыва сохраняется.
- DesktopConfig.kt: DIRECT по умолчанию, домены/IP/GEOIP, блокировка через reject,
  DNS для VPN-доменов через VPN; loopback mixed17890 и опциональный TUN.
  Android APP_PACKAGE на ПК не применяется и явно помечается в UI.
- TunnelRuntime.kt: проверка версии/конфига и сигнала готовности sing-box,
  контроль живого процесса, остановка и удаление временного приватного JSON.
- StateStore.kt: сессия в пользовательском каталоге; POSIX0700/0600,
  атомарная запись; в Windows наследуется ACL профиля. Keyring пока нет.
- `scripts/fetch-core.py`: sing-box1.14.4 Linux/Windows x64 из официального релиза,
  закреплённые SHA256, извлечение только runtime-файлов/лицензии.
- `.github/workflows/desktop.yml`: подготовлена сборка Linux/Windows image и MSI
  на Windows; workflow не отправлялся и не запускался.
- README.md: запуск, сборка, данные, границы и следующая работа.

## Проверки и результаты

- test/createDistributable: BUILD SUCCESSFUL;7 тестов,0 failures/errors.
  Включены сохранение linked-сессии при refresh503, повторный отзыв после502,
  DIRECT default/reject/DNS/TUN bypass и проверка обоих JSON реальным ядром.
- Linux GUI реально открылся в Hyprland, интерфейс осмотрен.
- Обнаружена и исправлена потеря executable-права sing-box при копировании
  ресурсов Compose: hook createDistributable выставляет его в app image.
- Реальный изолированный guest smoke через Францию: запуск ядра, Telegram HTTPS
  через selective loopback proxy HTTP200, остановка и серверный REVOKED.
  Повторено launcher-ом автономного пакета со встроенными runtime и ядром:
  временная замена mainclass на SmokeKt в сгенерированном cfg, затем гарантированно
  восстановлен MainKt; повтор также успешен. Это не системный TUN-тест.
- Первый smoke выбрал Нидерланды (изначально HEALTHY): probe не прошёл, revoke502
  XRAY_REMOVE_FAILED. Повторный каталог: Нидерланды UNHEALTHY. Поздний повтор отзыва
  подтвердил REVOKED, закрытый временный каталог удалён. Все smoke-доступы
  отозваны, их процессы остановлены. Причину отказа недоступного узла не меняли.
- git diff --check прошёл.
- Автономный Linux x64 архив: desktop-client/build/distributions/
  chspch-linux-x64-0.1-prototype.tar.gz (92MiB). Содержит всю папку Chspch,
  запуск bin/Chspch. SHA256710bb983a2cbc5a081bc0c6371d405e33123c1056cfe77fa8c0a2f49828266ea.
  Архив/сборка/скачанные бинарники исключены из Git.

## Ограничения и следующий шаг

Windows не собиралась и не запускалась здесь; реальный TUN/маршруты/DNS ОС,
свежий Telegram-вход с ПК и обычный авторизованный выход не проверены.
Нет привилегированного помощника/службы, трея, автостарта/обновления,
восстановления после сна/смены сети, редактора маршрутизации, выбора программ ПК.
Не запускать весь GUI от root ради TUN. Следующий этап — ограниченный системный
помощник и испытания TUN на Linux/Windows; затем авторизованный сценарий и упаковка.
Нативный launcher пишет stderr «pure virtual method called / terminate called»
при запуске, но GUI и packaged smoke продолжаются успешно. Источник сообщения
отдельно не установлен; не объявлять это падением приложения.
