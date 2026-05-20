package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * F01.9 年齢確認・保護者同意機能: 承認済み保護者リンクレスポンス（子から見た保護者情報）。
 *
 * <p>子ユーザーが承認済みの保護者一覧を取得する際に使用する。</p>
 */
@Getter
@RequiredArgsConstructor
public class ParentLinkResponse {

    /** リンク ID（UUID 文字列）*/
    private final String linkId;

    /** 保護者のメールアドレス */
    private final String parentEmail;

    /**
     * 保護者のシステムユーザー ID。
     * 未登録の外部保護者の場合は null。
     */
    private final Long parentUserId;

    /** 承認日時 */
    private final LocalDateTime approvedAt;

    /**
     * {@link ParentalConsentLinkEntity} から {@link ParentLinkResponse} を生成するファクトリメソッド。
     */
    public static ParentLinkResponse from(ParentalConsentLinkEntity link) {
        return new ParentLinkResponse(
                link.getId() != null ? link.getId().toString() : null,
                link.getParentEmail(),
                link.getParentUserId(),
                link.getApprovedAt()
        );
    }
}
