# Проверка проекта

Дата проверки: 2026-08-01.

## Выполнено в текущей среде

- все Java-файлы приложения скомпилированы JDK 17 против локальных Android/ARCore API-стабов;
- чистый Java-модуль экспорта запущен на синтетическом облаке из 400 точек;
- экспорт сформировал 81 voxel-ячейку и 396 треугольников;
- команда `unzip -t` успешно проверила все файлы тестового ZIP:
  `scan_points.ply`, `scan_points.obj`, `scan_mesh.obj`, `scan_mesh.stl`,
  `scan_metadata.json`, `README_RU.txt`;
- все 9 XML-файлов Android-ресурсов успешно разобраны XML-парсером;
- Python-код сервера успешно прошёл `python -m compileall`;
- shell-скрипт запуска Gradle проверен синтаксически.

## Что не было выполнено

Полная сборка Gradle, установка APK и сканирование на физическом телефоне в этой
среде не выполнялись: здесь нет Android SDK, камеры и ARCore-совместимого устройства.
GitHub Actions устанавливает необходимые SDK-пакеты и выполняет unit-тест,
`assembleDebug`, `assembleRelease` и `bundleRelease`.

До публикации рекомендуется проверить на реальном устройстве:

1. запуск ARCore и запрос разрешения камеры;
2. поддержку `RAW_DEPTH_ONLY` конкретной моделью телефона;
3. ориентацию RGB-цветов относительно глубины;
4. устойчивость накопления при полном обходе объекта;
5. импорт PLY/OBJ/STL в целевую программу;
6. память и скорость на длинном скане.

## AndroidX build configuration

The project has `android.useAndroidX=true`, which is required because ARCore 1.54.0 depends on `androidx.annotation`. Jetifier remains disabled because no legacy Android Support Library dependencies are used.
