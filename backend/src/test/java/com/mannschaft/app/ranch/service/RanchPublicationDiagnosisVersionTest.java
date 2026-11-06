package com.mannschaft.app.ranch.service;

import com.mannschaft.app.diagnosis.service.DiagnosisPublicationReadiness;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 任意の承認catalogの存在で、恐竜masterが要求する未承認版を公開しない。実登録素材の証拠ではない。 */
class RanchPublicationDiagnosisVersionTest {
    @Test void publicationRequiresExactMasterQuestionnaireAndScoringApproval() {
        var registry = mock(RanchProductionMasterRegistry.class);
        var master = mock(RanchProductionMasterRegistry.RegisteredMaster.class);
        var diagnosis = mock(DiagnosisPublicationReadiness.class);
        when(registry.current()).thenReturn(Optional.of(master));
        when(master.version()).thenReturn("synthetic-master");
        when(master.questionnaireVersion()).thenReturn("synthetic-questions");
        when(master.scoringVersion()).thenReturn("signed-centered-v1");
        when(diagnosis.approvedCatalogAvailable()).thenReturn(true);
        var readiness = new RanchPublicationReadiness(registry, diagnosis);
        assertThat(readiness.current().careReady()).isFalse();
        assertThat(readiness.current().reasonCode()).isEqualTo("DIAGNOSIS_UNAPPROVED");
        when(diagnosis.approvedVersionAvailable("synthetic-questions", "signed-centered-v1")).thenReturn(true);
        assertThat(readiness.current().careReady()).isTrue();
        verify(diagnosis, never()).approvedCatalogAvailable();
        verify(diagnosis, times(3)).approvedVersionAvailable("synthetic-questions", "signed-centered-v1");
    }
}
