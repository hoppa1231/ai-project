# Desktop1.0.2: список установленных приложений

Задача пользователя: вместо неудобного выбора исполняемого файла через проводник/файловый менеджер сканировать установленные приложения и выбирать их в стиле ЧСПЧ. Только ПК, Android/backend не менялись.

Изменения:
- desktop-client/src/main/kotlin/app/chspch/desktop/InstalledApps.kt: Linux XDG меню пользователя/системы, localized Name, Desktop Entry section, Hidden/NoDisplay, пользовательский приоритет, PATH/quoted Exec/env assignments/realpath, dedup. Простые exec shell wrappers (Firefox) разрешаются в реальный файл; Discord user bootstrap путь поддержан. Общие shell/java/Flatpak/Snap launchers не выбираются целиком, сложные неразрешимые wrappers пропускаются. Это не полный инвентарь всех форматов установки.
- Windows PowerShell: read-only COM разрешение ярлыков Пуска/рабочего стола + App Paths registry HKCU/HKLM/WOW6432Node, Squirrel Update.exe processStart actual app resolution. Список JSON передаётся через временный файл с cleanup и30s timeout. Общие shell/updater/uninstaller .exe исключены. Никаких приложений сканер не запускает, диск целиком не обходит.
- AppPicker.kt: отдельное окно в PosterTheme, поиск по названию/имени файла, множественные отметки, только выбранные, обновление, счётчик, отмена/единое применение. Сохраняются прежние выбранные пути, даже отсутствующие в результатах сканирования. Фоновое IO сканирование.
- Main.kt: «Выбрать приложения» — основная кнопка, ручной «Указать файл» оставлен второстепенным для portable. Выбранные программы показывают имя вместо полного пути; путь доступен в списке.
- DesktopController.kt: selectApps единым действием валидирует новые пути, сохраняет локально processPaths/processAppNames, один раз применяет изменение к активному туннелю. Отмена диалога не меняет состояние. Правила программ по-прежнему действуют только в TUN; дочерние процессы с другими файлами могут требовать отдельных правил.
- native/API версия1.0.2, README и упаковщики обновлены.

Проверки:
- Gradle test/createDistributable/prepareWindowsJars PASS,16 JVM tests/0 failures. Новые InstalledAppsTest: quotes/env/sandbox launcher exclusion, user menu override/Hidden suppression/localization/dedupe/action section isolation, actual executable resolution for simple shell wrappers.
- Реальный Linux скан:57 приложений,110ms, Telegram найден. Скан не менял выбранные маршруты. Это проверка обнаружения, не доказательство VPN-трафика каждого приложения.
- Linux установлен штатным установщиком с сохранением профиля, GUI запущен. Сам диалог выбора физически полностью не пройден; запуск оболочки подтверждён, компиляция диалога PASS. Windows runtime/PowerShell/UAC/process routing здесь не проверялись.
- Windows ZIP CRC PASS, git diff --check PASS.

Артефакты:
- desktop-client/build/distributions/chspch-linux-x64-1.0.2.tar.gz SHA256 4efafd893c4b301498ac1000e0b0b8ae46f9eba63b90cf9e143451cc0c274241
- desktop-client/build/distributions/chspch-windows-x64-1.0.2.zip SHA256 8b89c9ccaacc97e6dc9ec1a9c9be3fa9e4a30ef87a3ff4a90744342feeacd4b0

Следующий шаг: пользователь открывает Маршруты/Выбрать приложения, проверяет обнаружение своих программ и их соединения в TUN. Windows установить1.0.2 и проверить сканер на реальном ПК. Commit/push нет; прочие незакоммиченные изменения сохранены.
