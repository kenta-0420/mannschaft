package com.mannschaft.app.membership.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.membership.dto.CardStatusResponse;
import com.mannschaft.app.membership.dto.CheckinHistoryResponse;
import com.mannschaft.app.membership.dto.MemberCardDetailResponse;
import com.mannschaft.app.membership.dto.MemberCardListResponse;
import com.mannschaft.app.membership.dto.MemberCardResponse;
import com.mannschaft.app.membership.dto.QrTokenResponse;
import com.mannschaft.app.membership.dto.RegenerateResponse;
import com.mannschaft.app.membership.dto.SelfCheckinRequest;
import com.mannschaft.app.membership.dto.SelfCheckinResponse;
import com.mannschaft.app.membership.dto.SuspendRequest;
import com.mannschaft.app.membership.dto.VerifyRequest;
import com.mannschaft.app.membership.dto.VerifyResponse;
import com.mannschaft.app.membership.service.MemberCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 会員証コントローラー。会員証のCRUD・QR認証・チェックインAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/member-cards")
@Tag(name = "QR会員証", description = "F02.1 QR会員証")
@RequiredArgsConstructor
public class MemberCardController {

    private final MemberCardService memberCardService;


    /**
     * 自分の会員証一覧を取得する。
     */
    @GetMapping("/my")
    @Operation(summary = "自分の会員証一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<MemberCardResponse>>> getMyCards() {
        return ResponseEntity.ok(memberCardService.getMyCards(SecurityUtils.getCurrentUserId()));
    }

    /**
     * 会員証詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "会員証詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<MemberCardDetailResponse>> getCardDetail(@PathVariable Long id) {
        return ResponseEntity.ok(memberCardService.getCardDetail(id, SecurityUtils.getCurrentUserId()));
    }

    /**
     * QRコード表示用トークンを取得する。
     */
    @GetMapping("/{id}/qr")
    @Operation(summary = "QRコード表示用トークン取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<QrTokenResponse>> getQrToken(@PathVariable Long id) {
        return ResponseEntity.ok(memberCardService.getQrToken(id, SecurityUtils.getCurrentUserId()));
    }

    /**
     * QRコードを再生成する。
     */
    @PostMapping("/{id}/regenerate")
    @Operation(summary = "QRコード再生成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再生成成功")
    public ResponseEntity<ApiResponse<RegenerateResponse>> regenerateQr(@PathVariable Long id) {
        return ResponseEntity.ok(memberCardService.regenerateQr(id, SecurityUtils.getCurrentUserId()));
    }

    /**
     * QRスキャン認証（スタッフスキャン型チェックイン）。
     */
    @PostMapping("/verify")
    @Operation(summary = "QRスキャン認証")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "認証結果")
    public ResponseEntity<ApiResponse<VerifyResponse>> verify(
            @Valid @RequestBody VerifyRequest request) {
        return ResponseEntity.ok(memberCardService.verify(request, SecurityUtils.getCurrentUserId()));
    }

    /**
     * セルフチェックインを実行する。
     */
    @PostMapping("/self-checkin")
    @Operation(summary = "セルフチェックイン")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "チェックイン結果")
    public ResponseEntity<ApiResponse<SelfCheckinResponse>> selfCheckin(
            @Valid @RequestBody SelfCheckinRequest request) {
        return ResponseEntity.ok(memberCardService.selfCheckin(request, SecurityUtils.getCurrentUserId()));
    }

    /**
     * 会員証を一時停止する。
     */
    @PatchMapping("/{id}/suspend")
    @Operation(summary = "会員証一時停止")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "一時停止成功")
    public ResponseEntity<ApiResponse<CardStatusResponse>> suspend(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SuspendRequest request) {
        return ResponseEntity.ok(memberCardService.suspend(id));
    }

    /**
     * 一時停止を解除する。
     */
    @PatchMapping("/{id}/reactivate")
    @Operation(summary = "一時停止解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再有効化成功")
    public ResponseEntity<ApiResponse<CardStatusResponse>> reactivate(@PathVariable Long id) {
        return ResponseEntity.ok(memberCardService.reactivate(id));
    }

    /**
     * チェックイン履歴を取得する。
     */
    @GetMapping("/{id}/checkins")
    @Operation(summary = "チェックイン履歴")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CheckinHistoryResponse>>> getCheckinHistory(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(memberCardService.getCheckinHistory(id, SecurityUtils.getCurrentUserId(), from, to));
    }
}
