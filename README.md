# Depth Scanner 3D

Android-приложение, которое получает настоящую карту глубины через ARCore Raw Depth, переводит её в метрическое 3D-облако точек и экспортирует модель для Blender, MeshLab, CloudCompare, CAD-подготовки и 3D-печати.

## Что уже реализовано

- проверка установки и совместимости ARCore;
- проверка поддержки Raw Depth API;
- получение 16-битной глубины и карты уверенности;
- фильтрация точек по расстоянию и confidence;
- перевод пикселей глубины в координаты камеры и затем в локальные координаты стартового AR-якоря;
- цвет каждой 3D-точки из синхронного YUV-кадра камеры;
- накопление с voxel-фильтром 12,5 мм и усреднением повторных наблюдений;
- предпросмотр цветного облака точек поверх камеры;
- ограничение памяти до 350 000 накопленных точек;
- экспорт через системный выбор файла в единый ZIP;
- дополнительный Open3D-сервер для получения сглаженной сетки и GLB.

## Форматы внутри ZIP

| Файл | Назначение |
|---|---|
| `scan_points.ply` | Основное цветное облако точек в метрах |
| `scan_points.obj` | OBJ point cloud с цветами вершин |
| `scan_mesh.obj` | Черновая локальная треугольная voxel-сетка |
| `scan_mesh.stl` | Сетка для программ 3D-печати |
| `scan_metadata.json` | Размеры, число точек, параметры фильтрации |
| `README_RU.txt` | Краткая памятка по экспорту |

## Реальные ограничения

Локальная OBJ/STL-сетка строится из занятых voxel-ячеек. Она автономна и метрически масштабирована, но выглядит ступенчато. Для гладкой модели используйте `scan_points.ply` с сервером `reconstruction-server` или внешней программой реконструкции.

ARCore Depth лучше работает на неподвижных матовых объектах с фактурой и равномерным светом. Стекло, зеркала, блестящий металл, волосы и движущиеся объекты дают пропуски и шум.

## Требования

- Android 8.0 или новее;
- устройство с поддержкой ARCore и Depth API;
- установленный компонент Google Play Services for AR;
- для сборки: JDK 17 и Android SDK 36.

## Сборка на компьютере

Linux/macOS:

```bash
chmod +x gradlew
./gradlew :app:assembleDebug
```

Windows:

```bat
gradlew.bat :app:assembleDebug
```

Готовый устанавливаемый APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Скрипты `gradlew` и `gradlew.bat` автоматически загружают Gradle 8.13 при первом запуске.

## Сборка на GitHub

1. Создайте пустой репозиторий.
2. Загрузите содержимое проекта в корень.
3. Откройте вкладку **Actions**.
4. Запустите workflow **Android build**.
5. Скачайте artifact `DepthScanner3D-debug-apk`.

Debug APK подписывается стандартным отладочным ключом GitHub runner и устанавливается вручную. Release APK/AAB в текущем workflow не подписаны личным ключом разработчика.

## Как сканировать

1. Поставьте предмет неподвижно и обеспечьте рассеянное освещение.
2. Нажмите **Начать сканирование**.
3. Медленно обойдите объект, удерживая примерно одинаковое расстояние.
4. Снимите верх, боковые стороны и нижние доступные ракурсы.
5. Не делайте резких движений и не закрывайте камеру пальцами.
6. Остановите сканирование и нажмите **Экспорт ZIP**.

Для предмета 20–100 см обычно разумно держать телефон в 0,4–1,5 м от поверхности.

## Сервер гладкой реконструкции

См. [`reconstruction-server/README.md`](reconstruction-server/README.md). Сервер принимает `scan_points.ply` и возвращает ZIP с `GLB`, `OBJ`, `STL` и `PLY`, построенными методом Poisson reconstruction.

## Архитектура

```text
Camera + ARCore Raw Depth
        ↓
Depth/confidence filtering
        ↓
Camera intrinsics unprojection
        ↓
Camera pose → scan-anchor local coordinates
        ↓
Voxel accumulation + RGB averaging
        ↓
OpenGL point-cloud preview
        ↓
PLY / OBJ / STL / JSON export
        ↓ optional
Open3D Poisson reconstruction → GLB/OBJ/STL/PLY
```

## Проверка

Подробный перечень выполненных автоматических проверок и ограничений среды находится в [`VALIDATION.md`](VALIDATION.md). Полный APK в текущей среде не собирался; для этого подготовлен GitHub Actions workflow.

## Версии

- Android Gradle Plugin: 8.13.2
- Gradle: 8.13
- compileSdk / targetSdk: 36
- Java: 17
- ARCore SDK: 1.54.0
- applicationId: `com.depthscanner3d.app`
