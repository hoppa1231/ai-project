# Восстановление Linux-установки для Quickshell

Пользователь не находил Linux-клиент в Quickshell. Проверка: ~/.local/share/applications/chspch.desktop существовал, но Exec указывал на отсутствующий ~/.local/share/chspch/bin/Chspch; в каталоге установки находились только исходные packaging scripts. Причина появления этого состояния точно не установлена.

Неполный каталог сохранён как ~/.local/share/chspch.incomplete-20261109-qs. Полный опубликованный архив1.0.5 распакован в /tmp/chspch-linux-repair-105, оттуда выполнен install-linux.sh. Ярлык восстановлен, update-desktop-database выполнен, desktop-file-validate PASS. Клиент запущен прямым launcher, hyprctl подтвердил окно «ЧСПЧ · ВПН трудящихся», process Chspch. VPN не включался и в этом шаге не тестировался. Сообщение native launcher pure virtual method called воспроизвелось, но окно успешно открывается (ранее известное ограничение).

desktop-client/scripts/install-linux.sh теперь до любых изменений проверяет наличие bin/Chspch и lib/runtime у источника; запуск из исходного scripts завершается понятной ошибкой. Существующая проверка fresh install+two updates+rollback PASS. Опубликованный1.0.5 архив не перезапаковывался; защитная проверка попадёт в следующий релиз. Коммит/push не выполнялись. Если Quickshell держит старый список, закрыть/открыть меню; запуск из терминала ~/.local/share/chspch/bin/Chspch доступен напрямую.
