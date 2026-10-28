# План интеграции open-source AI и покерного движка

Цель: Android-приложение для анализа **сохранённых скриншотов/раздач и учебных сценариев**. Не менять существующий MediaProjection/capture, ручной выбор карт и overlay без отдельной проверки.

## Что уже есть в проекте

- Java 17, Android SDK 35, minSdk 26.
- ONNX Runtime Android 1.28.0 и ML Kit Text Recognition 16.0.1.
- Встроенная модель `playing_card_detector.onnx` и классы распознавания карт.
- `PokerEngine` с представлением карты и расчётом equity Monte Carlo.

По диагностике предыдущего запуска ONNX-модель почти не выдавала полезных confidence score. Поэтому простая замена вызова OCR на ещё одну библиотеку не решит проблему: необходимо согласовать входной тензор, нормализацию, размер и порядок классов конкретной модели.

## Кандидаты для повторного использования

### Распознавание карт

1. [PokerVision](https://github.com/Yufan3/PokerVision) — YOLO, генератор синтетических изображений карт, режимы Detect/Hold'em. Полезен как ориентир для детектора и датасета. Python-проект; модель и препроцессинг нужно отдельно экспортировать/адаптировать для Android.
2. [PlayingCardsDetection](https://github.com/Sxres/PlayingCardsDetection) — YOLO + OpenCV, классы 52 карт, стабилизация предсказаний между кадрами. Python-проект; не подключается напрямую как Android-библиотека.
3. [ONNX Runtime Android](https://github.com/microsoft/onnxruntime) — уже подключён. Предпочтительный runtime для модели, экспортированной в совместимый ONNX.
4. [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition) — уже подключён; оставить как вспомогательный OCR для рангов, но не полагаться на OCR как единственный способ определить масть.

**Решение:** не добавлять второй тяжёлый inference runtime. Сначала проверить текущую ONNX-модель на реальных кадрах и привести preprocessing/output decoder к её фактической спецификации. Если модель несовместима, заменить только файл модели и соответствующий decoder после проверки лицензии, классов и точности.

### Покерная математика

1. [Fold'em (Java)](https://github.com/hashbender/foldem) — evaluator, equity рук и диапазонов, range representation. Ближе всего по языку к Android-проекту, но совместимость/зависимость Maven Central и поведение на Android нужно проверить до добавления в Gradle.
2. [poker-apprentice/hand-evaluator](https://github.com/poker-apprentice/hand-evaluator) — evaluator и equity для Hold'em и других вариантов; TypeScript, поэтому это скорее эталон тестов/алгоритмов, а не прямая Java-зависимость.
3. [Poker Equity Engine](https://github.com/danstam/poker-equity-engine) — C engine + Monte Carlo + Python/FastAPI; можно изучить для оптимизации, но Python/API-слой не подходит для прямого встраивания в APK без отдельного native-порта.
4. [Poker Toolkit](https://github.com/erikfastermann/poker-toolkit) — equity, ranges и GUI на Rust; кандидат для изучения алгоритмов, не прямой Android dependency.

**Решение:** не переписывать текущий `PokerEngine` вслепую и не добавлять одновременно несколько evaluator-библиотек. Сначала добавить тестовые эталонные раздачи (включая wheel straight, kicker, split pot, blockers), сравнить текущий движок с проверенным evaluator, затем заменить/обернуть только если тесты выявят расхождения.

## Целевая архитектура

- `ScreenFrameSource`: существующий захват экрана — не менять без необходимости.
- `CardDetectionEngine`: единый интерфейс для ONNX inference.
- `CardCandidate`: ранг, масть, confidence, bounding box.
- `CardValidationPipeline`: исключать дубли карт, учитывать стабильность результатов по нескольким кадрам, не подтверждать низкую уверенность.
- `Board/Player zones`: фиксированная настраиваемая область hero-карт; board определяется отдельно. Координаты хранятся относительно размеров экрана, чтобы переживать другое разрешение.
- `PokerEngine`: отдельный детерминированный evaluator + Monte Carlo equity; вычисления вне UI-потока.
- `AnalysisSnapshot`: карты, confidence, улица, число симуляций и результат — для отладки и воспроизводимых тестов.

## Обязательные критерии перед включением в релиз

1. Вход модели: правильный размер, RGB/BGR, нормализация, layout тензора и порядок 52 классов подтверждены документацией/экспортом.
2. Набор тестовых скриншотов покрывает все 52 карты, разные масштабы и темы стола.
3. Невозможные состояния не проходят в расчёт: повтор карты, >5 карт борда, неполная пара hero-карт.
4. Equity проверяется на фиксированных сценариях и учитывает ничьи; результаты содержат число симуляций.
5. Лицензии исходников и весов проверены отдельно: лицензия кода не всегда покрывает веса и датасет.

## Источники

- https://github.com/Yufan3/PokerVision
- https://github.com/Sxres/PlayingCardsDetection
- https://github.com/microsoft/onnxruntime
- https://github.com/hashbender/foldem
- https://github.com/poker-apprentice/hand-evaluator
- https://github.com/danstam/poker-equity-engine
- https://github.com/erikfastermann/poker-toolkit
