package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.common.BusinessException;

/**
 * セグメント評価器自体は配備されているが、評価に必要なデータソース（テーブル / カラム）が
 * 未整備のため評価不能であることを示す例外。
 *
 * <p>{@link UnsupportedSegmentException}（戦略パターン未配備）と異なり、
 * 「Evaluator は存在するが、users.gender カラムが未追加」「user_interest_tags テーブル未作成」など
 * <b>スキーマ整備待ち</b>のケースを表す。後続フェーズで Flyway マイグレーションが行われ次第、
 * 各 Evaluator の {@code resolveUserIds()} を本実装に差し替えるだけで切替可能になる。</p>
 *
 * <p>対処療法（症状隠し）で空集合を返す実装を禁ずる
 * （CLAUDE.md「障害対応の原則 — 根治治療を徹底すること」に準拠）。
 * 未整備のセグメントを含むキャンペーンは {@code AD_AUDIENCE_INVALID} で 400 を返し、
 * 設計書側にも「該当セグメント型は今後のスキーマ整備で本実装予定」と明記する。</p>
 */
public class SegmentDataSourceNotAvailableException extends BusinessException {

    private final AdSegmentType segmentType;
    private final String missingDataSource;

    public SegmentDataSourceNotAvailableException(AdSegmentType type, String missingDataSource) {
        super(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        this.segmentType = type;
        this.missingDataSource = missingDataSource;
    }

    public AdSegmentType getSegmentType() {
        return segmentType;
    }

    public String getMissingDataSource() {
        return missingDataSource;
    }
}
