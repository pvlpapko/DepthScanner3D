# Changelog

## 1.0.1 — 2026-08-01

- Enabled AndroidX support required by `com.google.ar:core:1.54.0`.
- Explicitly disabled Jetifier because the project and all direct dependencies are already AndroidX-compatible.
- Fixed GitHub Actions failure in `:app:checkDebugAarMetadata`.
- Increased Android `versionCode` to 2 and `versionName` to 1.0.1.

## 1.0.0 — 2026-08-01

- Первый Android MVP 3D-сканера на ARCore Raw Depth.
- Цветное метрическое облако точек с привязкой к AR-якорю.
- Voxel-фильтрация, ограничение глубины и фильтр уверенности.
- Предпросмотр накопленного облака поверх камеры.
- ZIP-экспорт PLY, OBJ point cloud, OBJ/STL voxel mesh и JSON-метаданных.
- GitHub Actions для сборки APK/AAB.
- Дополнительный FastAPI/Open3D-сервер для Poisson-реконструкции GLB/OBJ/STL/PLY.
