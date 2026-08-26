package com.mannschaft.app.publicview.dto;

import java.time.LocalDateTime;

/**
 * F19.1 Phase 4 公開チーム検索 API のレスポンス DTO。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §7.x Phase 4</p>
 *
 * <h2>Defense in Depth - 禁則フィールド（絶対に含めない）</h2>
 * <ul>
 *   <li>メンバー一覧 / 氏名 / メール / 電話 / 番地レベル住所</li>
 *   <li>{@code supporterEnabled} / {@code archivedAt} / {@code deletedAt} / {@code version}</li>
 *   <li>出席情報 / チャット履歴 / ファイル / 内部ドキュメント</li>
 * </ul>
 *
 * @param id           チーム ID
 * @param slug         チームのスラッグ（URL 識別子）
 * @param name         チーム名
 * @param iconUrl      アイコン URL（null 可）
 * @param memberCount  アクティブメンバー数（{@code teams.member_count} 集約カラムから取得）
 * @param lastPostDate 最新投稿日時（{@code blog_posts.created_at} MAX、投稿なしの場合は null）
 * @param prefectureCode 都道府県コード（JIS X 0401・2桁・null可。F22.1 市の地域フィルタ用。行政区画コードでPIIではない）
 * @param cityCode     市区町村コード（JIS X 0402・5桁・null可。同上）
 */
public record PublicTeamSearchResultResponse(
        Long id,
        String slug,
        String name,
        String iconUrl,
        int memberCount,
        LocalDateTime lastPostDate,
        String prefectureCode,
        String cityCode
) {}
