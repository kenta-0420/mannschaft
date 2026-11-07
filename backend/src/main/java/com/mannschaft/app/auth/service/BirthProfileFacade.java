package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.auth.dto.BirthProfileConfirmationResponse;
import com.mannschaft.app.auth.dto.BirthProfileResponse;
import com.mannschaft.app.auth.dto.BirthProfileUpdateRequest;
import com.mannschaft.app.auth.dto.BirthProfileUpdateResponse;
import com.mannschaft.app.auth.dto.ConfirmedBirthNumbers;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** 非TX出生入口。同singleton受付から出生Runnerへ進み、generic Guardへ再入しない。 */
@Service
@RequiredArgsConstructor
public class BirthProfileFacade {
    private final UserOperationAdmission admission;
    private final BirthProfileRunner runner;

    public BirthProfileResponse read(Long userId) { return execute(() -> runner.read(userId)); }
    public BirthProfileUpdateResponse update(Long userId, UUID commandId, BirthProfileUpdateRequest request) {
        return execute(() -> runner.update(userId, commandId, request));
    }
    public BirthProfileConfirmationResponse confirm(Long userId, UUID commandId, long revision, boolean useConfirmed) {
        return execute(() -> runner.confirm(userId, commandId, revision, useConfirmed));
    }
    public <T> T withConfirmedBirthProfile(Long userId, UUID ref, Supplier<Optional<T>> successfulReplay,
            Function<ConfirmedBirthNumbers, T> initialWrite) {
        return execute(() -> runner.withConfirmedBirthProfile(userId, ref, successfulReplay, initialWrite));
    }
    private <T> T execute(Supplier<T> operation) {
        try { return admission.execute(operation); }
        catch (UserOperationAdmission.Rejected error) { throw new BusinessException(UserOperationErrorCode.UNAVAILABLE); }
    }
}
