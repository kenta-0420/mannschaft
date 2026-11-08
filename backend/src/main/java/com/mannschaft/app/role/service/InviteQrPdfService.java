package com.mannschaft.app.role.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfFileNameBuilder;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.pdf.PdfResponseHelper;
import com.mannschaft.app.role.dto.InviteTokenResponse;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * F01.8 招待QRコードPDF生成サービス。
 * 招待トークンのQRコードをMannschaftブランドのPDFとして生成する。
 */
@Service
@RequiredArgsConstructor
public class InviteQrPdfService {

    private final InviteService inviteService;
    private final TeamService teamService;
    private final AccessControlService accessControlService;
    private final PdfGeneratorService pdfGeneratorService;

    /**
     * 招待QRコードPDFを生成して返す。
     *
     * @param teamId        チームID
     * @param plaintextToken 発行時に受け取った平文の招待トークン
     * @param requestUserId リクエストユーザーID
     * @return PDFのResponseEntity
     */
    public ResponseEntity<byte[]> generateInviteQrPdf(Long teamId, String plaintextToken, Long requestUserId) {
        // 認可チェック: ADMIN or DEPUTY_ADMIN（COMMON_002 で 403）
        accessControlService.checkAdminOrAbove(requestUserId, teamId, "TEAM");

        // トークン取得（IDORチェック: 平文から引いた招待が teamId に属することを確認。
        // 他チームの有効な平文も不明な平文も、同じ 404 ROLE_002 で拒否する）
        InviteTokenResponse token = inviteService.findByPlaintextAndTeamId(plaintextToken, teamId);

        // チーム情報取得
        // 他ドメイン（team）は Service 経由で取得する（Entity / Repository を直接参照しない）
        TeamService.TeamSummary team = teamService.findTeamSummary(teamId)
            .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_001));

        // QRコード生成（350px, Base64）
        String qrBase64 = inviteService.generateInviteQrCodeAsBase64(plaintextToken, 350);

        // Thymeleafテンプレート変数組み立て
        Map<String, Object> vars = new HashMap<>();
        vars.put("teamName", team.name());
        vars.put("qrBase64", qrBase64);
        vars.put("expiresAt", token.expiresAt());
        vars.put("maxUses", token.maxUses());
        vars.put("usedCount", token.usedCount());
        vars.put("isRevoked", token.revokedAt() != null);
        vars.put("isExpired", token.expiresAt() != null
            && token.expiresAt().isBefore(LocalDateTime.now()));

        // PDF生成
        byte[] pdfBytes = pdfGeneratorService.generateFromTemplate("pdf/invite-qr", vars);

        // ファイル名生成
        String fileName = PdfFileNameBuilder.of("招待QRコード")
            .date(LocalDate.now())
            .identifier(team.name())
            .build();

        return PdfResponseHelper.toResponse(pdfBytes, fileName);
    }
}
