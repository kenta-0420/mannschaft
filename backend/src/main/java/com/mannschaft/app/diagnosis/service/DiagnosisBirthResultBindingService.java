package com.mannschaft.app.diagnosis.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.dto.OwnedDiagnosisResult;
import org.springframework.stereotype.Service;

/** 保存済み出生結果の計算元版と本人確認版を照合する。原情報は受け取らない。 */
@Service
public class DiagnosisBirthResultBindingService {
    public void requireSourceProfileRevision(OwnedDiagnosisResult result, long confirmedProfileRevision) {
        if (result == null || result.savedSummary() == null
                || result.savedSummary().method() != DiagnosisMethod.BIRTH_STYLE
                || result.sourceProfileRevision() == null || result.sourceProfileRevision() < 0
                || confirmedProfileRevision < 0 || result.sourceProfileRevision() != confirmedProfileRevision) {
            throw new BusinessException(DiagnosisErrorCode.STATE_CONFLICT);
        }
    }
}
