package com.mannschaft.app.recruitment.event;

/**
 * F22.1 市: 謝礼有効な札に応募が確定（CONFIRMED）したことを表すドメインイベント（設計書 02 §5.1）。
 *
 * <p>recruitment ドメインが発火し、payment.escrow がこれを購読して謝礼の与信（authorize）を開始する。
 * クロスドメイン FK を作らず ID のみを受け渡す疎結合連携（README §7・CLAUDE.md 原則1）。
 * payment ドメインの型（ScopeKind 等）には依存させないため、scope 種別は文字列で運ぶ。</p>
 *
 * <ul>
 *   <li>{@code listingId} — 札 ID（escrow の source_id）。</li>
 *   <li>{@code participantId} — 応募 ID（escrow の source_participant_id・冪等キー構成要素）。</li>
 *   <li>{@code payerUserId} — 支払者（応募者）の users.id。</li>
 *   <li>{@code listingScopeType} — 札のスコープ種別（{@code "TEAM"}/{@code "ORGANIZATION"}）。</li>
 *   <li>{@code listingScopeId} — 札のスコープ ID（team_id / organization_id）。</li>
 *   <li>{@code payeeKind} — 受領主体種別（{@code "USER"}/{@code "TEAM"}/{@code "ORG"}）。</li>
 *   <li>{@code payeeUserId} — {@code payeeKind="USER"} の受領者 users.id（それ以外 null）。</li>
 *   <li>{@code faceAmount} — 額面（円整数・札の price）。</li>
 * </ul>
 */
public record RecruitmentParticipantConfirmedEvent(
        Long listingId,
        Long participantId,
        Long payerUserId,
        String listingScopeType,
        Long listingScopeId,
        String payeeKind,
        Long payeeUserId,
        long faceAmount) {
}
