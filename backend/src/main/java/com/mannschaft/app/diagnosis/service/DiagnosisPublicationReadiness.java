package com.mannschaft.app.diagnosis.service;

import org.springframework.stereotype.Service;

/** 承認済み公開catalogの可用性。開発fixtureや保存結果を公開承認へ読み替えない。 */
@Service
public class DiagnosisPublicationReadiness {
    /** 承認済みcatalog機構が未登録の間は、環境profileに関係なく公開不可を返す。 */
    public boolean approvedCatalogAvailable() {
        return false;
    }
}
