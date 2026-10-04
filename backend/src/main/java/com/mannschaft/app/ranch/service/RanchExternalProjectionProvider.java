package com.mannschaft.app.ranch.service;

import lombok.RequiredArgsConstructor;
import com.mannschaft.app.ranch.reward.RanchRewardProjectionReader;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 認可後に既存設定・承認済み商品だけから牧場外の公開状態を確定する。 */
@Service
@RequiredArgsConstructor
public class RanchExternalProjectionProvider {
    private final RanchWidgetVisibilityReader visibility;
    private final RanchShopQueryReader shop;
    private final RanchRewardProjectionReader rewards;

    public RanchStateAssembler.ExternalProjection current(Long userId, Instant serverTime) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(serverTime);
        boolean visible = visibility.visible(userId);
        boolean shopAvailable = !shop.current(userId, serverTime).isEmpty();
        var reward = rewards.current(userId, serverTime);
        // 選定は実resolverと公開gateの接続後だけ表示する。
        return new RanchStateAssembler.ExternalProjection(reward.deliveryPaused(),
                reward.rewardsStatus(), shopAvailable, visible,
                reward.weekBudget(), reward.policyVersion(), List.of());
    }
}
