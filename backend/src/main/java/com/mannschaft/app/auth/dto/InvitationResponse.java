package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * F01.9 年齢確認・保護者同意機能: 保護者招待レスポンス。
 *
 * <p>子ユーザーが送信済みの招待の一覧取得や招待送信後のレスポンスに使用する。</p>
 */
@Getter
@RequiredArgsConstructor
public class InvitationResponse {

    /** 招待リンク ID（UUID 文字列）*/
    private final String linkId;

    /** 招待先の保護者メールアドレス */
    private final String parentEmail;

    /** ステータス（PENDING / APPROVED / REJECTED / REVOKED）*/
    private final String status;

    /** トークン有効期限 */
    private final LocalDateTime expiresAt;

    /** 招待作成日時 */
    private final LocalDateTime createdAt;

    /**
     * {@link ParentalConsentLinkEntity} から {@link InvitationResponse} を生成するファクトリメソッド。
     */
    public static InvitationResponse from(ParentalConsentLinkEntity link) {
        return new InvitationResponse(
                link.getId() != null ? link.getId().toString() : null,
                link.getParentEmail(),
                link.getStatus() != null ? link.getStatus().name() : null,
                link.getExpiresAt(),
                link.getCreatedAt()
        );
    }
}
