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
 * チームダイレクトメールコントローラー。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/direct-mails")
@Tag(name = "チームダイレクトメール", description = "F09.6 チームDM配信")
@RequiredArgsConstructor
public class TeamDirectMailController {

    private final DirectMailService directMailService;


    /**
     * メールを作成する（下書き保存）。
     */
    @PostMapping
    @Operation(summary = "ダイレクトメール作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<DirectMailResponse>> createMail(
            @PathVariable Long teamId, @Valid @RequestBody CreateDirectMailRequest request) {
        DirectMailResponse response = directMailService.createMail("TEAM", teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * メール一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "ダイレクトメール一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<DirectMailResponse>> listMails(
            @PathVariable Long teamId, @PageableDefault(size = 20) Pageable pageable) {
        PagedResponse<DirectMailResponse> response = directMailService.listMails("TEAM", teamId, pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * メール詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "ダイレクトメール詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<DirectMailResponse>> getMail(
            @PathVariable Long teamId, @PathVariable Long id) {
        DirectMailResponse response = directMailService.getMail("TEAM", teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メールを編集する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "ダイレクトメール編集")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<DirectMailResponse>> updateMail(
            @PathVariable Long teamId, @PathVariable Long id,
            @Valid @RequestBody UpdateDirectMailRequest request) {
        DirectMailResponse response = directMailService.updateMail("TEAM", teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メールを即時送信する。
     */
    @PostMapping("/{id}/send")
    @Operation(summary = "ダイレクトメール即時送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "送信開始")
    public ResponseEntity<ApiResponse<DirectMailResponse>> sendMail(
            @PathVariable Long teamId, @PathVariable Long id) {
        DirectMailResponse response = directMailService.sendMail("TEAM", teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メールを予約送信する。
     */
    @PostMapping("/{id}/schedule")
    @Operation(summary = "ダイレクトメール予約送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "予約成功")
    public ResponseEntity<ApiResponse<DirectMailResponse>> scheduleMail(
            @PathVariable Long teamId, @PathVariable Long id,
            @Valid @RequestBody ScheduleMailRequest request) {
        DirectMailResponse response = directMailService.scheduleMail("TEAM", teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 送信をキャンセルする。
     */
    @PostMapping("/{id}/cancel")
    @Operation(summary = "ダイレクトメール送信キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<DirectMailResponse>> cancelMail(
            @PathVariable Long teamId, @PathVariable Long id) {
        DirectMailResponse response = directMailService.cancelMail("TEAM", teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 受信者一覧を取得する。
     */
    @GetMapping("/{id}/recipients")
    @Operation(summary = "受信者一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<DirectMailRecipientResponse>> listRecipients(
            @PathVariable Long teamId, @PathVariable Long id,
            @PageableDefault(size = 50) Pageable pageable) {
        PagedResponse<DirectMailRecipientResponse> response =
                directMailService.listRecipients("TEAM", teamId, id, pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * 送信統計を取得する。
     */
    @GetMapping("/{id}/stats")
    @Operation(summary = "送信統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<DirectMailStatsResponse>> getStats(
            @PathVariable Long teamId, @PathVariable Long id) {
        DirectMailStatsResponse response = directMailService.getStats("TEAM", teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メールプレビューを生成する。
     */
    @PostMapping("/preview")
    @Operation(summary = "メールプレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "プレビュー生成成功")
    public ResponseEntity<ApiResponse<PreviewMailResponse>> preview(
            @PathVariable Long teamId, @Valid @RequestBody PreviewMailRequest request) {
        PreviewMailResponse response = directMailService.preview(request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 配信対象数を見積もる。
     */
    @PostMapping("/estimate-recipients")
    @Operation(summary = "配信対象数見積")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "見積成功")
    public ResponseEntity<ApiResponse<EstimateRecipientsResponse>> estimateRecipients(
            @PathVariable Long teamId, @Valid @RequestBody EstimateRecipientsRequest request) {
        EstimateRecipientsResponse response = directMailService.estimateRecipients("TEAM", teamId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
