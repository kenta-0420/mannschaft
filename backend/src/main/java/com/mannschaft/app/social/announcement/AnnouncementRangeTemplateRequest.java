package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 告知ウィザード範囲テンプレートの作成・更新リクエスト DTO（F02.8）。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnouncementRangeTemplateRequest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** テンプレート名（1〜100文字） */
    private String name;

    /**
     * 告知対象ロール。
     * 値: MEMBERS_AND_ABOVE / SUPPORTERS_AND_ABOVE / PUBLIC
     */
    private String targetRole;

    /**
     * 組織告知でのチーム絞り込み対象 ID リスト。
     * NULL = 全チーム対象。
     */
    private List<Long> targetTeamIds;

    /**
     * 優先チャネル。
     * 値: BULLETIN_THREAD / TIMELINE_POST / BLOG_POST / TODO / SCHEDULE / SURVEY
     * NULL = 優先チャネル未設定。
     */
    private String preferredChannel;

    /** デフォルトテンプレートフラグ */
    private Boolean isDefault;

    /**
     * targetTeamIds を JSON 配列文字列に変換する。
     * リストが null または空の場合は null を返す。
     *
     * @return JSON 配列文字列（例: "[1,3,5]"）、または null
     */
    public String getTargetTeamIdsJson() {
        if (targetTeamIds == null || targetTeamIds.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(targetTeamIds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("targetTeamIds の JSON 変換に失敗しました", e);
        }
    }
}
