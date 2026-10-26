package com.mannschaft.app.gamification.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gamification.ActionType;
import com.mannschaft.app.gamification.GamificationErrorCode;
import com.mannschaft.app.gamification.entity.PointRuleEntity;
import com.mannschaft.app.gamification.repository.PointRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ゲーミフィケーション・ポイントルールサービス。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GamificationPointRuleService {

    private final PointRuleRepository pointRuleRepository;

    /**
     * スコープのポイントルール一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return ポイントルール一覧
     */
    public ApiResponse<List<PointRuleEntity>> getRules(String scopeType, Long scopeId) {
        List<PointRuleEntity> rules =
                pointRuleRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
                        scopeType, scopeId);
        return ApiResponse.of(rules);
    }

    /**
     * ポイントルールを作成する。
     * 同一スコープ・actionType・deleted_at IS NULL の重複チェックを行う。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param actionType アクション種別
     * @param name       ルール名
     * @param points     ポイント数
     * @param dailyLimit 1日の付与上限（0=無制限）
     * @return 作成されたポイントルール
     */
    @Transactional
    public ApiResponse<PointRuleEntity> createRule(
            String scopeType, Long scopeId, ActionType actionType,
            String name, int points, int dailyLimit) {

        // 重複チェック
        boolean exists = pointRuleRepository
                .findByScopeTypeAndScopeIdAndActionTypeAndIsActiveTrueAndDeletedAtIsNull(
                        scopeType, scopeId, actionType)
                .isPresent();
        if (exists) {
            throw new BusinessException(GamificationErrorCode.GAMIFICATION_007);
        }

        PointRuleEntity rule = PointRuleEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .actionType(actionType)
                .name(name)
                .points(points)
                .dailyLimit(dailyLimit)
                .isSystem(false)
                .isActive(true)
                .build();

        PointRuleEntity saved = pointRuleRepository.save(rule);
        return ApiResponse.of(saved);
    }

    /**
     * ポイントルールを更新する。
     * isSystem=trueのルールはpoints変更不可（dailyLimit・isActiveのみ変更可）。
     *
     * @param id         ポイントルールID
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param points     ポイント数
     * @param dailyLimit 1日の付与上限
     * @param isActive   有効フラグ
     * @param version    楽観的ロックバージョン
     * @return 更新後のポイントルール
     */
    @Transactional
    public ApiResponse<PointRuleEntity> updateRule(
            Long id, String scopeType, Long scopeId,
            int points, int dailyLimit, boolean isActive, Long version) {

        PointRuleEntity rule = pointRuleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(GamificationErrorCode.GAMIFICATION_002));

        if (!rule.getScopeType().equals(scopeType) || !rule.getScopeId().equals(scopeId)) {
            throw new BusinessException(GamificationErrorCode.GAMIFICATION_008);
        }

        if (version == null || !version.equals(rule.getVersion())) {
            throw new BusinessException(GamificationErrorCode.GAMIFICATION_006);
        }

        // isSystem=trueの場合はpoints変更不可
        int effectivePoints = rule.getIsSystem() ? rule.getPoints() : points;

        rule.update(rule.getName(), effectivePoints, dailyLimit, isActive);

        PointRuleEntity saved = pointRuleRepository.save(rule);
        return ApiResponse.of(saved);
    }

    /**
     * ポイントルールを論理削除する。
     * isSystem=trueの場合は403（GAMIFICATION_004）。
     *
     * @param id        ポイントルールID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     */
    @Transactional
    public void deleteRule(Long id, String scopeType, Long scopeId) {
        PointRuleEntity rule = pointRuleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(GamificationErrorCode.GAMIFICATION_002));

        if (!rule.getScopeType().equals(scopeType) || !rule.getScopeId().equals(scopeId)) {
            throw new BusinessException(GamificationErrorCode.GAMIFICATION_008);
        }

        if (Boolean.TRUE.equals(rule.getIsSystem())) {
            throw new BusinessException(GamificationErrorCode.GAMIFICATION_004);
        }

        rule.softDelete();
        pointRuleRepository.save(rule);
    }
}
