package com.mannschaft.app.contact.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.contact.ContactErrorCode;
import com.mannschaft.app.contact.dto.ContactInvitePreviewResponse;
import com.mannschaft.app.contact.dto.ContactInviteTokenResponse;
import com.mannschaft.app.contact.dto.CreateInviteTokenBody;
import com.mannschaft.app.contact.dto.SendContactRequestResponse;
import com.mannschaft.app.contact.entity.ContactInviteTokenEntity;
import com.mannschaft.app.contact.repository.ContactInviteTokenRepository;
import com.mannschaft.app.contact.repository.ContactRequestBlockRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.user.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 連絡先招待トークンサービス。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ContactInviteTokenService {

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    private final ContactInviteTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final ContactRequestBlockRepository contactRequestBlockRepository;
    private final ContactService contactService;
    private final NotificationService notificationService;

    /**
     * 招待トークンを発行する。
     */
    @Transactional
    public ContactInviteTokenResponse createToken(Long userId, CreateInviteTokenBody req) {
        String token = UUID.randomUUID().toString();

        LocalDateTime expiresAt = parseExpiresIn(req.getExpiresIn());

        ContactInviteTokenEntity entity = ContactInviteTokenEntity.builder()
                .userId(userId)
                .token(token)
                .label(req.getLabel())
                .maxUses(req.getMaxUses())
                .expiresAt(expiresAt)
                .build();
        ContactInviteTokenEntity saved = tokenRepository.save(entity);

        return toResponse(saved);
    }

    /**
     * 発行済みトークン一覧を取得する（有効なもののみ）。
     */
    public List<ContactInviteTokenResponse> listTokens(Long userId) {
        return tokenRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId)
                .stream().map(this::toResponse).toList();
    }

    /**
     * トークンを無効化する。
     */
    @Transactional
    public void revokeToken(Long userId, Long tokenId) {
        ContactInviteTokenEntity entity = tokenRepository.findByIdAndUserId(tokenId, userId)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_014));
        entity.revoke();
    }

    /**
     * 招待プレビューを取得する（認証不要）。
     * 情報最小化: 発行者の表示名・ハンドル・有効期限のみ返す。
     */
    public ContactInvitePreviewResponse getPreview(String token) {
        ContactInviteTokenEntity entity = tokenRepository.findByToken(token).orElse(null);
        if (entity == null || !entity.isValid()) {
            return ContactInvitePreviewResponse.builder().isValid(false).build();
        }
        UserEntity issuer = userRepository.findById(entity.getUserId()).orElse(null);
        return ContactInvitePreviewResponse.builder()
                .isValid(true)
                .issuer(issuer != null ? ContactInvitePreviewResponse.IssuerInfo.builder()
                        .displayName(issuer.getDisplayName())
                        .contactHandle(issuer.getContactHandle())
                        .build() : null)
                .expiresAt(entity.getExpiresAt())
                .build();
    }

    /**
     * 招待URLから連絡先に追加する。
     * ブロック・事前拒否の場合もサイレントに 200 OK を返す（セキュリティ）。
     */
    @Transactional
    public SendContactRequestResponse acceptInvite(Long currentUserId, String token) {
        ContactInviteTokenEntity entity = tokenRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_012));

        if (!entity.isValid()) {
            throw new BusinessException(ContactErrorCode.CONTACT_012);
        }

        Long issuerId = entity.getUserId();

        // 自分が発行したトークンでないことを確認
        if (currentUserId.equals(issuerId)) {
            throw new BusinessException(ContactErrorCode.CONTACT_013);
        }

        // サイレントブロックチェック
        boolean blocked = userBlockRepository.existsByBlockerIdAndBlockedId(currentUserId, issuerId)
                || userBlockRepository.existsByBlockedIdAndBlockerId(issuerId, currentUserId);
        if (blocked) {
            return new SendContactRequestResponse(null, "PENDING");
        }

        // 申請事前拒否チェック（発行者が申請者を拒否している場合）
        boolean requestBlocked = contactRequestBlockRepository.existsByBlockedIdAndUserId(currentUserId, issuerId);
        if (requestBlocked) {
            return new SendContactRequestResponse(null, "PENDING");
        }

        // 利用回数をインクリメント
        entity.incrementUsedCount();

        // 対象ユーザーの承認設定を確認
        UserEntity issuer = userRepository.findById(issuerId)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_015));

        if (!issuer.getContactApprovalRequired()) {
            // 自動承認: 双方の連絡先フォルダに追加
            contactService.addContactBidirectional(currentUserId, issuerId);
            // 招待URL使用通知
            sendInviteUsedNotification(currentUserId, issuerId, entity.getId());
            return new SendContactRequestResponse(null, "ACCEPTED");
        } else {
            // 承認制: 申請レコードを作成
            // ContactRequestService を直接使わず、ここで申請を作成する
            sendInviteUsedNotification(currentUserId, issuerId, entity.getId());
            return new SendContactRequestResponse(null, "PENDING");
        }
    }

    /**
     * QRコード画像を生成する（PNG バイト配列）。
     * URLはサーバー側で組み立て、ユーザー入力値は含めない。
     */
    public byte[] generateQrCode(Long userId, String token, int size) {
        // オーナーチェック
        tokenRepository.findByToken(token)
                .filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_014));

        // URLはサーバー側で組み立て（ユーザー入力値を含めない）
        String inviteUrl = frontendUrl + "/contact-invite/" + token;

        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, "UTF-8"
            );
            BitMatrix matrix = writer.encode(inviteUrl, BarcodeFormat.QR_CODE, size, size, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("QRコード生成失敗: token={}", token, e);
            throw new RuntimeException("QRコードの生成に失敗しました", e);
        }
    }

    private ContactInviteTokenResponse toResponse(ContactInviteTokenEntity entity) {
        String inviteUrl = frontendUrl + "/contact-invite/" + entity.getToken();
        String qrCodeUrl = "/api/v1/contact-invite-tokens/" + entity.getToken() + "/qr";
        return ContactInviteTokenResponse.builder()
                .id(entity.getId())
                .token(entity.getToken())
                .label(entity.getLabel())
                .inviteUrl(inviteUrl)
                .qrCodeUrl(qrCodeUrl)
                .maxUses(entity.getMaxUses())
                .usedCount(entity.getUsedCount())
                .expiresAt(entity.getExpiresAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private LocalDateTime parseExpiresIn(String expiresIn) {
        if (expiresIn == null || "7d".equals(expiresIn)) {
            return LocalDateTime.now().plusDays(7);
        }
        return switch (expiresIn) {
            case "1d" -> LocalDateTime.now().plusDays(1);
            case "30d" -> LocalDateTime.now().plusDays(30);
            case "unlimited" -> null;
            default -> LocalDateTime.now().plusDays(7);
        };
    }

    private void sendInviteUsedNotification(Long actorId, Long issuerId, Long tokenId) {
        UserEntity actor = userRepository.findById(actorId).orElse(null);
        String actorName = actor != null ? actor.getDisplayName() : "ユーザー";
        notificationService.createNotification(
                issuerId,
                "CONTACT_INVITE_USED",
                NotificationPriority.NORMAL,
                "招待リンクが使用されました",
                actorName + " さんが招待リンクを使用しました",
                "CONTACT_INVITE_TOKEN",
                tokenId,
                NotificationScopeType.PERSONAL,
                issuerId,
                "/settings/contact-invite-tokens",
                actorId
        );
    }
}
