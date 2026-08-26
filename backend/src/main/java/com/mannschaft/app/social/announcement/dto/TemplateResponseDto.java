package com.mannschaft.app.social.announcement.dto;

import com.mannschaft.app.social.announcement.AnnouncementRangeTemplateEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * F02.8 告知ウィザード範囲テンプレートレスポンス DTO。
 *
 * <p>{@link AnnouncementRangeTemplateEntity} をクライアントに返却する形式に変換した DTO。
 * {@link #from(AnnouncementRangeTemplateEntity)} ファクトリメソッドを使って生成する。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateResponseDto {

    /** テンプレート ID。 */
    private Long id;

    /** スコープ種別（TEAM / ORGANIZATION）。 */
    private String scopeType;

    /** スコープ ID（teams.id または organizations.id）。 */
    private Long scopeId;

    /** テンプレート名。 */
    private String name;

    /** 告知対象ロール（MEMBERS_AND_ABOVE / SUPPORTERS_AND_ABOVE / PUBLIC）。 */
    private String targetRole;

    /** 組織告知でのチーム絞り込み対象 ID リスト（null = 全チーム対象）。 */
    private List<Long> targetTeamIds;

    /** 優先チャネル（BULLETIN_THREAD / TIMELINE_POST / BLOG_POST / TODO / SCHEDULE / SURVEY）。 */
    private String preferredChannel;

    /** デフォルトテンプレートフラグ。 */
    private Boolean isDefault;

    /** 作成者ユーザー ID。 */
    private Long createdBy;

    /** 作成日時。 */
    private LocalDateTime createdAt;

    /**
     * {@link AnnouncementRangeTemplateEntity} からこの DTO を生成するファクトリメソッド。
     *
     * <p>target_team_ids は DB に JSON 文字列（"[1,3,5]"）で保存されているため、
     * {@code List<Long>} に変換して返す。</p>
     *
     * @param entity テンプレートエンティティ
     * @return レスポンス DTO
     */
    public static TemplateResponseDto from(AnnouncementRangeTemplateEntity entity) {
        // target_team_ids: "[1,3,5]" → List<Long> に変換
        List<Long> ids = null;
        if (entity.getTargetTeamIds() != null && !entity.getTargetTeamIds().isBlank()) {
            String raw = entity.getTargetTeamIds().replaceAll("[\\[\\]\\s]", "");
            if (!raw.isBlank()) {
                ids = Arrays.stream(raw.split(","))
                        .map(Long::parseLong)
                        .collect(Collectors.toList());
            }
        }

        return TemplateResponseDto.builder()
                .id(entity.getId())
                .scopeType(entity.getScopeType() != null ? entity.getScopeType().name() : null)
                .scopeId(entity.getScopeId())
                .name(entity.getName())
                .targetRole(entity.getTargetRole())
                .targetTeamIds(ids)
                .preferredChannel(entity.getPreferredChannel())
                .isDefault(entity.getIsDefault())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
