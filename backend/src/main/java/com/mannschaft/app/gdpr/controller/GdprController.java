package com.mannschaft.app.gdpr.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.service.DataExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * F12.3 GDPRコントローラー。
 * データエクスポートのリクエスト・ステータス取得を提供する。
 */
@RestController
@RequestMapping("/api/v1/gdpr")
@RequiredArgsConstructor
public class GdprController {

    private final DataExportService dataExportService;

    /**
     * データエクスポートをリクエストする。
     *
     * @return 201 Accepted
     */
    @PostMapping("/data-export")
    public ResponseEntity<Map<String, Object>> requestExport() {
        Long userId = SecurityUtils.getCurrentUserId();
        DataExportEntity entity = dataExportService.requestExport(userId, null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", entity.getId() != null ? entity.getId() : 0L,
                        "status", entity.getStatus()));
    }

    /**
     * データエクスポートのステータスを取得する。
     *
     * @return 200 OK
     */
    @GetMapping("/data-export/status")
    public ResponseEntity<Map<String, Object>> getExportStatus() {
        Long userId = SecurityUtils.getCurrentUserId();
        DataExportEntity entity = dataExportService.getExportStatus(userId);
        return ResponseEntity.ok(Map.of(
                "id", entity.getId() != null ? entity.getId() : 0L,
                "status", entity.getStatus(),
                "progressPercent", entity.getProgressPercent()
        ));
    }
}
