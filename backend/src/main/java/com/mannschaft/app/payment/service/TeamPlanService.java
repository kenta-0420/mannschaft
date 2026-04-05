package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.repository.TeamSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * チームプラン状態確認サービス。
 * 有料プラン加入判定を一元管理し、モジュール有効化等の各サービスから参照する。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamPlanService {

    private final TeamSubscriptionRepository teamSubscriptionRepository;

    /**
     * チームが有料プランに加入しているか判定する。
     * Valkey キャッシュで高速化する。
     *
     * @param teamId チームID
     * @return 有料プラン加入中なら true
     */
    @Cacheable(value = "teamPlan", key = "#teamId")
    public boolean hasPaidPlan(Long teamId) {
        return teamSubscriptionRepository.hasActivePaidPlan(teamId);
    }
}
