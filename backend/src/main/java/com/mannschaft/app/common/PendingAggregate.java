package com.mannschaft.app.common;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F10.1.1 / P1: 各ドメインの承認待ち集約 Query Service が返すドメイン中立な集計結果。
 *
 * <p>件数（{@code pendingCount}）とプレビュー（{@code items}・{@code previewSize} 件まで）を保持する。
 * dashboard ドメインの {@code AdminActionRequiredFacade} がこの戻り値をメモリ上で合成して
 * レスポンス DTO を組み立てる。各ドメインの Query Service が dashboard ドメインの DTO に依存しない
 * よう、共通パッケージ（{@code common}）にドメイン中立な型として置く。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/03_admin_action_required_api.md §4.4</p>
 *
 * @param pendingCount 承認待ち総件数（COUNT クエリの結果）
 * @param items        プレビュー要素（最大 previewSize 件）
 */
public record PendingAggregate(long pendingCount, List<Item> items) {

    /**
     * 承認待ちプレビュー 1 件。
     *
     * @param id          対象ドメインの主キー（文字列化）
     * @param title       表示用タイトル
     * @param requestedBy 申請者表示名（バルク解決済み）
     * @param requestedAt 申請日時
     */
    public record Item(String id, String title, String requestedBy, LocalDateTime requestedAt) {
    }
}
