package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * F01.9 年齢確認・保護者同意機能: 承認済み子リンクレスポンス（保護者から見た子情報）。
 *
 * <p>保護者ユーザーが監護している子ユーザーの一覧を取得する際に使用する。
 * PII リスク低減のため、子ユーザーの表示名は呼び出し側で制御する。</p>
 */
@Getter
@RequiredArgsConstructor
public class ChildLinkResponse {

    /** リンク ID（UUID 文字列）*/
    private final String linkId;

    /** 子ユーザーの ID */
    private final Long childUserId;

    /**
     * 子ユーザーの表示名。
     * PII リスク低減のため null にすることも許容する（その場合クライアントは "非公開" を表示する）。
     */
    private final String childDisplayName;

    /** 承認日時 */
    private final LocalDateTime approvedAt;

    /**
     * {@link ParentalConsentLinkEntity} から {@link ChildLinkResponse} を生成するファクトリメソッド。
     *
     * @param link            保護者同意リンクエンティティ
     * @param childDisplayName 子の表示名（null 可: PII リスク低減のため null も許容）
     */
    public static ChildLinkResponse from(ParentalConsentLinkEntity link, String childDisplayName) {
        return new ChildLinkResponse(
                link.getId() != null ? link.getId().toString() : null,
                link.getChildUserId(),
                childDisplayName,
                link.getApprovedAt()
        );
    }
}
