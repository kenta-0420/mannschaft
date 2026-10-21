package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.DiagnosisStatus;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.dto.OwnedDiagnosisResult;
import com.mannschaft.app.diagnosis.repository.DiagnosisResultRepository;
import com.mannschaft.app.diagnosis.repository.DiagnosisSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

/** 本人所有条件をSQLに含め、不変結果だけを独立PRIMARY TXで参照する。 */
@Service
@RequiredArgsConstructor
public class DiagnosisResultReadFacade {
    private final DiagnosisResultRepository results;
    private final ObjectMapper mapper;
    private final DiagnosisSessionRepository sessions;
    private final DiagnosisSessionSnapshotCodec codec;

    // SELECTだけだが、ReplicaRoutingAspectの既存規則に従ってPRIMARYを選ぶ。
    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
    public Optional<OwnedDiagnosisResult> findOwnedSummary(Long userId, UUID resultId) {
        if (userId == null || resultId == null) return Optional.empty();
        return results.findByIdAndUserId(resultId, userId).map(result -> {
            try {
                DiagnosisResultSummary summary = mapper.readValue(result.getSummarySnapshot(), DiagnosisResultSummary.class);
                if (!result.getId().equals(summary.id()) || result.getMethod() != summary.method()) {
                    throw new BusinessException(DiagnosisErrorCode.UNAVAILABLE);
                }
                if (DiagnosisAxisSelectionSnapshot.needsSupplement(summary)) {
                    // 本人結果の所有を確認した後、同じ本人・同じ完成結果の凍結定義だけを読む。
                    var source = sessions.findByResultIdAndUserId(resultId, userId)
                            .filter(session -> session.getStatus() == DiagnosisStatus.COMPLETED);
                    if (source.isPresent()) {
                        summary = DiagnosisAxisSelectionSnapshot.supplement(summary,
                                codec.definition(source.get().getQuestionsSnapshot()));
                    }
                }
                return new OwnedDiagnosisResult(result.getUserId(), summary, result.getSourceProfileRevision());
            } catch (JsonProcessingException error) {
                // 保存JSONやパース例外をcauseへ複製しない。
                throw new BusinessException(DiagnosisErrorCode.UNAVAILABLE);
            }
        });
    }
}
