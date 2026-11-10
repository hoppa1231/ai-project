# Публикация всех подготовленных обновлений

Поручение пользователя: «опубликуй все», в контексте Android гостевого выбора/свайпов и иконки Windows.

Опубликованы штатными скриптами:
- Android1.3.4/code10 ARM64: гостевой выбор сервера, доступность выбора при OFF, синхронизация content/offset свайпа. Прежняя подпись/package сохранены; assembleDebug/testDebugUnitTest PASS21/0. Артефакт dist/android-updates/10/ChSPCh-1.3.4-10-arm64-v8a-2e9fe026a1ef.apk. SHA2562e9fe026a1ef32bbaea3d92b2299be032b3bc32d1ffeefe29f5e694a2ad6e2b0,80633127bytes.
- Desktop1.0.6 Windows/Linux: повышены DESKTOP_VERSION,packageVersion и имена упаковщиков. Новая иконка Windows EXE/Install/ярлыков; Linux выпуск для согласованного двухплатформенного feed. Gradle test/createDistributable/prepareWindowsJars PASS23/0. Helper/launcher кросс-собраны. В обоих Windows EXE подтверждены ICON/GROUP_ICON. CRC архивов/отсутствие session.json/.env проверены publisher.
- Windows127743410bytes SHA256d6765f5bdca806a4ea5c7cc2f5b8eaba7947dfc4fedc800818e42a23cec12f10.
- Linux110328484bytes SHA256b9a8fa52d156fd7b45ef16c7e39609198e89da358ebf20f8ac1b35cfe68fc46f.

Публикация через ops/android-updates/publish.py и ops/desktop-updates/publish.py; desktop сначала dry-run, затем skip-build, чтобы не пересобирать проверенные байты. Обе передачи desktop завершились с первой попытки; сервер проверил хеши перед переключением feed. Старые immutable версии сохранены. Публичные latest.json совпали с локальными manifest; HEAD постоянных Android APK/Windows ZIP/Linux tar.gz вернул200 и ожидаемые размеры. git diff --check PASS. README Desktop дополнен инструкцией ярлыков после упаковки: новая секция присутствует в checkout, в этих архивах README может быть без неё.

Постоянные ссылки:
https://tech-supp-test.ru/updates/android/latest-arm64-v8a.apk
https://tech-supp-test.ru/updates/desktop/

Ограничения: новые релизы не установлены на телефон/Windows/локальный Linux в этой задаче. Runtime свайпов/иконок и VPN не проверялись. Backend/узлы не менялись/не перезапускались. Публичная публикация приложений не является постом в Telegram; черновик не отправлялся. Коммит/push не выполнялись. Следующий шаг: установить предложенные обновления; на Windows полностью распаковать архив и запустить Install.exe для обновления ярлыков.
