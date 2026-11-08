package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.service.DashboardRanchWidgetVisibilityFacade;
import com.mannschaft.app.ranch.RanchErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 本人の正式dashboard設定から牧場widgetの可視値を投影する。 */
@Service
@RequiredArgsConstructor
public class RanchWidgetVisibilityReader {
    private final DashboardRanchWidgetVisibilityFacade widgets;

    public boolean visible(Long userId) {
        Objects.requireNonNull(userId);
        return widgets.visible(userId)
                .orElseThrow(() -> new BusinessException(RanchErrorCode.RANCH_008,
                        HttpStatus.INTERNAL_SERVER_ERROR));
    }
}
