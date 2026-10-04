package com.mannschaft.app.ranch.service;

import lombok.RequiredArgsConstructor;
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

    public RanchStateAssembler.ExternalProjection current(Long userId, Instant serverTime) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(serverTime);
        boolean visible = visibility.visible(userId);
        boolean shopAvailable = !shop.current(userId, serverTime).isEmpty();
        // source/admin policyと64種素材の公開gate未製造につき報酬と選定は正規にOFF。
        return new RanchStateAssembler.ExternalProjection(false, "DISABLED",
                shopAvailable, visible, null, null, List.of());
    }
}
