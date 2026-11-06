package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchLegacySyncResult;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** 源の現在availability照会前に成功命令をPRIMARYから返す。 */
@Service
@RequiredArgsConstructor
public class RanchLegacySyncReplayReader {
    private final RanchCommandRepository commands;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Optional<RanchLegacySyncResult> saved(Long userId, UUID key, byte[] hash) {
        return commands.findByUserIdAndIdempotencyKey(userId, key).map(command -> {
            if (!"LEGACY_SYNC".equals(command.getCommandType())
                    || !Arrays.equals(hash, command.getBodyHash())) {
                throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
            }
            try { return json.readValue(command.getResultJson(), RanchLegacySyncResult.class); }
            catch (JsonProcessingException exception) { throw new IllegalStateException("保存済み取込結果が不正です", exception); }
        });
    }
}
