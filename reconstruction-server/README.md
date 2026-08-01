# Reconstruction server

Сервер принимает `scan_points.ply` из Android-приложения и строит сглаженную поверхность методом Poisson reconstruction.

## Запуск

```bash
docker compose up --build
```

Проверка:

```bash
curl http://localhost:8080/health
```

Реконструкция:

```bash
curl -X POST http://localhost:8080/api/v1/reconstruct \
  -F "point_cloud=@scan_points.ply" \
  -F "voxel_size_mm=5" \
  -F "poisson_depth=9" \
  -F "target_triangles=250000" \
  --output reconstructed_model.zip
```

Результат содержит `GLB`, `OBJ`, `STL`, `PLY` и сведения о сетке. Для Poisson-реконструкции желательно 4–8 ГБ оперативной памяти.
