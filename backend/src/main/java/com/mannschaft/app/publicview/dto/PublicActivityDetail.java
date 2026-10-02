package com.mannschaft.app.publicview.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * F06.4 公開活動記録の <b>詳細</b> 用 公開専用 DTO（匿名公開・PII 完全分離）。
 *
 * <p>金型: {@link PublicPostDetail}（F19.1 §6.3「PII 完全分離」原則）。
 * 認証済み API の {@code ActivityRecordResponse} とは<b>共有せず</b>、
 * 公開経路専用の型を分けることで Defense in Depth を担保する
 * （認証済み DTO に項目が増えても公開経路には波及しない）。</p>
 *
 * <p>公開してよい項目は軍議で御裁可された以下の <b>8 つのみ</b>。
 * 項目を増やす場合は「未認証の誰にでも見せてよいか」を再審議すること。</p>
 *
 * <h2>Defense in Depth - 禁則フィールド（絶対に含めない）</h2>
 * <ul>
 *   <li>{@code fieldValues} / {@code attachments} — テンプレート入力値・添付の生 JSON。
 *       内部限定メモや非公開ファイル ID が入りうる</li>
 *   <li>{@code createdBy} — 作成者のユーザー ID（PII・ID 列挙の足がかり）</li>
 *   <li>{@code visibility} / {@code status} — 内部の可視性・ライフサイクル状態。
 *       露出すると「非公開が存在する」ことを漏らす</li>
 *   <li>{@code templateId} / {@code venueId} / {@code scheduleId} — 内部リソース ID（IDOR の足がかり）</li>
 *   <li>{@code scopeId} 生値 — スコープ ID は {@link PublicScopeRef} 経由でのみ露出する</li>
 *   <li>{@code updatedAt} / {@code deletedAt} — 内部運用のタイムスタンプ</li>
 *   <li>{@code publishable} — {@code isPublishable()} 派生ゲッター（内部状態の漏洩）</li>
 *   <li>{@code location} — 開催場所。会員限定で共有される所在情報であり公開対象外</li>
 * </ul>
 *
 * <p><b>{@code @JsonInclude(NON_NULL)} を付けてはならない</b>: 時刻・説明が未設定の記録では
 * 当該キーごと消え、フロントエンドの型契約（キーは常に存在し値が null）が崩れる。
 * 契約テスト {@code ActivityPublicContractIT} の AC-23 / AC-24 がこれを機械的に守る。</p>
 *
 * @param id                活動記録 ID
 * @param title             タイトル
 * @param activityDate      活動日
 * @param activityTimeStart 開始時刻（未設定なら {@code null}）
 * @param activityTimeEnd   終了時刻（未設定なら {@code null}）
 * @param description       説明（未設定なら {@code null}）
 * @param scopeRef          所属スコープ（チーム / 組織）への軽量参照。親が PUBLIC であることは呼び出し側で検証済み
 * @param createdAt         作成日時
 */
public record PublicActivityDetail(
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
