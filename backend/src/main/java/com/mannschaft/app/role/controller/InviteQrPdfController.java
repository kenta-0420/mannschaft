package com.mannschaft.app.role.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.role.dto.InviteQrPdfRequest;
import com.mannschaft.app.role.service.InviteQrPdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F01.8 招待QRコードPDFコントローラー。
 * チームの招待トークンQRコードをPDFで提供する。
 *
 * <p>招待トークンは DB にハッシュしか残らないため、PDF は<b>発行直後に画面が保持する平文</b>を POST して作る
 * （CMP-260826-1457）。tokenId だけから平文は引けないので、旧 {@code GET .../{tokenId}/pdf} は撤去した。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/invite-tokens/pdf")
@Tag(name = "招待QRコードPDF", description = "F01.8 チーム招待QRコードPDF印刷")
@RequiredArgsConstructor
public class InviteQrPdfController {

    private final InviteQrPdfService inviteQrPdfService;

    @Operation(summary = "招待QRコードPDFダウンロード",
            description = "発行時に受け取った平文トークンから、チームの招待QRコードをMannschaftブランドのPDFで取得する")
    @PostMapping
    public ResponseEntity<byte[]> downloadQrPdf(
            @PathVariable Long teamId,
            @Valid @RequestBody InviteQrPdfRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return inviteQrPdfService.generateInviteQrPdf(teamId, request.token(), userId);
    }
}
