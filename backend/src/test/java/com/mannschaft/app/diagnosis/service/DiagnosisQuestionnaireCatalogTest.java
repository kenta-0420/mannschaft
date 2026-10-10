package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 標準Environment/実catalogで未承認masterのprofile境界を検証する。認可・DB証拠ではない。 */
class DiagnosisQuestionnaireCatalogTest {
    @Test void 新版は六軸一巡の24問で旧版の軸極性と採点を保持する() throws Exception {
        var mapper = new ObjectMapper();
        var environment = new MockEnvironment(); environment.setActiveProfiles("test");
        var current = new DiagnosisQuestionnaireCatalog(mapper, environment).forStart();
        var legacy = com.mannschaft.app.diagnosis.DiagnosisQuestionnaireTestFixture.legacy(mapper);
        assertThat(current.questionnaireVersion()).isEqualTo("draft-20261010-v2");
        assertThat(current.scoringVersion()).isEqualTo(legacy.scoringVersion());
        assertThat(current.approval()).isEqualTo(DiagnosisQuestionnaireCatalog.SnapshotApproval.DRAFT);
        assertThat(current.questions()).extracting(com.mannschaft.app.diagnosis.dto.DiagnosisQuestion::id)
                .containsExactly("Q01", "Q05", "Q09", "Q13", "Q17", "Q21",
                        "Q02", "Q06", "Q10", "Q14", "Q18", "Q22",
                        "Q03", "Q07", "Q11", "Q15", "Q19", "Q23",
                        "Q04", "Q08", "Q12", "Q16", "Q20", "Q24");
        for (int round = 0; round < 4; round++) {
            assertThat(current.questions().subList(round * 6, round * 6 + 6))
                    .extracting(com.mannschaft.app.diagnosis.dto.DiagnosisQuestion::axis)
                    .containsExactly(com.mannschaft.app.diagnosis.DiagnosisAxis.values());
        }
        var answers = new java.util.HashMap<String, Integer>();
        for (var question : current.questions()) {
            var old = legacy.questions().stream().filter(value -> value.id().equals(question.id())).findFirst().orElseThrow();
            assertThat(question.axis()).isEqualTo(old.axis());
            assertThat(question.polarity()).isEqualTo(old.polarity());
            assertThat(question.text().keySet()).containsExactlyInAnyOrder("ja", "en", "zh", "ko", "es", "de");
            assertThat(question.text().values()).doesNotContainNull().allMatch(value -> !value.isBlank());
            for (String locale : java.util.List.of("en", "zh", "ko", "es", "de")) {
                assertThat(question.text().get(locale)).isNotEqualTo(question.text().get("ja"));
            }
            answers.put(question.id(), question.polarity() > 0 ? 5 : 1);
        }
        var scoring = new com.mannschaft.app.diagnosis.DiagnosisScoringService();
        assertThat(scoring.score(current.questions(), answers, java.util.Map.of()))
                .isEqualTo(scoring.score(legacy.questions(), answers, java.util.Map.of()));
        assertThat(scoring.score(current.questions(), answers, java.util.Map.of()).typeCode()).isEqualTo("111111");
        answers.replaceAll((id, value) -> 3);
        var neutral = scoring.score(current.questions(), answers, java.util.Map.of());
        assertThat(neutral.typeCode()).isNull();
        assertThat(neutral.tiedAxes()).containsExactly(com.mannschaft.app.diagnosis.DiagnosisAxis.values());
        assertThat(current.descriptionSnapshot()).isEqualTo(legacy.descriptionSnapshot());
        assertThat(current.questions().getFirst().text().get("ja")).startsWith("食べ物や飲み物を選ぶなら");
    }

    @Test void 現在版が新版でも保存済み旧新版は読めるが未登録draft版は拒否する() throws Exception {
        var mapper = new ObjectMapper();
        var environment = new MockEnvironment(); environment.setActiveProfiles("test");
        var catalog = new DiagnosisQuestionnaireCatalog(mapper, environment);
        var legacy = com.mannschaft.app.diagnosis.DiagnosisQuestionnaireTestFixture.legacy(mapper);
        var current = catalog.forStart();
        assertThat(current.questionnaireVersion()).isEqualTo("draft-20261010-v2");
        var codec = new DiagnosisSessionSnapshotCodec(mapper);
        environment.setActiveProfiles("production");
        for (var saved : java.util.List.of(legacy, current)) {
            var frozen = codec.definition(codec.encodeDefinition(saved));
            catalog.requireSnapshotReadable(frozen);
            assertThat(frozen).isEqualTo(saved);
            assertThatThrownBy(() -> catalog.requireMutationAllowed(frozen)).isInstanceOf(BusinessException.class);
        }
        var unknown = new DiagnosisQuestionnaireCatalog.Definition(current.snapshotSchemaVersion(), current.approval(),
                "draft-20261011-v3", current.scoringVersion(), current.questions(), current.ties(),
                current.descriptionSnapshot(), current.axisDescriptions());
        assertThatThrownBy(() -> catalog.requireSnapshotReadable(unknown)).isInstanceOf(BusinessException.class);
    }
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
