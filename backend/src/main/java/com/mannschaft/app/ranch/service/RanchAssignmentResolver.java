package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.AssignmentMethod;
import com.mannschaft.app.ranch.Habitat;
import com.mannschaft.app.ranch.dto.RanchAssignmentRequest;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.auth.service.BirthStyleCalculator.BirthNumbers;
import java.util.UUID;

/** 出生選定の境界。診断側の確定済み結果は後続アダプターから不透明な根拠のみ取得する。 */
public interface RanchAssignmentResolver {
    Selection resolve(Long userId, RanchAssignmentRequest request,
                      DiagnosisResultSummary savedResult, BirthNumbers confirmedBirth);

    record Selection(AssignmentMethod method, Habitat habitat, String speciesKey,
                     String variantKey, long catalogVersion, String ruleVersion,
                     UUID resultId, String inputHash) { }
}
