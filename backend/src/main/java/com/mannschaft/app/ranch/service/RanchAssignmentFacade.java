package com.mannschaft.app.ranch.service;

import com.mannschaft.app.auth.service.BirthProfileFacade;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.service.DiagnosisResultReadFacade;
import com.mannschaft.app.ranch.AssignmentMethod;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.AssignmentResult;
import com.mannschaft.app.ranch.dto.RanchAssignmentRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 本人ACTIVE境界で成功PRIMARY再送を先に読み、初回だけ外domain根拠を確認する。 */
@Service
@RequiredArgsConstructor
public class RanchAssignmentFacade {
    private final UserOperationGuard guard;
    private final BirthProfileFacade birthProfiles;
    private final DiagnosisResultReadFacade diagnosisResults;
    private final RanchAssignmentWriter writer;
    private final RanchAssignmentResolver resolver;
    private final Clock clock;

    public AssignmentResult assign(Long userId, UUID key, RanchAssignmentRequest request) {
        validateShape(request);
        if (request.method() == AssignmentMethod.BIRTH_STYLE) {
            UUID ref = parseRef(request.confirmationRef());
            return birthProfiles.withConfirmedBirthProfile(userId, ref,
                    () -> writer.savedReplay(userId, key, request), confirmed -> {
                        var owned = diagnosisResults.findOwnedSummary(userId, request.resultId())
                                .orElseThrow(this::missing);
                        if (owned.savedSummary().method() != DiagnosisMethod.BIRTH_STYLE
                                || owned.sourceProfileRevision() == null
                                || owned.sourceProfileRevision() != confirmed.profileRevision()) {
                            throw conflict();
                        }
                        var selection = resolver.resolve(userId, request,
                                owned.savedSummary(), confirmed.numbers());
                        return writer.assign(userId, key, request, selection, now());
                    });
        }
        return guard.withActiveUser(userId, () -> {
            var saved = writer.savedReplay(userId, key, request);
            if (saved.isPresent()) return saved.orElseThrow();
            com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary savedResult = null;
            if (request.method() == AssignmentMethod.DIAGNOSIS) {
                var owned = diagnosisResults.findOwnedSummary(userId, request.resultId())
                        .orElseThrow(this::missing);
                if (owned.savedSummary().method() != DiagnosisMethod.DIAGNOSIS) throw missing();
                savedResult = owned.savedSummary();
            }
            var selection = resolver.resolve(userId, request, savedResult, null);
            return writer.assign(userId, key, request, selection, now());
        });
    }

    private void validateShape(RanchAssignmentRequest request) {
        if (request == null || request.method() == null || request.version() == null) throw badInput();
        switch (request.method()) {
            case HABITAT_RANDOM -> {
                if (request.habitat() == null || request.resultId() != null
                        || request.confirmationRef() != null) throw badInput();
            }
            case DIAGNOSIS -> {
                if (request.habitat() != null || request.resultId() == null
                        || request.confirmationRef() != null) throw badInput();
            }
            case BIRTH_STYLE -> {
                if (request.habitat() != null || request.resultId() == null
                        || request.confirmationRef() == null) throw badInput();
            }
        }
    }

    private UUID parseRef(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException malformed) {
            throw badInput();
        }
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private BusinessException badInput() {
        return new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
    }

    private BusinessException missing() {
        return new BusinessException(RanchErrorCode.RANCH_001, HttpStatus.NOT_FOUND);
    }

    private BusinessException conflict() {
        return new BusinessException(RanchErrorCode.RANCH_007, HttpStatus.CONFLICT);
    }
}
