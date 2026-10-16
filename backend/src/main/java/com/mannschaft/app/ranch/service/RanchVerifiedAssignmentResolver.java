package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.AssignmentMethod;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.Habitat;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.RenderStyle;
import com.mannschaft.app.ranch.dto.RanchAssignmentRequest;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.auth.service.BirthStyleCalculator.BirthNumbers;
import com.mannschaft.app.diagnosis.service.DiagnosisPublicationReadiness;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 承認済み候補のみ選ぶ。開発候補は明示fixtureのLAND一組に限定する。 */
@Service
@RequiredArgsConstructor
public class RanchVerifiedAssignmentResolver implements RanchAssignmentResolver {
    private final RanchDevelopmentVisualCatalog development;
    private final RanchProductionMasterRegistry production;
    private final DiagnosisPublicationReadiness diagnosisPublication;
    private final RanchRuleProvider rules;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Override
    public Selection resolve(Long userId, RanchAssignmentRequest request,
                             DiagnosisResultSummary savedResult, BirthNumbers confirmedBirth) {
        var master = production.current();
        if (master.isPresent()) {
            return resolveApproved(request, savedResult, confirmedBirth,
                    master.orElseThrow());
        }
        if (request.method() != AssignmentMethod.HABITAT_RANDOM
                || request.habitat() != Habitat.LAND) throw unavailable();
        var visual = development.find(RanchDevelopmentVisualCatalog.CATALOG_VERSION,
                RanchDevelopmentVisualCatalog.SPECIES_KEY,
                RanchDevelopmentVisualCatalog.VARIANT_KEY, DinosaurStage.BABY,
                RenderStyle.PIXEL);
        var rule = rules.currentCareRule(Instant.now(clock));
        if (visual.isEmpty() || rule.isEmpty()) throw unavailable();
        String basis = "HABITAT_RANDOM|LAND|" + RanchDevelopmentVisualCatalog.CATALOG_VERSION
                + "|" + RanchDevelopmentVisualCatalog.SPECIES_KEY
                + "|" + RanchDevelopmentVisualCatalog.VARIANT_KEY;
        return new Selection(AssignmentMethod.HABITAT_RANDOM, Habitat.LAND,
                RanchDevelopmentVisualCatalog.SPECIES_KEY,
                RanchDevelopmentVisualCatalog.VARIANT_KEY,
                RanchDevelopmentVisualCatalog.CATALOG_VERSION, rule.orElseThrow().ruleVersion(),
                null, sha256(basis));
    }

    public List<AssignmentMethod> availableMethods() {
        if (rules.currentCareRule(Instant.now(clock)).isEmpty()) return List.of();
        if (production.current().isPresent()) {
            var master = production.current().orElseThrow();
            return diagnosisPublication.approvedVersionAvailable(master.questionnaireVersion(), master.scoringVersion())
                    ? List.of(AssignmentMethod.HABITAT_RANDOM,
                            AssignmentMethod.DIAGNOSIS, AssignmentMethod.BIRTH_STYLE)
                    : List.of(AssignmentMethod.HABITAT_RANDOM, AssignmentMethod.BIRTH_STYLE);
        }
        return development.find(RanchDevelopmentVisualCatalog.CATALOG_VERSION,
                RanchDevelopmentVisualCatalog.SPECIES_KEY,
                RanchDevelopmentVisualCatalog.VARIANT_KEY, DinosaurStage.BABY,
                RenderStyle.PIXEL).isPresent()
                && rules.currentCareRule(Instant.now(clock)).isPresent()
                ? List.of(AssignmentMethod.HABITAT_RANDOM) : List.of();
    }

    Selection resolveApproved(RanchAssignmentRequest request,
            DiagnosisResultSummary savedResult, BirthNumbers confirmedBirth,
            RanchProductionMasterRegistry.RegisteredMaster master) {
        var careRule = rules.currentCareRule(Instant.now(clock)).orElseThrow(this::unavailable);
        RanchProductionMasterRegistry.Pair pair;
        String basis;
        if (request.method() == AssignmentMethod.DIAGNOSIS) {
            if (!diagnosisPublication.approvedVersionAvailable(master.questionnaireVersion(), master.scoringVersion())
                    || savedResult == null || savedResult.method() != DiagnosisMethod.DIAGNOSIS
                    || savedResult.typeCode() == null
                    || !Objects.equals(savedResult.id(), request.resultId())
                    || !Objects.equals(savedResult.resultSchemaVersion(), master.resultSchemaVersion())
                    || !Objects.equals(savedResult.questionnaireVersion(), master.questionnaireVersion())
                    || !Objects.equals(savedResult.scoringVersion(), master.scoringVersion())
                    || (savedResult.mappingVersion() != null
                            && !savedResult.mappingVersion().equals(master.version()))) throw unavailable();
            pair = master.diagnosis(savedResult.typeCode()).orElseThrow(this::unavailable);
            basis = "DIAGNOSIS|" + request.resultId() + "|" + master.version()
                    + "|" + savedResult.typeCode();
        } else if (request.method() == AssignmentMethod.BIRTH_STYLE) {
            if (savedResult == null || savedResult.method() != DiagnosisMethod.BIRTH_STYLE
                    || confirmedBirth == null
                    || !Objects.equals(savedResult.id(), request.resultId())
                    || !Objects.equals(savedResult.resultSchemaVersion(), master.resultSchemaVersion())
                    || !Objects.equals(savedResult.normalizationVersion(), master.birthNormalizationVersion())
                    || !Objects.equals(savedResult.ruleVersion(), master.birthRuleVersion())
                    || !Objects.equals(confirmedBirth.normalizationVersion(), master.birthNormalizationVersion())
                    || !Objects.equals(confirmedBirth.ruleVersion(), master.birthRuleVersion())
                    || savedResult.numberSummary() == null
                    || savedResult.numberSummary().lifePathNumber() != confirmedBirth.lifePathNumber()
                    || savedResult.numberSummary().nameNumber() != confirmedBirth.nameNumber()
                    || savedResult.numberSummary().dateSum() != confirmedBirth.dateSum()
                    || savedResult.numberSummary().nameSum() != confirmedBirth.nameSum()
                    || (savedResult.mappingVersion() != null
                            && !savedResult.mappingVersion().equals(master.version()))) throw unavailable();
            pair = master.birth(confirmedBirth.lifePathNumber(), confirmedBirth.nameNumber())
                    .orElseThrow(this::unavailable);
            basis = "BIRTH_STYLE|" + request.resultId() + "|" + master.version()
                    + "|" + confirmedBirth.lifePathNumber() + "|" + confirmedBirth.nameNumber();
        } else if (request.method() == AssignmentMethod.HABITAT_RANDOM) {
            if (request.habitat() == null) throw unavailable();
            pair = master.random(request.habitat().name(), random).orElseThrow(this::unavailable);
            basis = "HABITAT_RANDOM|" + request.habitat() + "|" + master.version()
                    + "|" + pair.speciesKey() + "|" + pair.variantKey();
        } else {
            throw unavailable();
        }
        // 個人の原情報・診断本文を保存せず、凍結済み結果IDと承認版だけを記録する。
        Habitat habitat = Habitat.valueOf(master.habitat(pair).orElseThrow(this::unavailable));
        return new Selection(request.method(), habitat, pair.speciesKey(),
                pair.variantKey(), master.catalogVersion(), careRule.ruleVersion(),
                request.resultId(), sha256(basis));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256が利用できません", impossible);
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(RanchErrorCode.RANCH_004, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
