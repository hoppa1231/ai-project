# Плитка Android VPN в быстрых настройках

Задача: включать и выключать ЧСПЧ из шторки Android.

Изменения:
- `android-client/app/src/main/java/app/chspch/client/vpn/VpnTileService.kt`: защищённая BIND_QUICK_SETTINGS_TILE плитка, статусы подключения/паузы/выключения. При заблокированном телефоне сначала unlockAndRun. Отключение/отмена подключения через текущую службу. Включение через обычный MainActivity connection flow с загрузкой актуального конфига и системным VPN consent, API34 PendingIntent для startActivityAndCollapse. Приватные конфиги не кешируются плиткой.
- `android-client/app/src/main/AndroidManifest.xml`: экспортированная системная плитка с BIND_QUICK_SETTINGS_TILE и ACTIVE_TILE.
- `android-client/app/src/main/java/app/chspch/client/vpn/SingBoxVpnService.kt`: актуальность runtime snapshot привязана к живой службе текущего процесса; сохранённый ON после остановки процесса/обновления больше не объявляется активным. Запрос обновления плитки после записи нового состояния, только при его смене.
- MainActivity hook реализован параллельно UI-агентом: CONNECT_VPN одноразово обрабатывается после bootstrap, запускает штатный cycleConnection для Off.

Проверки: `bash ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --offline` успешно, BUILD SUCCESSFUL. Перенос requestListeningState после apply выполнен после этого запуска; требуется общий финальный rebuild. Есть прежние deprecated allNetworks warnings. Живая проверка плитки/permission на телефоне этим агентом не выполнялась; статическая компиляция не подтверждает VPN-трафик. Плитку нужно добавить через системное редактирование быстрых настроек. Следующий шаг: интеграционная сборка и проверка на устройстве, публикация новой версии родительским агентом. Коммиты и push не выполнялись.
