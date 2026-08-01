package com.depthscanner3d.app.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.depthscanner3d.app.scan.PointSample;
import com.depthscanner3d.app.scan.ScanSnapshot;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ScanArchiveExporterTest {
    @Test
    public void exportCreatesExpectedFilesAndMetadata() throws Exception {
        List<PointSample> points = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                points.add(new PointSample(
                        x * 0.01f,
                        y * 0.01f,
                        0.0f,
                        80 + x,
                        100 + y,
                        200
                ));
            }
        }
        ScanSnapshot snapshot = new ScanSnapshot(
                points,
                12,
                1_700_000_000_000L,
                0.0125f,
                0.55f,
                0.18f,
                5.0f
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ScanArchiveExporter.ExportResult result = ScanArchiveExporter.export(output, snapshot);

        assertEquals(400, result.pointCount());
        assertTrue(result.meshTriangleCount() > 0);

        Map<String, byte[]> files = unzip(output.toByteArray());
        assertTrue(files.containsKey("scan_points.ply"));
        assertTrue(files.containsKey("scan_points.obj"));
        assertTrue(files.containsKey("scan_mesh.obj"));
        assertTrue(files.containsKey("scan_mesh.stl"));
        assertTrue(files.containsKey("scan_metadata.json"));
        assertTrue(files.containsKey("README_RU.txt"));

        String metadata = new String(files.get("scan_metadata.json"), StandardCharsets.UTF_8);
        assertTrue(metadata.contains("\"point_count\": 400"));
        assertTrue(metadata.contains("\"units\": \"meters\""));
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    if (read > 0) {
                        content.write(buffer, 0, read);
                    }
                }
                files.put(entry.getName(), content.toByteArray());
                zip.closeEntry();
            }
        }
        return files;
    }
}
