package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import com.mannschaft.app.social.announcement.audience.TargetGroupRange;
import com.mannschaft.app.social.announcement.audience.TemplateGroupItemsCodec;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * 告知ウィザード範囲テンプレートの作成・更新リクエスト DTO（F02.8）。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnouncementRangeTemplateRequest {

    /** テンプレート名（1〜100文字） */
    private String name;

    /**
     * 告知対象ロール。
     * 値: MEMBERS_AND_ABOVE / SUPPORTERS_AND_ABOVE / PUBLIC
     */
    private String targetRole;

    /**
     * 組織告知でのチーム絞り込み（チームの URL 識別子＝slug。{@code GET /organizations/{slug}/teams} の {@code id}）。
     * NULL・空 = 全チーム対象。保存時に内部 ID へ写して検証する（{@code BroadcastAudienceResolver#resolveTemplateTeams}）。
     */
    @Schema(description = "組織告知でのチーム絞り込み（チームの slug。組織所属チーム一覧の id と同じ値）。null・空は全チーム")
    private List<String> targetTeamIds;

    /**
     * {@link #targetTeamIds}（slug）を検証して内部 ID へ写した結果（保存する値）。リクエストの JSON には現れない。
     * 組織スコープの Controller が {@code BroadcastAudienceResolver#resolveTemplateTeams} の結果を
     * {@link #applyResolvedTeamIds} で載せる。載せなければ（TEAM スコープ）「全チーム」（null）として保存する。
     */
    @JsonIgnore
    @Schema(hidden = true)
    private List<Long> resolvedTargetTeamIds;

    /** 個別に選んだチームグループ ID（組織スコープのみ。F01.2.1 §5.6）。 */
    private List<UUID> targetGroupIds;

    /** 並び順の範囲指定（組織スコープのみ。端の片方は null 可。F01.2.1 §5.6）。 */
    private TargetGroupRange targetGroupRange;

    /** 未分類のチームを含めるか（組織スコープのみ）。 */
    private Boolean includeUnassigned;

    /**
     * 優先チャネル。
     * 値: BULLETIN_THREAD / TIMELINE_POST / BLOG_POST / TODO / SCHEDULE / SURVEY
     * NULL = 優先チャネル未設定。
     */
    private String preferredChannel;

    /** デフォルトテンプレートフラグ */
    private Boolean isDefault;

    /** グループ項目（個別・範囲・未分類）が1つでも指定されたか。保存には MANAGE_CONTENT が要る（F01.2.1 §12）。 */
    public boolean hasGroupItems() {
        return (targetGroupIds != null && !targetGroupIds.isEmpty())
                || targetGroupRange != null
                || Boolean.TRUE.equals(includeUnassigned);
    }

    /** 検証済みのチーム ID を載せる（{@link #resolvedTargetTeamIds}）。 */
    public void applyResolvedTeamIds(List<Long> teamIds) {
        this.resolvedTargetTeamIds = teamIds;
    }

    /** 保存する target_team_ids（検証済みのチーム ID の JSON 配列。未設定・空は「全チーム」として null）。 */
    public String toStoredTargetTeamIdsJson() {
        return TemplateGroupItemsCodec.teamIdsToJson(resolvedTargetTeamIds);
    }

    /** targetGroupIds を JSON に変換する（空・null は null）。 */
    public String getTargetGroupIdsJson() {
        return TemplateGroupItemsCodec.groupIdsToJson(targetGroupIds);
    }

    /** targetGroupRange を JSON に変換する（null は null）。 */
    public String getTargetGroupRangeJson() {
        return TemplateGroupItemsCodec.rangeToJson(targetGroupRange);
    }
}
