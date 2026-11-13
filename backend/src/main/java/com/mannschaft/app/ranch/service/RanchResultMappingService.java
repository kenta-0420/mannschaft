package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/** 起動時固定mapだけを読み、新しい本人結果へ対応identityと説明を凍結する。DBアクセスは行わない。 */
@Service
@RequiredArgsConstructor
public class RanchResultMappingService {
    private final RanchProductionMasterRegistry registry;

    public DiagnosisResultSummary freeze(DiagnosisResultSummary result) {
        var registered = registry.current();
        // 未登録環境の従来結果作成は維持する。null結果を新規割当へ転用することはresolverが拒否する。
        if (registered.isEmpty()) return result;
        var master = registered.orElseThrow();
        if (!Objects.equals(result.resultSchemaVersion(), master.resultSchemaVersion())) throw unavailable();
        RanchProductionMasterRegistry.Pair pair;
        Map<String,String> description;
        if (result.method() == DiagnosisMethod.DIAGNOSIS) {
            if (!Objects.equals(result.questionnaireVersion(), master.questionnaireVersion())
                    || !Objects.equals(result.scoringVersion(), master.scoringVersion())
                    || result.typeCode() == null) throw unavailable();
            pair = master.diagnosis(result.typeCode()).orElseThrow(RanchResultMappingService::unavailable);
            description = master.diagnosisDescriptions().get(result.typeCode());
        } else if (result.method() == DiagnosisMethod.BIRTH_STYLE) {
            if (!Objects.equals(result.normalizationVersion(), master.birthNormalizationVersion())
                    || !Objects.equals(result.ruleVersion(), master.birthRuleVersion())
                    || result.numberSummary() == null) throw unavailable();
            var numbers = result.numberSummary();
            pair = master.birth(numbers.lifePathNumber(), numbers.nameNumber())
                    .orElseThrow(RanchResultMappingService::unavailable);
            description = master.birthDescriptions().get(new RanchProductionMasterRegistry.BirthPair(
                    numbers.lifePathNumber(), numbers.nameNumber()));
        } else throw unavailable();
        if (description == null || description.size() != 6) throw unavailable();
        return result.withMapping(master.version(), pair.speciesKey(), pair.variantKey(),
                Long.toString(master.catalogVersion()), description);
    }

    private static BusinessException unavailable() {
        return new BusinessException(DiagnosisErrorCode.UNAVAILABLE);
    }
}
