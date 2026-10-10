package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchSlotRequest;
import com.mannschaft.app.ranch.dto.RoomSlotSummary;
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

/** 現在resource照合の前に、保存済み枠操作の固定結果だけをPRIMARYから読む。 */
@Service
@RequiredArgsConstructor
public class RanchSlotReplayReader {
    private final RanchCommandRepository commands;
    private final ObjectMapper json;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Optional<RoomSlotSummary> placement(Long userId, UUID key, String slotKey,
                                                RanchSlotRequest request) {
        Objects.requireNonNull(request);
        return saved(userId, key, slotKey, "SLOT_PUT",
                json.valueToTree(request));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Optional<RoomSlotSummary> removal(Long userId, UUID key, String slotKey,
                                              String ifMatchVersion) {
        return saved(userId, key, slotKey, "SLOT_DELETE",
                json.valueToTree(new VersionBody(ifMatchVersion)));
    }

    private Optional<RoomSlotSummary> saved(Long userId, UUID key, String slotKey,
                                             String type, com.fasterxml.jackson.databind.JsonNode body) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(slotKey);
        var command = commands.findByUserIdAndIdempotencyKey(userId, key);
        if (command.isEmpty()) return Optional.empty();
        var row = command.orElseThrow();
        byte[] expected = hasher.hash(type,
                "/api/v1/me/ranch/room/slots/" + slotKey, null, body);
        if (!type.equals(row.getCommandType()) || !Arrays.equals(expected, row.getBodyHash())) {
            throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
        }
        try {
            return Optional.of(json.readValue(row.getResultJson(), RoomSlotSummary.class));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_008, exception);
        }
    }

    private record VersionBody(String version) { }
}
