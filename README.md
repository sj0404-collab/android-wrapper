# Пример конфигурации модулей
# Поместите этот файл в /assets/ или загрузите через SAF

## Доступные модули:

### TTS (Text-to-Speech)
- engine: google, pico
- language: ru-RU, en-US и др.
- speed: 0.5 - 2.0
- pitch: 0.5 - 2.0

### OCR (Распознавание текста)
- engine: mlkit
- languages: массив языков
- auto_translate: true/false

### Translator
- engine: google, yandex
- source_lang: auto, ru, en и др.
- target_lang: ru, en и др.

### Router
- rules: массив правил маршрутизации
  - app: пакет приложения
  - action: intercept, redirect, modify
  - target: какой модуль использовать

## Добавление новых модулей:
1. Создайте папку в /assets/modules/[имя_модуля]/
2. Добавьте config.json с настройками
3. Реализуйте интерфейс модуля в коде
