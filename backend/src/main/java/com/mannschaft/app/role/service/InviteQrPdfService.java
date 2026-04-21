package com.mannschaft.app.role.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfFileNameBuilder;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.pdf.PdfResponseHelper;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
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
    private final TeamRepository teamRepository;
    private final AccessControlService accessControlService;
    private final PdfGeneratorService pdfGeneratorService;

    /**
     * 招待QRコードPDFを生成して返す。
     *
     * @param teamId        チームID
     * @param tokenId       招待トークンID
     * @param requestUserId リクエストユーザーID
     * @return PDFのResponseEntity
     */
    public ResponseEntity<byte[]> generateInviteQrPdf(Long teamId, Long tokenId, Long requestUserId) {
        // 認可チェック: ADMIN or DEPUTY_ADMIN（COMMON_002 で 403）
        accessControlService.checkAdminOrAbove(requestUserId, teamId, "TEAM");

        // トークン取得（IDORチェック: tokenId が teamId に属することを確認）
        InviteTokenEntity token = inviteService.findByIdAndTeamId(tokenId, teamId);

        // チーム情報取得
        TeamEntity team = teamRepository.findById(teamId)
            .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_001));

        // QRコード生成（350px, Base64）
        String qrBase64 = inviteService.generateInviteQrCodeAsBase64(token.getToken(), 350);

        // Thymeleafテンプレート変数組み立て
        Map<String, Object> vars = new HashMap<>();
        vars.put("teamName", team.getName());
        vars.put("qrBase64", qrBase64);
        vars.put("expiresAt", token.getExpiresAt());
        vars.put("maxUses", token.getMaxUses());
        vars.put("usedCount", token.getUsedCount());
        vars.put("isRevoked", token.getRevokedAt() != null);
        vars.put("isExpired", token.getExpiresAt() != null
            && token.getExpiresAt().isBefore(LocalDateTime.now()));

        // PDF生成
        byte[] pdfBytes = pdfGeneratorService.generateFromTemplate("pdf/invite-qr", vars);

        // ファイル名生成
        String fileName = PdfFileNameBuilder.of("招待QRコード")
            .date(LocalDate.now())
            .identifier(team.getName())
            .build();

        return PdfResponseHelper.toResponse(pdfBytes, fileName);
    }
}
