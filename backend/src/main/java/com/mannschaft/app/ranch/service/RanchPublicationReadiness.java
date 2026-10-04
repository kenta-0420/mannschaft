package com.mannschaft.app.ranch.service;

import com.mannschaft.app.diagnosis.service.DiagnosisPublicationReadiness;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 管理公開前にRanchの完全masterと独立した診断承認版を順次確認する非TX境界。 */
@Service
@RequiredArgsConstructor
public class RanchPublicationReadiness {
    private final RanchProductionMasterRegistry master;
    private final DiagnosisPublicationReadiness diagnosis;

    public Snapshot current() {
        var registered = master.current();
        if (registered.isEmpty()) {
            return new Snapshot(null, false, "MASTER_UNREGISTERED");
        }
        String version = registered.orElseThrow().version();
        if (!diagnosis.approvedCatalogAvailable()) {
            return new Snapshot(version, false, "DIAGNOSIS_UNAPPROVED");
        }
        return new Snapshot(version, true, "READY");
    }

    public record Snapshot(String masterVersion, boolean careReady, String reasonCode) { }
}
