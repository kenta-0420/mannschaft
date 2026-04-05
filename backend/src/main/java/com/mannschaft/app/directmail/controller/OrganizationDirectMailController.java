package com.mannschaft.app.directmail.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.directmail.dto.CreateDirectMailRequest;
import com.mannschaft.app.directmail.dto.DirectMailRecipientResponse;
import com.mannschaft.app.directmail.dto.DirectMailResponse;
import com.mannschaft.app.directmail.dto.DirectMailStatsResponse;
import com.mannschaft.app.directmail.dto.EstimateRecipientsRequest;
import com.mannschaft.app.directmail.dto.EstimateRecipientsResponse;
import com.mannschaft.app.directmail.dto.PreviewMailRequest;
import com.mannschaft.app.directmail.dto.PreviewMailResponse;
import com.mannschaft.app.directmail.dto.ScheduleMailRequest;
import com.mannschaft.app.directmail.dto.UpdateDirectMailRequest;
import com.mannschaft.app.directmail.service.DirectMailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織ダイレクトメールコントローラー。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/direct-mails")
@Tag(name = "組織ダイレクトメール", description = "F09.6 組織DM配信")
@RequiredArgsConstructor
public class OrganizationDirectMailController {

    private final DirectMailService directMailService;


    /**
     * メールを作成する（下書き保存）。
     */
    @PostMapping
    @Operation(summary = "組織ダイレクトメール作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<DirectMailResponse>> createMail(
            @PathVariable Long orgId, @Valid @RequestBody CreateDirectMailRequest request) {
        DirectMailResponse response = directMailService.createMail("ORGANIZATION", orgId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * メール一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "組織ダイレクトメール一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<DirectMailResponse>> listMails(
            @PathVariable Long orgId, @PageableDefault(size = 20) Pageable pageable) {
        PagedResponse<DirectMailResponse> response = directMailService.listMails("ORGANIZATION", orgId, pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * メール詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "組織ダイレクトメール詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<DirectMailResponse>> getMail(
            @PathVariable Long orgId, @PathVariable Long id) {
        DirectMailResponse response = directMailService.getMail("ORGANIZATION", orgId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メールを編集する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "組織ダイレクトメール編集")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<DirectMailResponse>> updateMail(
            @PathVariable Long orgId, @PathVariable Long id,
            @Valid @RequestBody UpdateDirectMailRequest request) {
        DirectMailResponse response = directMailService.updateMail("ORGANIZATION", orgId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メールを即時送信する。
     */
    @PostMapping("/{id}/send")
    @Operation(summary = "組織ダイレクトメール即時送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "送信開始")
    public ResponseEntity<ApiResponse<DirectMailResponse>> sendMail(
            @PathVariable Long orgId, @PathVariable Long id) {
        DirectMailResponse response = directMailService.sendMail("ORGANIZATION", orgId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メールを予約送信する。
     */
    @PostMapping("/{id}/schedule")
    @Operation(summary = "組織ダイレクトメール予約送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "予約成功")
    public ResponseEntity<ApiResponse<DirectMailResponse>> scheduleMail(
            @PathVariable Long orgId, @PathVariable Long id,
            @Valid @RequestBody ScheduleMailRequest request) {
        DirectMailResponse response = directMailService.scheduleMail("ORGANIZATION", orgId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 送信をキャンセルする。
     */
    @PostMapping("/{id}/cancel")
    @Operation(summary = "組織ダイレクトメール送信キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<DirectMailResponse>> cancelMail(
            @PathVariable Long orgId, @PathVariable Long id) {
        DirectMailResponse response = directMailService.cancelMail("ORGANIZATION", orgId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 受信者一覧を取得する。
     */
    @GetMapping("/{id}/recipients")
    @Operation(summary = "組織DM受信者一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<DirectMailRecipientResponse>> listRecipients(
            @PathVariable Long orgId, @PathVariable Long id,
            @PageableDefault(size = 50) Pageable pageable) {
        PagedResponse<DirectMailRecipientResponse> response =
                directMailService.listRecipients("ORGANIZATION", orgId, id, pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * 送信統計を取得する。
     */
    @GetMapping("/{id}/stats")
    @Operation(summary = "組織DM送信統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<DirectMailStatsResponse>> getStats(
            @PathVariable Long orgId, @PathVariable Long id) {
        DirectMailStatsResponse response = directMailService.getStats("ORGANIZATION", orgId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メールプレビューを生成する。
     */
    @PostMapping("/preview")
    @Operation(summary = "組織メールプレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "プレビュー生成成功")
    public ResponseEntity<ApiResponse<PreviewMailResponse>> preview(
            @PathVariable Long orgId, @Valid @RequestBody PreviewMailRequest request) {
        PreviewMailResponse response = directMailService.preview(request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 配信対象数を見積もる。
     */
    @PostMapping("/estimate-recipients")
    @Operation(summary = "組織配信対象数見積")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "見積成功")
    public ResponseEntity<ApiResponse<EstimateRecipientsResponse>> estimateRecipients(
            @PathVariable Long orgId, @Valid @RequestBody EstimateRecipientsRequest request) {
        EstimateRecipientsResponse response = directMailService.estimateRecipients("ORGANIZATION", orgId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
