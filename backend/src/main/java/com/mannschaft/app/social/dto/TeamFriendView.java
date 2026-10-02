package com.mannschaft.app.social.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * フレンドチーム一覧の 1 エントリを表す View DTO。
 *
 * <p>
 * {@code GET /api/v1/teams/{id}/friends} のレスポンスで使用する。
 * 設計書 §5 では {@code friend_team} ネスト構造だが、Phase 1 段階では
 * 相手チームの基本情報（ID・名称）をフラットに持ち、次陣で {@code FriendTeamProfileResolver}
 * を介して拡張属性（アイコン・都道府県等）を埋め込む想定とする。
 * </p>
 *
 * <h2>なぜ明示的な {@link JsonCreator} コンストラクタを持つのか（issue #2496）</h2>
 * <p>
 * 本 DTO は {@code teamFriendList} キャッシュに載る＝Valkey へ JSON シリアライズされ、
 * ヒット時に JSON からデシリアライズされる。ところが Lombok の {@code @Builder} が生成する
 * 暗黙コンストラクタの引数名は {@code isPublic} である一方、{@code @Getter} が生成する
 * {@code isPublic()} を Jackson は <b>{@code "public"}</b> というプロパティ名で書き出す。
 * この非対称のままキャッシュに載せると、ラウンドトリップで {@code isPublic} が
 * <b>常に {@code false} に化ける</b>（＝非公開フレンドが公開扱いになる／公開フレンドが
 * 非公開表示になる）。しかも {@code LoggingCacheErrorHandler} の fail-open により、
 * デシリアライズ失敗系は WARN に握り潰されて表面化しにくい。
 * </p>
 * <p>
 * そこで読み書き両側のプロパティ名を {@code "public"} に明示的に固定する。
 * これは <b>従来の API 出力（{@code "public"}）と完全に同一</b>であり、
 * OpenAPI スキーマ・フロントエンドの型に変更はない。
 * </p>
 */
@Getter
public class TeamFriendView {

    /** {@code team_friends.id} */
    private final Long teamFriendId;

    /** 相手チーム ID（閲覧者視点から見てフレンドになっている側） */
    private final Long friendTeamId;

    /** 相手チーム名（非公開の場合は空文字が入り得るため Nullable 扱い） */
    private final String friendTeamName;

    /**
     * フレンド関係の公開フラグ。
     * JSON 上のプロパティ名は {@code "public"}（{@code isPublic()} ゲッター由来。既存 API 互換）。
     */
    private final boolean isPublic;

    /** フレンド関係成立日時 */
    private final LocalDateTime establishedAt;

    /**
     * 全項目コンストラクタ。{@code @Builder} をここに付けることで、
     * Jackson のデシリアライズ契約（{@link JsonCreator}）とビルダーの生成元を一本化する。
     */
    @Builder
    @JsonCreator
    public TeamFriendView(
            @JsonProperty("teamFriendId") Long teamFriendId,
            @JsonProperty("friendTeamId") Long friendTeamId,
            @JsonProperty("friendTeamName") String friendTeamName,
            @JsonProperty("public") boolean isPublic,
            @JsonProperty("establishedAt") LocalDateTime establishedAt) {
        this.teamFriendId = teamFriendId;
        this.friendTeamId = friendTeamId;
        this.friendTeamName = friendTeamName;
        this.isPublic = isPublic;
        this.establishedAt = establishedAt;
    }
}
