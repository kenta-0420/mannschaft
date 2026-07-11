package com.mannschaft.app.chat.visibility;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityProjection;

/**
 * F00 Phase B（積み残し根治） — チャットメッセージ
 * ({@link com.mannschaft.app.chat.entity.ChatMessageEntity}) 用 Projection。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §12.3.1。</p>
 *
 * <p>チャットは機能側に visibility 概念を持たない（§12.3.1 の「visibility 概念新設機能」群）。
 * 本基盤では最小実装として <b>{@link StandardVisibility#SCOPE_AFFILIATED}（チャンネルのスコープ＝
 * TEAM/ORGANIZATION への直接所属者全員が閲覧可）</b> 固定で扱う。よって {@link #visibility()} は
 * 常に {@link StandardVisibility#SCOPE_AFFILIATED} を返し、Resolver の {@code toStandard} は恒等写像となる。</p>
 *
 * <p>スコープはメッセージ自身ではなく <b>所属チャンネルの scope</b>（{@code chat_channels.team_id} /
 * {@code organization_id}）で決まる。DM・村ロビー等 team/org スコープを持たないチャンネルのメッセージは
 * {@code scopeType/scopeId} が {@code null} となり、基底の {@code SCOPE_AFFILIATED} 判定で
 * scope が null → fail-closed（不可視）になる。</p>
 *
 * <p>{@code chat_messages} / {@code chat_channels} 双方の {@code @SQLRestriction("deleted_at IS NULL")}
 * により論理削除済の行は射影段階で除外されるため、{@code ContentStatus#DELETED} を Projection で
 * 再区別する必要は無い（取得不可 → fail-closed の自然な振る舞い）。</p>
 *
 * @param id           chat_messages.id（メッセージ ID）
 * @param scopeType    {@code "TEAM"} / {@code "ORGANIZATION"} / {@code null}（所属チャンネルの scope）
 * @param scopeId      team_id または organization_id（{@code null} 可）
 * @param authorUserId chat_messages.sender_id（送信者 user_id・{@code null} 可）
 */
public record ChatMessageVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId) implements VisibilityProjection {

    @Override
    public Long visibilityTemplateId() {
        return null;
    }

    /**
     * チャットメッセージは「チャンネル所属者全員が閲覧可」の最小実装固定であるため、
     * 常に {@link StandardVisibility#SCOPE_AFFILIATED} を返す。
     */
    @Override
    public Object visibility() {
        return StandardVisibility.SCOPE_AFFILIATED;
    }
}
