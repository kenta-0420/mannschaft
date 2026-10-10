package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.entity.RanchAdminCommandEntity;
import com.mannschaft.app.ranch.repository.RanchAdminCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** 非TX preflightより先に保存ACKを照会する。保存側も行lock後に再照会する。 */
@Service
@RequiredArgsConstructor
public class RanchAdminCommandReplayReader {
    private final RanchAdminCommandRepository commands;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public <T> Optional<T> read(Long actorId, UUID key, String kind, byte[] hash, Class<T> type) {
        return commands.findByActorUserIdAndIdempotencyKey(actorId, key)
                .map(command -> decode(command, kind, hash, type, json));
    }

    static <T> T decode(RanchAdminCommandEntity command, String kind, byte[] hash,
                        Class<T> type, ObjectMapper json) {
        if (!kind.equals(command.getCommandType()) || !Arrays.equals(hash, command.getBodyHash())) {
            throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
        }
        try { return json.readValue(command.getResultJson(), type); }
        catch (JsonProcessingException invalid) { throw new BusinessException(RanchErrorCode.RANCH_008, invalid); }
    }
}
