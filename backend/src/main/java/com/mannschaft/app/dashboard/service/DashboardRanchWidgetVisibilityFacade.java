package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.WidgetKey;
import com.mannschaft.app.dashboard.entity.DashboardWidgetSettingEntity;
import com.mannschaft.app.dashboard.repository.DashboardWidgetSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** 認証users lockと別のPRIMARY取引で本人widget可視値だけを公開する。 */
@Service
@RequiredArgsConstructor
public class DashboardRanchWidgetVisibilityFacade {
    private final DashboardWidgetSettingRepository settings;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Optional<Boolean> visible(Long userId) {
        Objects.requireNonNull(userId);
        WidgetKey key = WidgetKey.PERSONAL_DINOSAUR_RANCH;
        // Ranch widgetはモジュール依存なし。保存値がない場合は正規default=falseを返す。
        boolean value = settings.findByUserIdAndScopeTypeAndScopeIdAndWidgetKey(
                        userId, ScopeType.PERSONAL, 0L, key.name())
                .map(DashboardWidgetSettingEntity::getIsVisible)
                .orElse(key.isDefaultVisible());
        return Optional.of(value);
    }
}
