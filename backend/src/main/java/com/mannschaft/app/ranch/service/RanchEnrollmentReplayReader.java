package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchState;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 外部の現在設定を読む前に保存済み登録結果をPRIMARYで復元する。 */
@Service
@RequiredArgsConstructor
public class RanchEnrollmentReplayReader {
    private final RanchCommandRepository commands;
    private final ObjectMapper json;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Optional<RanchEnrollmentWriter.EnrollmentOutcome> saved(Long userId, UUID key) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        var command = commands.findByUserIdAndIdempotencyKey(userId, key);
        if (command.isEmpty()) return Optional.empty();
        var row = command.orElseThrow();
        byte[] expected = hasher.hash("ENROLL", "/api/v1/me/ranch", null,
                json.createObjectNode());
        if (!"ENROLL".equals(row.getCommandType())
                || !Arrays.equals(expected, row.getBodyHash())) {
            throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
        }
        try {
            RanchState state = json.readValue(row.getResultJson(), RanchState.class);
            return Optional.of(new RanchEnrollmentWriter.EnrollmentOutcome(false,
                    state.owner().id(), state.dinosaur().id(), row.getId(), state));
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_008, exception);
        }
    }
}
