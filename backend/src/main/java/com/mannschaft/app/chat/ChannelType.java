package com.mannschaft.app.chat;

import java.util.EnumSet;
import java.util.Set;

/**
 * チャットチャンネルの種別。
 */
public enum ChannelType {

    /** チーム公開チャンネル */
    TEAM_PUBLIC,

    /** チーム非公開チャンネル */
    TEAM_PRIVATE,

    /** 組織公開チャンネル */
    ORG_PUBLIC,

    /** 組織非公開チャンネル */
    ORG_PRIVATE,

    /** ダイレクトメッセージ（1対1） */
    DM,

    /** グループダイレクトメッセージ（複数人） */
    GROUP_DM,

    /**
     * 村ロビー（F17.1 井戸端会議）。
     * villages.id を {@code chat_channels.village_id} に紐付けて一意に払い出される。
     * チーム/組織と独立した第三のスコープであり、{@code teamId}/{@code organizationId} は NULL。
     */
    VILLAGE_LOBBY,

    /**
     * イベント専用チャット。
     * {@code chat_channels.source_type="EVENT"}, {@code source_id=eventId} に紐付けて一意に払い出される。
     * イベント作成時に自動生成され、イベント完了・キャンセル時にアーカイブされる。
     */
    EVENT_CHAT,

    /**
     * 大会全体の連絡チャット（F08.7.1 連絡機能）。
     * {@code chat_channels.source_type="TOURNAMENT"}, {@code source_id=tournamentId} に紐付けて
     * 一意に払い出される。大会作成時に自動生成される。{@code team_id}/{@code organization_id} は NULL。
     * 桁: "TOURNAMENT_CHAT"=15字（隊0 で channel_type VARCHAR(30) 化済み）。
     */
    TOURNAMENT_CHAT,

    /**
     * 大会ディビジョンの連絡チャット（F08.7.1 連絡機能）。
     * {@code chat_channels.source_type="TOURNAMENT_DIVISION"}, {@code source_id=divisionId} に紐付けて
     * 一意に払い出される。ディビジョン作成時に自動生成される。{@code team_id}/{@code organization_id} は NULL。
     * 桁: "TOURNAMENT_DIVISION_CHAT"=24字（隊0 で channel_type VARCHAR(30) 化済み）。
     */
    TOURNAMENT_DIVISION_CHAT;

    /**
     * {@code chat_channel_members} 行でメンバーシップが定義される種別（＝チャンネルメンバーシップで
     * 閲覧・投稿を認可する種別）。
     *
     * <p>これ以外の種別（{@link #VILLAGE_LOBBY} / {@link #EVENT_CHAT} / {@link #TOURNAMENT_CHAT} /
     * {@link #TOURNAMENT_DIVISION_CHAT}）はメンバー行を持たない横断スペースであり、village メンバーシップ・
     * 大会連絡スペース認可（{@code TournamentContactAccessService}）等の<b>別ドメインのアクセスモデル</b>で
     * 管理される。これらにメンバーシップ検査を適用すると正当な利用者まで一律拒否してしまう（機能破壊）ため、
     * 本集合から除外する。</p>
     *
     * <p><b>正準の出所</b>: WebSocket 購読認可
     * {@code ChatChannelSubscriptionInterceptor.MEMBERSHIP_GATED_TYPES}（AC-11 / 既存 main）で確立した
     * 境界をここへ引き上げ、WS 層と REST 層（{@code ChatMessageService} /
     * {@code ChatChannelService} / {@code ChatMemberService}）が<b>同一の定義を共有</b>するようにした。
     * 種別が増えたときに片側だけ更新されて認可が割れる事故を構造的に防ぐ。</p>
     */
    private static final Set<ChannelType> MEMBERSHIP_GATED_TYPES = EnumSet.of(
            TEAM_PUBLIC,
            TEAM_PRIVATE,
            ORG_PUBLIC,
            ORG_PRIVATE,
            DM,
            GROUP_DM);

    /**
     * チャンネルメンバーシップ（{@code chat_channel_members}）で閲覧・投稿を認可すべき種別かどうか。
     *
     * <p>{@code true} の場合、当該チャンネルの閲覧・投稿には
     * {@code chat_channel_members} に行が存在することを要する。{@code TEAM_PUBLIC} が含まれる点に注意:
     * 「公開」はチームメンバーが<b>自分で参加できる</b>ことを意味し、未参加のまま本文を閲覧できることを
     * 意味しない（未参加者は {@code POST /chat/channels/{id}/join} で参加してから閲覧する）。</p>
     *
     * @return メンバーシップで認可する種別なら {@code true}
     */
    public boolean isMembershipGated() {
        return MEMBERSHIP_GATED_TYPES.contains(this);
    }

    /**
     * チーム／組織などの上位スコープ配下に属する「公開」チャンネルかどうか。
     *
     * <p>{@code POST /chat/channels/{id}/join} による<b>自己参加</b>を許してよい唯一の種別群。
     * 非公開（{@code *_PRIVATE}）は招待制、{@link #DM} / {@link #GROUP_DM} は当事者のみで構成されるため
     * いずれも自己参加は認めない。</p>
     *
     * @return 自己参加を許容する公開スコープチャンネルなら {@code true}
     */
    public boolean isSelfJoinableScopeChannel() {
        return this == TEAM_PUBLIC || this == ORG_PUBLIC;
    }

    /**
     * チーム／組織という上位スコープの配下に属する種別かどうか。
     *
     * <p>{@code true} の種別は {@code chat_channels.team_id} または {@code organization_id} を持ち、
     * そのスコープの内部資産として扱われる。作成時にはスコープ所属の検証を要する
     * （{@code ChatChannelAccessGuard#requireChannelCreationScope}）。
     * {@link #DM} / {@link #GROUP_DM} は当事者のみで構成されスコープを持たないため {@code false}、
     * 村ロビー・イベント・大会チャットは各ドメイン側で自動払い出しされるため {@code false} を返す。</p>
     *
     * @return チーム／組織スコープ配下のチャンネル種別なら {@code true}
     */
    public boolean isScopeChannel() {
        return this == TEAM_PUBLIC || this == TEAM_PRIVATE
                || this == ORG_PUBLIC || this == ORG_PRIVATE;
    }
}
