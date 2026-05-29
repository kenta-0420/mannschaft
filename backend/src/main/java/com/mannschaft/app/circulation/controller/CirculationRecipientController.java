package com.mannschaft.app.circulation.controller;

import com.mannschaft.app.circulation.dto.AddRecipientsRequest;
import com.mannschaft.app.circulation.dto.AdminSkipRecipientRequest;
import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.circulation.service.CirculationStampService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 回覧受信者コントローラー。回覧文書の受信者管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/circulations/{documentId}/recipients")
@Tag(name = "回覧受信者", description = "F05.2 回覧文書の受信者管理")
@RequiredArgsConstructor
public class CirculationRecipientController {

    private static final String SCOPE_TYPE = "TEAM";

    private final CirculationService circulationService;
    private final CirculationStampService stampService;

    /**
     * 受信者一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "受信者一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<RecipientResponse>>> listRecipients(
            @PathVariable Long documentId) {
        List<RecipientResponse> recipients = circulationService.listRecipients(documentId);
        return ResponseEntity.ok(ApiResponse.of(recipients));
    }

    /**
     * 受信者を追加する。
     */
    @PostMapping
    @Operation(summary = "受信者追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<List<RecipientResponse>>> addRecipients(
            @PathVariable Long documentId,
            @Valid @RequestBody AddRecipientsRequest request) {
        // ドキュメントからscopeType/scopeIdを解決（現時点ではデフォルト値を使用）
        List<RecipientResponse> recipients = circulationService.addRecipients(
                SCOPE_TYPE, 0L, documentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(recipients));
    }

    /**
     * 受信者を削除する。
     */
    @DeleteMapping("/{recipientId}")
    @Operation(summary = "受信者削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> removeRecipient(
            @PathVariable Long documentId,
            @PathVariable Long recipientId) {
        // ドキュメントからscopeType/scopeIdを解決（現時点ではデフォルト値を使用）
        circulationService.removeRecipient(SCOPE_TYPE, 0L, documentId, recipientId);
        return ResponseEntity.noContent().build();
    }

    /**
     * ADMIN による受信者強制スキップ。
     *
     * <p>F05.2 Phase 11 第三陣 3-B: 退職者・休職者などへの対応として、ADMIN が
     * 特定受信者を SKIPPED 状態に強制遷移させる。{@code reason} は必須。</p>
     *
     * <p><b>認可（2026-05-29 fixup）:</b> {@code @PreAuthorize("hasRole('ADMIN')")} は
     * {@code @EnableMethodSecurity} 未有効ゆえ実機で効かない（将来宣言）。真の per-scope 認可は
     * {@code CirculationStampService.adminSkipRecipient} の処理本体前で
     * {@code AccessControlService} により実施する（対象文書スコープの ADMIN/DEPUTY_ADMIN、
     * または SYSTEM_ADMIN）。</p>
     */
    @PostMapping("/{userId}/skip")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "ADMIN による受信者強制スキップ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "スキップ成功")
    public ResponseEntity<ApiResponse<RecipientResponse>> adminSkipRecipient(
            @PathVariable Long documentId,
            @PathVariable("userId") Long targetUserId,
            @Valid @RequestBody AdminSkipRecipientRequest request) {
        RecipientResponse response = stampService.adminSkipRecipient(documentId, targetUserId,
                SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
