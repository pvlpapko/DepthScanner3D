from __future__ import annotations

import shutil
import tempfile
import zipfile
from pathlib import Path

import numpy as np
import open3d as o3d
import trimesh
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.background import BackgroundTasks
from fastapi.responses import FileResponse

app = FastAPI(
    title="Depth Scanner 3D Reconstruction Server",
    version="1.0.0",
    description="Converts exported PLY point clouds into smoothed OBJ/STL/GLB meshes.",
)

MAX_UPLOAD_BYTES = 300 * 1024 * 1024


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "depth-scanner-reconstruction"}


@app.post("/api/v1/reconstruct")
async def reconstruct(
    background_tasks: BackgroundTasks,
    point_cloud: UploadFile = File(...),
    voxel_size_mm: float = Form(5.0),
    poisson_depth: int = Form(9),
    target_triangles: int = Form(250_000),
) -> FileResponse:
    if not 1.0 <= voxel_size_mm <= 50.0:
        raise HTTPException(400, "voxel_size_mm must be between 1 and 50")
    if not 6 <= poisson_depth <= 12:
        raise HTTPException(400, "poisson_depth must be between 6 and 12")
    if not 10_000 <= target_triangles <= 2_000_000:
        raise HTTPException(400, "target_triangles must be between 10000 and 2000000")

    work_dir = Path(tempfile.mkdtemp(prefix="depthscanner_"))
    source_path = work_dir / "scan_points.ply"
    archive_path = work_dir / "reconstructed_model.zip"

    try:
        written = 0
        with source_path.open("wb") as destination:
            while chunk := await point_cloud.read(1024 * 1024):
                written += len(chunk)
                if written > MAX_UPLOAD_BYTES:
                    raise HTTPException(413, "Point cloud is larger than 300 MB")
                destination.write(chunk)
        await point_cloud.close()

        result = _reconstruct_mesh(
            source_path=source_path,
            output_dir=work_dir,
            voxel_size_m=voxel_size_mm / 1000.0,
            poisson_depth=poisson_depth,
            target_triangles=target_triangles,
        )
        _write_archive(archive_path, result)
    except HTTPException:
        shutil.rmtree(work_dir, ignore_errors=True)
        raise
    except Exception as exc:
        shutil.rmtree(work_dir, ignore_errors=True)
        raise HTTPException(422, f"Reconstruction failed: {exc}") from exc

    background_tasks.add_task(shutil.rmtree, work_dir, True)
    return FileResponse(
        archive_path,
        media_type="application/zip",
        filename="DepthScanner3D_reconstructed.zip",
        background=background_tasks,
    )


def _reconstruct_mesh(
    source_path: Path,
    output_dir: Path,
    voxel_size_m: float,
    poisson_depth: int,
    target_triangles: int,
) -> dict[str, Path | int | float]:
    cloud = o3d.io.read_point_cloud(str(source_path))
    if cloud.is_empty() or len(cloud.points) < 500:
        raise ValueError("At least 500 valid points are required")

    cloud = cloud.voxel_down_sample(voxel_size_m)
    if len(cloud.points) < 500:
        raise ValueError("Too few points remain after voxel filtering")

    if len(cloud.points) >= 2_000:
        cloud, _ = cloud.remove_statistical_outlier(nb_neighbors=24, std_ratio=2.2)

    normal_radius = max(voxel_size_m * 5.0, 0.015)
    cloud.estimate_normals(
        search_param=o3d.geometry.KDTreeSearchParamHybrid(
            radius=normal_radius,
            max_nn=60,
        )
    )
    cloud.normalize_normals()
    try:
        cloud.orient_normals_consistent_tangent_plane(40)
    except RuntimeError:
        cloud.orient_normals_towards_camera_location(np.zeros(3))

    mesh, densities = o3d.geometry.TriangleMesh.create_from_point_cloud_poisson(
        cloud,
        depth=poisson_depth,
        width=0,
        scale=1.08,
        linear_fit=False,
    )

    density_array = np.asarray(densities)
    if density_array.size:
        threshold = float(np.quantile(density_array, 0.025))
        mesh.remove_vertices_by_mask(density_array < threshold)

    crop_box = cloud.get_axis_aligned_bounding_box()
    crop_box = crop_box.scale(1.03, crop_box.get_center())
    mesh = mesh.crop(crop_box)
    mesh.remove_degenerate_triangles()
    mesh.remove_duplicated_triangles()
    mesh.remove_duplicated_vertices()
    mesh.remove_non_manifold_edges()
    mesh.remove_unreferenced_vertices()

    if len(mesh.triangles) > target_triangles:
        mesh = mesh.simplify_quadric_decimation(target_number_of_triangles=target_triangles)
        mesh.remove_degenerate_triangles()
        mesh.remove_duplicated_triangles()
        mesh.remove_unreferenced_vertices()

    mesh.compute_vertex_normals()
    if len(mesh.triangles) < 100:
        raise ValueError("Generated mesh is too small")

    obj_path = output_dir / "reconstructed_mesh.obj"
    stl_path = output_dir / "reconstructed_mesh.stl"
    ply_path = output_dir / "reconstructed_mesh.ply"
    glb_path = output_dir / "reconstructed_mesh.glb"

    if not o3d.io.write_triangle_mesh(str(obj_path), mesh, write_vertex_normals=True):
        raise RuntimeError("Open3D could not write OBJ")
    if not o3d.io.write_triangle_mesh(str(stl_path), mesh, write_vertex_normals=True):
        raise RuntimeError("Open3D could not write STL")
    if not o3d.io.write_triangle_mesh(str(ply_path), mesh, write_vertex_normals=True):
        raise RuntimeError("Open3D could not write PLY")

    tri_mesh = trimesh.load_mesh(obj_path, process=False)
    tri_mesh.export(glb_path, file_type="glb")

    info_path = output_dir / "reconstruction_info.txt"
    info_path.write_text(
        "Depth Scanner 3D reconstruction\n"
        f"Input filtered points: {len(cloud.points)}\n"
        f"Output vertices: {len(mesh.vertices)}\n"
        f"Output triangles: {len(mesh.triangles)}\n"
        f"Voxel size: {voxel_size_m * 1000.0:.2f} mm\n"
        f"Poisson depth: {poisson_depth}\n",
        encoding="utf-8",
    )

    return {
        "obj": obj_path,
        "stl": stl_path,
        "ply": ply_path,
        "glb": glb_path,
        "info": info_path,
        "points": len(cloud.points),
        "vertices": len(mesh.vertices),
        "triangles": len(mesh.triangles),
        "voxel_size_m": voxel_size_m,
    }


def _write_archive(archive_path: Path, result: dict[str, Path | int | float]) -> None:
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for key in ("obj", "stl", "ply", "glb", "info"):
            path = result[key]
            if isinstance(path, Path):
                archive.write(path, arcname=path.name)
