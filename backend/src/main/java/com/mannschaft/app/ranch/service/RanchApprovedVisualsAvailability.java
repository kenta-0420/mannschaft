package com.mannschaft.app.ranch.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 素材登録と独立した明示公開許可。育成・報酬・本人の表示設定を公開承認に読み替えない。 */
@Service
public class RanchApprovedVisualsAvailability {
    private final boolean published;
    private final RanchPublicationReadiness readiness;

    public RanchApprovedVisualsAvailability(
            @Value("${mannschaft.ranch.approved-visuals-available:false}") boolean published,
            RanchPublicationReadiness readiness) {
        this.published = published;
        this.readiness = readiness;
    }

    public boolean isAvailable() {
        return published && readiness.current().careReady();
    }
}
