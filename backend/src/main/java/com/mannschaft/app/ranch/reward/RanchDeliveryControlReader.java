package com.mannschaft.app.ranch.reward;

import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 配送の運営停止を Ranch 所有の独立した PRIMARY 読取で確定する。 */
@Service
@RequiredArgsConstructor
public class RanchDeliveryControlReader {
    private final RanchOperationalControlRepository controls;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public boolean paused() {
        return controls.findById(1).orElseThrow(
                () -> new IllegalStateException("牧場運営制御の初期行がありません"))
                .isDeliveryPaused();
    }
}
