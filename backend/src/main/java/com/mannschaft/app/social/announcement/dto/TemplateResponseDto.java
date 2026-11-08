package com.mannschaft.app.social.announcement.dto;

import com.mannschaft.app.social.announcement.AnnouncementRangeTemplateEntity;
import com.mannschaft.app.social.announcement.audience.TargetGroupRange;
import com.mannschaft.app.social.announcement.audience.TemplateGroupItemsCodec;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

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

    /** 組織告知でのチーム絞り込み（チームの slug。null = 全チーム対象）。 */
    private List<String> targetTeamIds;

    /** 個別に選んだチームグループ ID（null = 個別選択なし。F01.2.1 §5.6）。 */
    private List<UUID> targetGroupIds;

    /** 並び順の範囲指定（null = 範囲指定なし）。 */
    private TargetGroupRange targetGroupRange;

    /** 未分類のチームを含めるか。 */
    private Boolean includeUnassigned;

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
     * <p>target_team_ids は DB に内部 ID の JSON 配列（"[1,3,5]"）で保存されている。API ではチームの URL 識別子
     * （slug）で返すため、ID → slug の写像を呼び出し側から受け取る（slug 一本化。DTO はドメインを引かない）。</p>
     *
     * @param entity      テンプレートエンティティ
     * @param teamSlugsOf 内部のチーム ID を slug へ写す関数（null を渡せば null を返すこと）
     * @return レスポンス DTO
     */
    public static TemplateResponseDto from(AnnouncementRangeTemplateEntity entity,
                                           Function<List<Long>, List<String>> teamSlugsOf) {
        List<String> slugs = teamSlugsOf.apply(storedTeamIds(entity));

        return TemplateResponseDto.builder()
                .id(entity.getId())
                .scopeType(entity.getScopeType() != null ? entity.getScopeType().name() : null)
                .scopeId(entity.getScopeId())
                .name(entity.getName())
                .targetRole(entity.getTargetRole())
                .targetTeamIds(slugs)
                .targetGroupIds(TemplateGroupItemsCodec.parseGroupIds(entity.getTargetGroupIds()))
                .targetGroupRange(TemplateGroupItemsCodec.parseRange(entity.getTargetGroupRange()))
                .includeUnassigned(Boolean.TRUE.equals(entity.getIncludeUnassigned()))
                .preferredChannel(entity.getPreferredChannel())
                .isDefault(entity.getIsDefault())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /** 保存されている target_team_ids（内部 ID の JSON 配列）を読む。null・空は null（全チーム）。 */
    public static List<Long> storedTeamIds(AnnouncementRangeTemplateEntity entity) {
        List<Long> ids = TemplateGroupItemsCodec.parseTeamIds(entity.getTargetTeamIds());
        return ids == null || ids.isEmpty() ? null : ids;
    }
}
