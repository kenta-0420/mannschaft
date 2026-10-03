package com.mannschaft.app.diagnosis.dto;



/** ranch選定窓口向けの本人結果。恐竜対応が未登録ならmappingはnullのまま。 */
public record OwnedDiagnosisResult(Long ownerUserId, DiagnosisResultSummary savedSummary,
        Long sourceProfileRevision) {}
