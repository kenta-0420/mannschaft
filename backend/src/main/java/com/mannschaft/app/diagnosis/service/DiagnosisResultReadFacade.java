package com.mannschaft.app.diagnosis.service;

import com.mannschaft.app.diagnosis.dto.OwnedDiagnosisResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

/** 他ドメインの非TX操作入口から、本人所有の保存済み結果だけを参照する。 */
@Service
public class DiagnosisResultReadFacade {
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<OwnedDiagnosisResult> findOwnedSummary(Long userId, UUID resultId) {
        throw new UnsupportedOperationException("本人結果参照は未実装");
    }
}
