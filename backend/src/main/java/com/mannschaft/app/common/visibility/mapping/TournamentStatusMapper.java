package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.tournament.TournamentStatus;

/**
 * {@link com.mannschaft.app.tournament.TournamentStatus} を {@link ContentStatus} に正規化する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §7.5
 * 「各機能 status の正規化」表に準拠する。</p>
 *
 * <p>マッピング:
 * <ul>
 *   <li>{@link TournamentStatus#DRAFT} → {@link ContentStatus#DRAFT}（作成者と SystemAdmin のみ可視）</li>
 *   <li>{@link TournamentStatus#OPEN} / {@link TournamentStatus#IN_PROGRESS}
 *       / {@link TournamentStatus#COMPLETED} → {@link ContentStatus#PUBLISHED}（visibility 評価へ）</li>
 *   <li>{@link TournamentStatus#CANCELLED} / {@link TournamentStatus#ARCHIVED}
 *       → {@link ContentStatus#ARCHIVED}（SystemAdmin のみ可視）</li>
 * </ul>
 *
 * <p>{@link ContentStatus#DELETED} は tournaments.deleted_at 列を {@code @SQLRestriction} が
 * 取得時にフィルタするため、本マッパーで扱う必要は無い（取得不可 → 自然に fail-closed）。
 *
 * <p>tournaments テーブルに {@code published_at} 列が無いため、設計書 §7.5 表の SCHEDULED 区分
 * （PUBLISHED かつ published_at が未来）はマッピング対象外。
 */
public final class TournamentStatusMapper {

    private TournamentStatusMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link TournamentStatus} を共通の {@link ContentStatus} に写像する。
     *
     * @param status 機能側 enum (non-null)
     * @return 対応する ContentStatus 値
     */
    public static ContentStatus toStandard(TournamentStatus status) {
        return switch (status) {
            case DRAFT -> ContentStatus.DRAFT;
            case OPEN, IN_PROGRESS, COMPLETED -> ContentStatus.PUBLISHED;
            case CANCELLED, ARCHIVED -> ContentStatus.ARCHIVED;
        };
    }
}
