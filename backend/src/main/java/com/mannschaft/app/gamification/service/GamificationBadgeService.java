package com.mannschaft.app.gamification.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.gamification.event.BadgeAwardedEvent;
import com.mannschaft.app.gamification.AwardedBy;
import com.mannschaft.app.gamification.BadgeConditionType;
import com.mannschaft.app.gamification.BadgeType;
import com.mannschaft.app.gamification.GamificationErrorCode;
import com.mannschaft.app.gamification.entity.BadgeEntity;
import com.mannschaft.app.gamification.entity.UserBadgeEntity;
import com.mannschaft.app.gamification.repository.BadgeRepository;
import com.mannschaft.app.gamification.repository.UserBadgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * ゲーミフィケーション・バッジサービス。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GamificationBadgeService {

    private final BadgeRepository badgeRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final DomainEventPublisher domainEventPublisher;

    /**
     * スコープのバッジ一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return バッジ一覧
     */
    public ApiResponse<List<BadgeEntity>> getBadges(String scopeType, Long scopeId) {
        List<BadgeEntity> badges =
                badgeRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
                        scopeType, scopeId);
        return ApiResponse.of(badges);
    }

    /**
     * バッジを作成する。
     *
     * @param scopeType       スコープ種別
     * @param scopeId         スコープID
     * @param name            バッジ名
     * @param badgeType       バッジ種別
     * @param conditionType   獲得条件種別
     * @param conditionValue  獲得条件値
     * @param conditionPeriod 獲得条件期間
     * @param iconEmoji       アイコン絵文字
     * @param isRepeatable    繰り返し獲得可否
     * @return 作成されたバッジ
     */
    @Transactional
    public ApiResponse<BadgeEntity> createBadge(
            String scopeType, Long scopeId, String name,
            BadgeType badgeType, BadgeConditionType conditionType,
            Integer conditionValue, String conditionPeriod,
            String iconEmoji, boolean isRepeatable) {

        BadgeEntity badge = BadgeEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(name)
                .badgeType(badgeType)
                .conditionType(conditionType)
                .conditionValue(conditionValue)
                .conditionPeriod(conditionPeriod)
                .iconEmoji(iconEmoji)
                .isSystem(false)
                .isRepeatable(isRepeatable)
                .isActive(true)
                .build();

        BadgeEntity saved = badgeRepository.save(badge);
        return ApiResponse.of(saved);
    }

    /**
     * バッジ更新パラメータ。
     */
    public record UpdateBadgeParams(
            String name,
            Integer conditionValue,
            String conditionPeriod,
            String iconEmoji,
            String iconKey,
            Boolean isRepeatable,
            Boolean isActive,
            Long version
    ) {}

    /**
     * バッジを更新する。
     *
     * @param id        バッジID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param params    更新パラメータ
     * @return 更新後のバッジ
     */
    @Transactional
    public ApiResponse<BadgeEntity> updateBadge(
            Long id, String scopeType, Long scopeId, UpdateBadgeParams params) {

        BadgeEntity badge = badgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(GamificationErrorCode.GAMIFICATION_003));

        if (!badge.getScopeType().equals(scopeType) || !badge.getScopeId().equals(scopeId)) {
            throw new BusinessException(GamificationErrorCode.GAMIFICATION_008);
        }

        if (params.version() == null || !params.version().equals(badge.getVersion())) {
            throw new BusinessException(GamificationErrorCode.GAMIFICATION_006);
        }

        badge.update(
                params.name(),
                params.conditionValue(),
                params.conditionPeriod(),
                params.iconEmoji(),
                params.iconKey(),
                params.isRepeatable(),
                params.isActive()
        );

        BadgeEntity saved = badgeRepository.save(badge);
        return ApiResponse.of(saved);
    }

    /**
     * バッジを論理削除する。
     * isSystem=trueの場合は403（GAMIFICATION_004）。
     *
     * @param id        バッジID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     */
    @Transactional
    public void deleteBadge(Long id, String scopeType, Long scopeId) {
        BadgeEntity badge = badgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(GamificationErrorCode.GAMIFICATION_003));

        if (!badge.getScopeType().equals(scopeType) || !badge.getScopeId().equals(scopeId)) {
            throw new BusinessException(GamificationErrorCode.GAMIFICATION_008);
        }

        if (Boolean.TRUE.equals(badge.getIsSystem())) {
            throw new BusinessException(GamificationErrorCode.GAMIFICATION_004);
        }

        badge.softDelete();
        badgeRepository.save(badge);
    }

    /**
     * ユーザーが取得済みのバッジ一覧を返す。
     * UserBadgeEntityからbadgeIdを取得し、対応するBadgeEntityを返す。
     *
     * @param userId    対象ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return バッジ一覧
     */
    public ApiResponse<List<BadgeEntity>> getUserBadges(Long userId, String scopeType, Long scopeId) {
        List<Long> badgeIds = userBadgeRepository.findByUserId(userId)
                .stream()
                .map(UserBadgeEntity::getBadgeId)
                .distinct()
                .toList();

        List<BadgeEntity> badges = badgeIds.isEmpty()
                ? List.of()
                : badgeRepository.findAllById(badgeIds).stream()
                        .filter(b -> b.getScopeType().equals(scopeType)
                                && b.getScopeId().equals(scopeId))
                        .toList();

        return ApiResponse.of(badges);
    }

    /**
     * バッジを管理者が手動付与する。
     * UserBadgeEntityをINSERTし、バッジ獲得通知イベントを発行する。
     *
     * @param badgeId   バッジID
     * @param userId    対象ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param adminId   管理者ユーザーID
     */
    @Transactional
    public void awardBadgeManually(
            Long badgeId, Long userId, String scopeType, Long scopeId, Long adminId) {

        BadgeEntity badge = badgeRepository.findById(badgeId)
                .orElseThrow(() -> new BusinessException(GamificationErrorCode.GAMIFICATION_003));

        if (!badge.getScopeType().equals(scopeType) || !badge.getScopeId().equals(scopeId)) {
            throw new BusinessException(GamificationErrorCode.GAMIFICATION_008);
        }

        UserBadgeEntity userBadge = UserBadgeEntity.builder()
                .badgeId(badgeId)
                .userId(userId)
                .earnedOn(LocalDate.now())
                .awardedBy(AwardedBy.ADMIN)
                .build();

        userBadgeRepository.save(userBadge);

        log.info("バッジ手動付与完了: badgeId={}, userId={}, adminId={}", badgeId, userId, adminId);

        // バッジ獲得通知イベントを発行（通知リスナーが後続処理を担当）
        domainEventPublisher.publish(new BadgeAwardedEvent(badgeId, userId, scopeType, scopeId));
    }
}
