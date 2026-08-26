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
 * 本基盤ではチャンネル属性に応じた 3 段階の粒度で扱う（検分是正 2026-07-11）:</p>
 * <ol>
 *   <li><b>問い合わせチャンネル（{@code isInquiryChannel=true}）</b> —
 *       {@link StandardVisibility#CUSTOM}。Resolver の {@code evaluateCustom} で
 *       「当該スコープの ADMIN / DEPUTY_ADMIN のみ」に絞る（通知受信者集合
 *       {@code InquiryChatEventListener} と完全一致）。一般メンバーには他者の問い合わせ内容を開かない。</li>
 *   <li><b>PRIVATE チャンネル（{@code isPrivate=true} かつ非 inquiry）</b> —
 *       {@link #scopeType()} / {@link #scopeId()} を {@code null} に落とし
 *       <b>fail-closed（SystemAdmin 以外不可視）</b>。本来はチャンネルメンバーシップ
 *       （{@code chat_channel_members}）ベースの判定が正だが、その昇格は将来の別軍議とする。</li>
 *   <li><b>公開チャンネル（上記以外）</b> — {@link StandardVisibility#SCOPE_AFFILIATED}
 *       （チャンネルのスコープ＝TEAM/ORGANIZATION への直接所属者全員が閲覧可）。</li>
 * </ol>
 *
 * <p><b>危険注記</b>: 本 Projection / Resolver の canView は<b>本文可視の単独ゲートとして使用禁止</b>。
 * チャンネルメンバーシップ（DM・PRIVATE の招待制）を判定に含まないため、チャット本文の読み取り認可は
 * 従来どおり {@code ChatChannelMemberRepository} 直参照（例: {@code ChatChannelSubscriptionInterceptor}）
 * を正とする。本経路は通知発行ガード・コルクボード参照解決など「二次参照の可視性確認」用途に限る。</p>
 *
 * <p>スコープはメッセージ自身ではなく <b>所属チャンネルの scope</b>（{@code chat_channels.team_id} /
 * {@code organization_id}）で決まる。DM・村ロビー等 team/org スコープを持たないチャンネルのメッセージは
 * {@code scopeType/scopeId} が {@code null} となり fail-closed（不可視）になる。</p>
 *
 * <p>{@code chat_messages} / {@code chat_channels} 双方の {@code @SQLRestriction("deleted_at IS NULL")}
 * により論理削除済の行は射影段階で除外されるため、{@code ContentStatus#DELETED} を Projection で
 * 再区別する必要は無い（取得不可 → fail-closed の自然な振る舞い）。</p>
 *
 * @param id               chat_messages.id（メッセージ ID）
 * @param rawScopeType     {@code "TEAM"} / {@code "ORGANIZATION"} / {@code null}（所属チャンネルの scope 生値）
 * @param rawScopeId       team_id または organization_id（{@code null} 可）
 * @param authorUserId     chat_messages.sender_id（送信者 user_id・{@code null} 可）
 * @param isPrivate        chat_channels.is_private（PRIVATE チャンネルなら true）
 * @param isInquiryChannel chat_channels.is_inquiry_channel（問い合わせチャンネルなら true）
 */
public record ChatMessageVisibilityProjection(
        Long id,
        String rawScopeType,
        Long rawScopeId,
        Long authorUserId,
        Boolean isPrivate,
        Boolean isInquiryChannel) implements VisibilityProjection {

    /**
     * PRIVATE チャンネル（非 inquiry）は scope を {@code null} に落とし fail-closed にする。
     * 問い合わせチャンネルは管理者判定に scope が必要なため生値を返す。
     */
    @Override
    public String scopeType() {
        return failClosedPrivate() ? null : rawScopeType;
    }

    /** {@link #scopeType()} と同じ規約で scopeId を返す。 */
    @Override
    public Long scopeId() {
        return failClosedPrivate() ? null : rawScopeId;
    }

    @Override
    public Long visibilityTemplateId() {
        return null;
    }

    /**
     * 問い合わせチャンネルは {@link StandardVisibility#CUSTOM}（Resolver で ADMIN/DEPUTY_ADMIN に限定）、
     * それ以外は {@link StandardVisibility#SCOPE_AFFILIATED}（PRIVATE は scope=null で fail-closed）。
     */
    @Override
    public Object visibility() {
        return inquiry() ? StandardVisibility.CUSTOM : StandardVisibility.SCOPE_AFFILIATED;
    }

    /** 問い合わせチャンネルか（null 安全）。 */
    boolean inquiry() {
        return Boolean.TRUE.equals(isInquiryChannel);
    }

    /** fail-closed 対象（PRIVATE かつ非 inquiry）か。 */
    private boolean failClosedPrivate() {
        return Boolean.TRUE.equals(isPrivate) && !inquiry();
    }
}
