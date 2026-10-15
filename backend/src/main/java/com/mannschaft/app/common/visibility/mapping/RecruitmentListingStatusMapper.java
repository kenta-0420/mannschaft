package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;

/**
 * {@link RecruitmentListingStatus} を {@link ContentStatus} に正規化する Mapper。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §7.5
 * 「各機能 status の正規化」表に準拠する。</p>
 *
 * <p>マッピング:
 * <ul>
 *   <li>{@link RecruitmentListingStatus#DRAFT} → {@link ContentStatus#DRAFT}
 *       （作成者と SystemAdmin のみ可視）</li>
 *   <li>{@link RecruitmentListingStatus#OPEN} / {@link RecruitmentListingStatus#FULL}
 *       / {@link RecruitmentListingStatus#CLOSED} /
 *       {@link RecruitmentListingStatus#COMPLETED}
 *       → {@link ContentStatus#PUBLISHED}（visibility 評価へ）</li>
 *   <li>{@link RecruitmentListingStatus#CANCELLED} /
 *       {@link RecruitmentListingStatus#AUTO_CANCELLED}
 *       → {@link ContentStatus#ARCHIVED}（SystemAdmin のみ可視）</li>
 * </ul>
 *
 * <p>{@link ContentStatus#DELETED} は recruitment_listings.deleted_at 列を
 * {@code @SQLRestriction} が取得時にフィルタするため、本マッパーで扱う必要は無い
 * （取得不可 → 自然に fail-closed）。</p>
 *
 * <p>{@link ContentStatus#SCHEDULED} 区分は recruitment_listings には対応する
 * 列が無いためマッピング対象外（公開予約は OPEN への遷移時刻で表現するため
 * status 軸では DRAFT/PUBLISHED の 2 値）。</p>
 */
public final class RecruitmentListingStatusMapper {

    private RecruitmentListingStatusMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link RecruitmentListingStatus} を共通の {@link ContentStatus} に写像する。
     *
     * @param status 機能側 enum (non-null)
     * @return 対応する ContentStatus 値
     */
    public static ContentStatus toStandard(RecruitmentListingStatus status) {
        return switch (status) {
            case DRAFT -> ContentStatus.DRAFT;
            case OPEN, FULL, CLOSED, COMPLETED -> ContentStatus.PUBLISHED;
            case CANCELLED, AUTO_CANCELLED -> ContentStatus.ARCHIVED;
        };
    }
}
