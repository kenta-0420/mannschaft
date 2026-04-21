package com.mannschaft.app.role.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.role.service.InviteQrPdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F01.8 招待QRコードPDFコントローラー。
 * チームの招待トークンQRコードをPDFで提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/invite-tokens/{tokenId}/pdf")
@Tag(name = "招待QRコードPDF", description = "F01.8 チーム招待QRコードPDF印刷")
@RequiredArgsConstructor
public class InviteQrPdfController {

    private final InviteQrPdfService inviteQrPdfService;

    @Operation(summary = "招待QRコードPDFダウンロード", description = "チームの招待トークンのQRコードをMannschaftブランドのPDFで取得する")
    @GetMapping
    public ResponseEntity<byte[]> downloadQrPdf(
            @PathVariable Long teamId,
            @PathVariable Long tokenId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return inviteQrPdfService.generateInviteQrPdf(teamId, tokenId, userId);
    }
}
