package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 標準Environment/実catalogで未承認masterのprofile境界を検証する。認可・DB証拠ではない。 */
class DiagnosisQuestionnaireCatalogTest {
    @Test void productionAlwaysRejectsEvenWithTestAndIsolatedFlag() {
        for (String production : new String[]{"prod","production"}) {
            var environment=new MockEnvironment();environment.setActiveProfiles("test","ranch-isolated",production);
            environment.setProperty("ranch.diagnosis.draft-enabled","true");
            assertThatThrownBy(()->new DiagnosisQuestionnaireCatalog(new ObjectMapper(),environment).draft())
                    .isInstanceOf(BusinessException.class).satisfies(error ->
                        assertThat(((BusinessException)error).getErrorCode().getCode()).isEqualTo("DIAGNOSIS_005"));
        }
    }
    @Test void isolatedRequiresExplicitFixtureFlagAndKeepsMasterImmutable() {
        var environment=new MockEnvironment();environment.setActiveProfiles("ranch-isolated");
        var catalog=new DiagnosisQuestionnaireCatalog(new ObjectMapper(),environment);
        assertThatThrownBy(catalog::draft).isInstanceOf(BusinessException.class);
        environment.setProperty("ranch.diagnosis.draft-enabled","true");
        var definition=catalog.draft();assertThat(definition.questions()).hasSize(24);assertThat(definition.ties()).hasSize(6);
        assertThat(definition.descriptionSnapshot().keySet()).containsExactlyInAnyOrder("ja","en","zh","ko","es","de");
        assertThatThrownBy(()->definition.descriptionSnapshot().put("ja","changed")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->definition.ties().getFirst().zero().put("ja","changed")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->definition.axisDescriptions().values().iterator().next().put("ja","changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void deepSnapshotCopiesCallerMaps() {
        var environment=new MockEnvironment();environment.setActiveProfiles("test");
        var original=new DiagnosisQuestionnaireCatalog(new ObjectMapper(),environment).draft();
        var inner=new java.util.HashMap<>(original.axisDescriptions().values().iterator().next());
        var axes=new java.util.EnumMap<>(original.axisDescriptions());var axis=axes.keySet().iterator().next();axes.put(axis,inner);
        var explanation=new java.util.HashMap<>(original.descriptionSnapshot());
        var frozen=new DiagnosisQuestionnaireCatalog.Definition(original.snapshotSchemaVersion(),original.approval(),original.questionnaireVersion(),original.scoringVersion(),
                original.questions(),original.ties(),explanation,axes);
        inner.put("ja","changed");explanation.put("ja","changed");
        assertThat(frozen.axisDescriptions().get(axis).get("ja")).isNotEqualTo("changed");
        assertThat(frozen.descriptionSnapshot().get("ja")).isNotEqualTo("changed");
    }
    @Test void savedDraftMutationUsesProfileGateAndRejectsUnknownSnapshotMetadata() {
        var environment=new MockEnvironment();environment.setActiveProfiles("test");
        var catalog=new DiagnosisQuestionnaireCatalog(new ObjectMapper(),environment);
        var saved=catalog.draft();
        assertThat(saved.snapshotSchemaVersion()).isEqualTo(DiagnosisQuestionnaireCatalog.SNAPSHOT_SCHEMA_VERSION);
        assertThat(saved.approval()).isEqualTo(DiagnosisQuestionnaireCatalog.SnapshotApproval.DRAFT);
        for(String profile:new String[]{"prod","production"}) {
            environment.setActiveProfiles("test","ranch-isolated",profile);
            environment.setProperty("ranch.diagnosis.draft-enabled","true");
            assertThatThrownBy(()->catalog.requireMutationAllowed(saved)).isInstanceOf(BusinessException.class);
        }
        environment.setActiveProfiles("test");
        var unknown=new DiagnosisQuestionnaireCatalog.Definition(null,null,saved.questionnaireVersion(),saved.scoringVersion(),
                saved.questions(),saved.ties(),saved.descriptionSnapshot(),saved.axisDescriptions());
        assertThatThrownBy(()->catalog.requireMutationAllowed(unknown)).isInstanceOf(BusinessException.class);
    }
    @Test void approvalFlagAloneAndUnknownSavedVersionsRemainUnavailable() {
        var environment=new MockEnvironment();environment.setActiveProfiles("test");
        var catalog=new DiagnosisQuestionnaireCatalog(new ObjectMapper(),environment);var saved=catalog.draft();
        var approved=new DiagnosisQuestionnaireCatalog.Definition(saved.snapshotSchemaVersion(),DiagnosisQuestionnaireCatalog.SnapshotApproval.APPROVED,
                saved.questionnaireVersion(),saved.scoringVersion(),saved.questions(),saved.ties(),saved.descriptionSnapshot(),saved.axisDescriptions());
        assertThatThrownBy(()->catalog.requireMutationAllowed(approved)).isInstanceOf(BusinessException.class);
        for(int unknown=0;unknown<3;unknown++) {
            var definition=new DiagnosisQuestionnaireCatalog.Definition(unknown==0?"unknown":saved.snapshotSchemaVersion(),saved.approval(),
                    unknown==1?"unknown":saved.questionnaireVersion(),unknown==2?"unknown":saved.scoringVersion(),
                    saved.questions(),saved.ties(),saved.descriptionSnapshot(),saved.axisDescriptions());
            assertThatThrownBy(()->catalog.requireSnapshotReadable(definition)).isInstanceOf(BusinessException.class);
        }
    }
}
