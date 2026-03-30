package com.mannschaft.app.incident.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.incident.service.IncidentService;
import com.mannschaft.app.incident.service.IncidentService.AssignIncidentRequest;
import com.mannschaft.app.incident.service.IncidentService.IncidentResponse;
import com.mannschaft.app.incident.service.IncidentService.IncidentSummaryResponse;
import com.mannschaft.app.incident.service.IncidentService.ReportIncidentRequest;
import com.mannschaft.app.incident.service.IncidentService.UpdateIncidentRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * インシデント管理コントローラー。
 * インシデントの報告・取得・一覧・更新・ステータス変更・アサイン・削除を提供する。
 */
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;

    // ========================================
    // 内部DTO定義（ステータス変更リクエスト）
    // ========================================

    /** ステータス変更リクエスト */
    public record ChangeStatusRequest(String status) {}

    // ========================================
    // エンドポイント
    // ========================================

    /**
     * インシデントを報告する。
     * 認可: MEMBER 以上
     */
    @PostMapping
    public ResponseEntity<ApiResponse<IncidentResponse>> reportIncident(
            @Validated @RequestBody ReportIncidentRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        IncidentResponse response = incidentService.reportIncident(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * インシデントを1件取得する。
     * 認可: MEMBER 以上
     */
    @GetMapping("/{id}")
    public ApiResponse<IncidentResponse> getIncident(@PathVariable Long id) {
        IncidentResponse response = incidentService.getIncident(id);
        return ApiResponse.of(response);
    }

    /**
     * インシデント一覧をフィルタ検索する（ページング対応）。
     * 認可: MEMBER 以上
     */
    @GetMapping
    public PagedResponse<IncidentSummaryResponse> listIncidents(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<IncidentSummaryResponse> result =
                incidentService.listIncidents(scopeType, scopeId, status, pageable);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(),
                result.getNumber(),
                result.getSize(),
                result.getTotalPages()
        );
        return PagedResponse.of(result.getContent(), meta);
    }

    /**
     * インシデントを更新する。
     * 認可: ADMIN または報告者本人
     */
    @PutMapping("/{id}")
    public ApiResponse<IncidentResponse> updateIncident(
            @PathVariable Long id,
            @Validated @RequestBody UpdateIncidentRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        IncidentResponse response = incidentService.updateIncident(id, userId, request);
        return ApiResponse.of(response);
    }

    /**
     * インシデントのステータスを変更する。
     * 認可: ADMIN または担当者
     */
    @PatchMapping("/{id}/status")
    public ApiResponse<IncidentResponse> changeStatus(
            @PathVariable Long id,
            @Validated @RequestBody ChangeStatusRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        IncidentResponse response = incidentService.changeStatus(id, userId, request.status());
        return ApiResponse.of(response);
    }

    /**
     * インシデントに担当者をアサインする。
     * 認可: ADMIN 相当
     */
    @PostMapping("/{id}/assign")
    public ApiResponse<IncidentResponse> assignIncident(
            @PathVariable Long id,
            @Validated @RequestBody AssignIncidentRequest request) {
        IncidentResponse response =
                incidentService.assignIncident(id, request.assigneeId(), request.assigneeType());
        return ApiResponse.of(response);
    }

    /**
     * インシデントを論理削除する。
     * 認可: ADMIN 相当
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteIncident(@PathVariable Long id) {
        incidentService.deleteIncident(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * インシデントのコメント一覧を取得する。
     * TODO: コメント機能実装後に本実装に差し替えること
     */
    @GetMapping("/{id}/comments")
    public ResponseEntity<ApiResponse<List<Object>>> listComments(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(List.of()));
    }
}
