package com.mannschaft.app.publicview.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * F06.4 公開活動記録の <b>一覧</b> 用 公開専用 DTO（匿名公開・PII 完全分離）。
 *
 * <p>金型: {@link PublicPostSummary}（F19.1 §6.3「PII 完全分離」原則）。
 * 詳細用の {@link PublicActivityDetail} とは別型とし、将来一覧だけ項目を削る／
 * 詳細だけ項目を足す変更が互いに波及しないようにする。</p>
 *
 * <p>公開してよい項目は軍議で御裁可された <b>8 つのみ</b>で、{@link PublicActivityDetail} と同一。
 * <b>一覧は詳細より広い項目を持ってはならない</b>（一覧経由の漏洩は F08.9 で実際に起きた事故）。</p>
 *
 * <h2>Defense in Depth - 禁則フィールド（絶対に含めない）</h2>
 * <ul>
 *   <li>{@code fieldValues} / {@code attachments} — テンプレート入力値・添付の生 JSON</li>
 *   <li>{@code createdBy} — 作成者のユーザー ID（PII）</li>
 *   <li>{@code visibility} / {@code status} — 内部の可視性・ライフサイクル状態</li>
 *   <li>{@code templateId} / {@code venueId} / {@code scheduleId} — 内部リソース ID（IDOR の足がかり）</li>
 *   <li>{@code scopeId} 生値 — {@link PublicScopeRef} 経由でのみ露出する</li>
 *   <li>{@code updatedAt} / {@code deletedAt} — 内部運用のタイムスタンプ</li>
 *   <li>{@code publishable} — 派生ゲッターによる内部状態の漏洩</li>
 *   <li>{@code location} — 開催場所（会員限定情報）</li>
 * </ul>
 *
 * <p><b>{@code @JsonInclude(NON_NULL)} を付けてはならない</b>（理由は
 * {@link PublicActivityDetail} の Javadoc 参照）。</p>
 *
 * @param id                活動記録 ID
 * @param title             タイトル
 * @param activityDate      活動日
 * @param activityTimeStart 開始時刻（未設定なら {@code null}）
 * @param activityTimeEnd   終了時刻（未設定なら {@code null}）
 * @param description       説明（未設定なら {@code null}）
 * @param scopeRef          所属スコープへの軽量参照
 * @param createdAt         作成日時
 */
public record PublicActivitySummary(
        Long id,
        String title,
        LocalDate activityDate,
        LocalTime activityTimeStart,
        LocalTime activityTimeEnd,
        String description,
        PublicScopeRef scopeRef,
        LocalDateTime createdAt
) {
}
