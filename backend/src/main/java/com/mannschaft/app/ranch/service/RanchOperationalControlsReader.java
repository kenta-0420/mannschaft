package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import org.springframework.http.HttpStatus;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchOperationalControlsResponse;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPausePeriodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** admin非TX facadeから独立Ranch PRIMARY読取へ入る。未登録行を作成しない。 */
@Service
@RequiredArgsConstructor
public class RanchOperationalControlsReader {
    private final RanchOperationalControlRepository controls;
    private final RanchRewardPausePeriodRepository pauses;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RanchOperationalControlsResponse read(Instant now) {
        var control = controls.findById(1).orElseThrow(() -> new BusinessException(RanchErrorCode.RANCH_004, HttpStatus.SERVICE_UNAVAILABLE));
        return new RanchOperationalControlsResponse(Long.toString(control.getVersion()), control.isCareEnabled(),
                control.isShopEnabled(), control.isDeliveryPaused(), pauses.includes(now), control.getUpdatedAt());
    }
}
