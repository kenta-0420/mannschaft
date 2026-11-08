package com.mannschaft.app.diagnosis.service;

import org.springframework.stereotype.Service;

/** 承認済み公開catalogの可用性。開発fixtureや保存結果を公開承認へ読み替えない。 */
@Service
public class DiagnosisPublicationReadiness {
    private final DiagnosisApprovedQuestionnaireRegistry registry;

    public DiagnosisPublicationReadiness(DiagnosisApprovedQuestionnaireRegistry registry) {
        this.registry = registry;
    }

    /** 正式登録の同じ正本だけを公開gateへ渡す。開発profile・保存flagからは承認しない。 */
    public boolean approvedCatalogAvailable() {
        return registry.current().isPresent();
    }

    /** 保存結果と恐竜masterの版照合に加え、当該質問・採点版そのものの承認登録を要求する。 */
    public boolean approvedVersionAvailable(String questionnaireVersion, String scoringVersion) {
        return registry.supports(questionnaireVersion, scoringVersion);
    }
}
