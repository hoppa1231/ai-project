# Выбор сервера гостем и вспышка предыдущего экрана

Поручение: дать выбор сервера неавторизованным пользователям; исправить краткое появление предыдущего экрана при свайпе.

Изменения:
- android-client/app/src/main/java/app/chspch/client/MainActivity.kt: убраны Telegram-gates открытия Servers и onSelect; существующая выдача конфига передаёт выбранный exitNodeId и сбрасывает старый кеш при смене узла.
- android-client/app/src/main/java/app/chspch/client/Screens.kt: ServerTicket доступен и при OFF, можно выбрать узел перед подключением.
- MainActivity.kt: content и translation используют один composition snapshot, исключая чтение уже сброшенного offset старым graphicsLayer перед заменой содержимого. Offset теперь читается в композиции на кадрах свайпа; физическая плавность не измерялась.

Проверено: Android assembleDebug/testDebugUnitTest BUILD SUCCESSFUL,21 тест без ошибок. Backend GuestNodeSelectorTest3 PASS: заданный здоровый гостевой узел учитывается, резерв работает, выход за allowlist запрещён. Backend и узлы не менялись. git diff --check PASS. ADB вне sandbox: список устройств пуст. На телефоне гостевой выбор/свайп и VPN не проверены; статические и JVM-проверки не являются подтверждением runtime. APK: android-client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk, версия прежняя1.3.3/code9; публичное обновление не публиковалось. Выбор другого узла сохраняет прежнюю семантику применения при следующем подключении.

PROJECT_MEMORY.md дополнен новым решением. Черновик поста Telegram и его заметка сохранены. Коммит/push не выполнялись. Следующий шаг: проверить быстрые/отменённые/обратные свайпы и оба гостевых узла на телефоне, затем выпустить новую версию по поручению.
