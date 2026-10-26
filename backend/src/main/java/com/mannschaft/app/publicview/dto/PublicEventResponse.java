package com.mannschaft.app.publicview.dto;

import java.time.OffsetDateTime;

/**
 * F19.1 Phase 7 公開イベントレスポンス DTO。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.2 Phase 7</p>
 *
 * <p><strong>Defense in Depth — 禁則フィールド（絶対に含めない）</strong></p>
 * <ul>
 *   <li>{@code createdBy}（個人特定回避のため不含）</li>
 *   <li>{@code preSurveyId} / {@code postSurveyId}（内部 ID のため不含）</li>
 *   <li>{@code workflowRequestId}（内部 ID のため不含）</li>
 *   <li>{@code deletedAt} / {@code version}（内部状態は非公開）</li>
 *   <li>{@code authorRealNameSnapshot}（PII 漏洩防止）</li>
 * </ul>
 *
 * @param id               イベント ID
 * @param slug             イベントスラグ（URL 用）
 * @param subtitle         イベントサブタイトル
 * @param summary          イベント概要（200 文字程度のトリミング済み）
 * @param status           イベントステータス（PUBLISHED / REGISTRATION_OPEN 等）
 * @param venueName        会場名
 * @param venueAddress     会場住所
 * @param maxCapacity      最大定員（null の場合は無制限）
 * @param registrationCount 参加登録数
 * @param scopeRef         所属スコープ（チーム / 組織）
 * @param createdAt        作成日時
 */
public record PublicEventResponse(
        Long id,
        String slug,
        String subtitle,
        String summary,
        String status,
        String venueName,
        String venueAddress,
        Integer maxCapacity,
        Integer registrationCount,
        PublicScopeRef scopeRef,
        OffsetDateTime createdAt
) {
}
