package com.mannschaft.app.diagnosis.service;

import com.mannschaft.app.diagnosis.dto.OwnedDiagnosisResult;
import org.springframework.stereotype.Service;

/** 非TX操作入口で保存済み出生結果と現在確認版を照合する。未実装の先行試練用骨格。 */
@Service
public class DiagnosisBirthResultBindingService {
    public void requireSourceProfileRevision(OwnedDiagnosisResult result, long confirmedProfileRevision) {
        throw new UnsupportedOperationException("出生結果のプロフィール版束縛は未実装");
    }
}
