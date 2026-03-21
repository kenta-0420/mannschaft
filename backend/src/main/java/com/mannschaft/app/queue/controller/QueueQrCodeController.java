package com.mannschaft.app.queue.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.queue.dto.CreateQrCodeRequest;
import com.mannschaft.app.queue.dto.QrCodeResponse;
import com.mannschaft.app.queue.service.QueueQrCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 順番待ちQRコードコントローラー。QRコードの発行・取得・無効化APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/queue/qr-codes")
@Tag(name = "順番待ちQRコード管理", description = "F03.7 順番待ちQRコードの発行・管理")
@RequiredArgsConstructor
public class QueueQrCodeController {

    private final QueueQrCodeService qrCodeService;

    /**
     * QRコードを発行する。
     */
    @PostMapping
    @Operation(summary = "QRコード発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "発行成功")
    public ResponseEntity<ApiResponse<QrCodeResponse>> createQrCode(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateQrCodeRequest request) {
        QrCodeResponse qrCode = qrCodeService.createQrCode(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(qrCode));
    }

    /**
     * QRトークンでQRコード情報を取得する。
     */
    @GetMapping("/token/{qrToken}")
    @Operation(summary = "QRトークン検証")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<QrCodeResponse>> getByToken(
            @PathVariable Long teamId,
            @PathVariable String qrToken) {
        QrCodeResponse qrCode = qrCodeService.getByToken(qrToken);
        return ResponseEntity.ok(ApiResponse.of(qrCode));
    }

    /**
     * QRコード一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "QRコード一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<QrCodeResponse>>> listQrCodes(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long counterId) {
        List<QrCodeResponse> qrCodes = qrCodeService.listQrCodes(categoryId, counterId);
        return ResponseEntity.ok(ApiResponse.of(qrCodes));
    }

    /**
     * QRコードを無効化する。
     */
    @DeleteMapping("/{qrCodeId}")
    @Operation(summary = "QRコード無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "無効化成功")
    public ResponseEntity<Void> deactivateQrCode(
            @PathVariable Long teamId,
            @PathVariable Long qrCodeId) {
        qrCodeService.deactivateQrCode(qrCodeId);
        return ResponseEntity.noContent().build();
    }
}
