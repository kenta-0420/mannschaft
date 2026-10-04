package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.WidgetKey;
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
    private final DashboardWidgetService widgets;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Optional<Boolean> visible(Long userId) {
        Objects.requireNonNull(userId);
        return widgets.getWidgetSettings(userId, ScopeType.PERSONAL, 0L, false).stream()
                .filter(setting -> WidgetKey.PERSONAL_DINOSAUR_RANCH.name()
                        .equals(setting.getWidgetKey()))
                .findFirst()
                .map(setting -> setting.isVisible() && setting.isModuleEnabled());
    }
}
