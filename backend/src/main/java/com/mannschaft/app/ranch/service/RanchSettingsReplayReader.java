package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchSettings;
import com.mannschaft.app.ranch.dto.RanchSettingsRequest;
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

/** 現在dashboard投影前に保存済み設定応答だけをPRIMARYから復元する。 */
@Service
@RequiredArgsConstructor
public class RanchSettingsReplayReader {
    private static final String PATH = "/api/v1/me/ranch/settings";
    private final RanchCommandRepository commands;
    private final ObjectMapper json;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Optional<RanchSettings> saved(Long userId, UUID key, RanchSettingsRequest request) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(request);
        var command = commands.findByUserIdAndIdempotencyKey(userId, key);
        if (command.isEmpty()) return Optional.empty();
        var row = command.orElseThrow();
        byte[] expected = hasher.hash("SETTINGS", PATH, null, json.valueToTree(request));
        if (!"SETTINGS".equals(row.getCommandType())
                || !Arrays.equals(expected, row.getBodyHash())) {
            throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
        }
        try {
            return Optional.of(json.readValue(row.getResultJson(), RanchSettings.class));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_008, exception);
        }
    }
}
