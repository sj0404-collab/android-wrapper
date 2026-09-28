# VPN Wrapper

Модульная обёртка для Android, из которой осталась **только VPN-составляющая**.

Приложение поднимает `VpnService`, заворачивает трафик в туннель до выбранного
сервера по HTTP CONNECT и продолжает работать в фоне как foreground-сервис.

## Возможности

- Список серверов: DNS, Proxy и VPN-протоколы, плюс свои серверы
- Проверка пинга всех серверов, тест скорости, проверка внешнего IP
- Per-app VPN: маршрутизация только выбранных приложений через туннель
- Статистика: отправлено/принято байт, текущая скорость, пинг, jitter
- Foreground-уведомление с кнопкой «Отключить» и живой статистикой
- Плитка в шторке быстрых настроек для включения/выключения
- Автовосстановление соединения после перезагрузки

## Где запускается

| Точка входа | Описание |
|---|---|
| Иконка приложения | Полный UI: серверы, приложения, логи |
| Плитка в шторке | Вкл/выкл без открытия приложения |
| Уведомление | Открывает приложение, кнопка отключения |
| После перезагрузки | `BootReceiver` поднимает VPN, если он был включён |
| `VpnActivity` | Отдельный экран подключения |

## Разрешения

Только то, что нужно VPN:

- `INTERNET`, `ACCESS_NETWORK_STATE` — сеть
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` — работа в фоне
- `POST_NOTIFICATIONS` — уведомление о подключении
- `RECEIVE_BOOT_COMPLETED` — автостарт после перезагрузки
- `WAKE_LOCK` — не давать заснуть

## Сборка

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Сборка и тест прогоняются в GitHub Actions (`.github/workflows/build.yml`),
результат доступен как артефакты `app-debug` и `app-release`.

## Структура

```
app/src/main/java/com/wrapper/
├── vpn/
│   ├── BrowsecVpnService.java   # VpnService, туннели, foreground-уведомление
│   ├── VpnActivity.java         # экран подключения
│   ├── VpnNotificationManager.java
│   ├── NetworkMonitor.java      # пинг, скорость, jitter
│   ├── VpnTileService.java      # плитка в шторке
│   └── BootReceiver.java        # автостарт после перезагрузки
└── ui/
    └── MainActivity.java        # вкладки DNS / Proxy / VPN / Apps / Logs
```
