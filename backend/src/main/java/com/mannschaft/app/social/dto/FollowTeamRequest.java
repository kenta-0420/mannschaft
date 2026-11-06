package com.mannschaft.app.social.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.common.util.SlugValidator;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/**
 * チーム間フォローリクエスト DTO。F01.5 の
 * {@code POST /api/v1/teams/{id}/friends/follow} で使用する。
 *
 * <p>
 * 自チームが他チームをフォローする際のパラメータを保持する。
 * 相互フォロー成立時にフレンド関係へ自動昇格する。
 * </p>
 */
@Getter
public class FollowTeamRequest {

    /** フォロー先のチーム ID（{@code targetTeamSlug} とちょうど一方だけ指定する） */
    private final Long targetTeamId;

    /**
     * フォロー先のチーム slug（CMP-261012-0315）。{@code targetTeamId} とちょうど一方だけ指定する。
     * 書式は {@link SlugValidator#isValidFormat(String)} に準拠する。
     */
    private final String targetTeamSlug;

    /** 相手チーム管理者に表示される任意の挨拶コメント（最大 300 文字） */
    @Size(max = 300)
    private final String comment;

    /**
     * Jackson によるデシリアライズ用コンストラクタ。JSON 側のスネークケース
     * {@code target_team_id} を明示的にマッピングする。
     *
     * @param targetTeamId   フォロー先チーム ID
     * @param targetTeamSlug フォロー先チーム slug
     * @param comment        任意の挨拶コメント
     */
    @JsonCreator
    public FollowTeamRequest(
            @JsonProperty("targetTeamId") Long targetTeamId,
            @JsonProperty("targetTeamSlug") String targetTeamSlug,
            @JsonProperty("comment") String comment) {
        this.targetTeamId = targetTeamId;
        this.targetTeamSlug = targetTeamSlug;
        this.comment = comment;
    }

    /**
     * {@code targetTeamId} と {@code targetTeamSlug} の指定がちょうど一方であることを検証する。
     * 両方指定・両方なしは 400 COMMON_001 になる。
     *
     * @return ちょうど一方だけ指定されていれば true
     */
    @JsonIgnore
    @AssertTrue(message = "targetTeamId と targetTeamSlug はちょうど一方だけ指定してください")
    public boolean isExactlyOneTargetSpecified() {
        return (targetTeamId != null) != (targetTeamSlug != null);
    }

    /**
     * {@code targetTeamSlug} が指定されている場合に、書式が妥当であることを検証する
     * （空文字・空白のみ・規則外は不正）。
     *
     * @return slug 未指定、または書式が妥当なら true
     */
    @JsonIgnore
    @AssertTrue(message = "targetTeamSlug の形式が不正です")
    public boolean isTargetTeamSlugFormatValid() {
        return targetTeamSlug == null || SlugValidator.isValidFormat(targetTeamSlug);
    }
}
