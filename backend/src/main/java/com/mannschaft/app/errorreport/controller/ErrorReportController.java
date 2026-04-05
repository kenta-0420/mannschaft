package com.mannschaft.app.errorreport.controller;

import com.mannschaft.app.errorreport.dto.ActiveIncidentResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportRequest;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * フロントエンドエラーレポート受信コントローラー。
 * 認証不要エンドポイント。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "エラーレポート", description = "F12.5 フロントエンドエラー追跡API")
@RequiredArgsConstructor
public class ErrorReportController {

    private final ErrorReportService errorReportService;

    /**
     * フロントエンドからのエラーレポートを受信する。
     * 同一エラーハッシュの場合は既存レコードに集約される。
     */
    @PostMapping("/error-reports")
    @Operation(summary = "エラーレポート送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "新規作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "既存レポートに集約")
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody ErrorReportRequest request,
            HttpServletRequest httpRequest) {
        String ipAddress = httpRequest.getRemoteAddr();
        ErrorReportEntity entity = errorReportService.createOrAggregate(request, ipAddress);
        Map<String, Object> body = Map.of(
                "id", entity.getId(),
                "status", entity.getStatus().name());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * 現在発生中のアクティブインシデント一覧を取得する。
     */
    @GetMapping("/active-incidents")
    @Operation(summary = "アクティブインシデント一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ActiveIncidentResponse> getActiveIncidents() {
        ActiveIncidentResponse response = errorReportService.getActiveIncidents();
        return ResponseEntity.ok(response);
    }
}
