package com.mannschaft.app.gamification;

import com.mannschaft.app.gamification.dto.BadgeResponse;
import com.mannschaft.app.gamification.dto.GamificationConfigResponse;
import com.mannschaft.app.gamification.dto.GamificationUserSettingResponse;
import com.mannschaft.app.gamification.dto.PointRuleResponse;
import com.mannschaft.app.gamification.dto.PointTransactionResponse;
import com.mannschaft.app.gamification.dto.RankingResponse;
import com.mannschaft.app.gamification.entity.BadgeEntity;
import com.mannschaft.app.gamification.entity.GamificationConfigEntity;
import com.mannschaft.app.gamification.entity.GamificationUserSettingEntity;
import com.mannschaft.app.gamification.entity.PointRuleEntity;
import com.mannschaft.app.gamification.entity.PointTransactionEntity;
import com.mannschaft.app.gamification.entity.RankingSnapshotEntity;
import org.springframework.stereotype.Component;

/**
 * ゲーミフィケーション用Mapper。Entity → DTO変換を担う。
 */
@Component
public class GamificationMapper {

    /**
     * GamificationConfigEntity → GamificationConfigResponse 変換。
     */
    public GamificationConfigResponse toConfigResponse(GamificationConfigEntity entity) {
        Integer resetMonth = entity.getPointResetMonth() != null
                ? entity.getPointResetMonth().intValue()
                : null;
        return new GamificationConfigResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                Boolean.TRUE.equals(entity.getIsEnabled()),
                Boolean.TRUE.equals(entity.getIsRankingEnabled()),
                entity.getRankingDisplayCount(),
                resetMonth,
                entity.getVersion()
        );
    }

    /**
     * PointRuleEntity → PointRuleResponse 変換。
     */
    public PointRuleResponse toPointRuleResponse(PointRuleEntity entity) {
        return new PointRuleResponse(
                entity.getId(),
                entity.getActionType(),
                entity.getName(),
                entity.getPoints(),
                entity.getDailyLimit(),
                Boolean.TRUE.equals(entity.getIsSystem()),
                Boolean.TRUE.equals(entity.getIsActive()),
                entity.getVersion()
        );
    }

    /**
     * BadgeEntity → BadgeResponse 変換。
     */
    public BadgeResponse toBadgeResponse(BadgeEntity entity) {
        return new BadgeResponse(
                entity.getId(),
                entity.getName(),
                entity.getBadgeType(),
                entity.getConditionType(),
                entity.getConditionValue(),
                entity.getConditionPeriod(),
                entity.getIconEmoji(),
                entity.getIconKey(),
                Boolean.TRUE.equals(entity.getIsSystem()),
                Boolean.TRUE.equals(entity.getIsRepeatable()),
                Boolean.TRUE.equals(entity.getIsActive()),
                entity.getVersion()
        );
    }

    /**
     * PointTransactionEntity → PointTransactionResponse 変換。
     */
    public PointTransactionResponse toTransactionResponse(PointTransactionEntity entity) {
        return new PointTransactionResponse(
                entity.getId(),
                entity.getTransactionType(),
                entity.getPoints(),
                entity.getActionType(),
                entity.getReferenceType(),
                entity.getReferenceId(),
                entity.getEarnedOn(),
                entity.getCreatedAt()
        );
    }

    /**
     * GamificationUserSettingEntity → GamificationUserSettingResponse 変換。
     */
    public GamificationUserSettingResponse toSettingResponse(GamificationUserSettingEntity entity) {
        return new GamificationUserSettingResponse(
                Boolean.TRUE.equals(entity.getShowInRanking()),
                Boolean.TRUE.equals(entity.getShowBadges())
        );
    }

    /**
     * RankingSnapshotEntity → RankingResponse 変換。
     * displayName はスナップショットに含まれないため userId の文字列表現を使用する。
     */
    public RankingResponse toRankingResponse(RankingSnapshotEntity entity) {
        return new RankingResponse(
                entity.getUserId(),
                String.valueOf(entity.getUserId()),
                entity.getTotalPoints(),
                entity.getRankPosition()
        );
    }
}
