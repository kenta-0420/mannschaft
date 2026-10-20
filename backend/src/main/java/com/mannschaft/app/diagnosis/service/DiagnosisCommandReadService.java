package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.repository.DiagnosisCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;

/** 成功命令の同一本文を確認してから保存結果をPRIMARYで返す。 */
@Service
@RequiredArgsConstructor
public class DiagnosisCommandReadService {
    private final DiagnosisCommandRepository commands;
    private final ObjectMapper mapper;

    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
    public Optional<DiagnosisResultSummary> findBirthResult(Long userId, UUID commandId, String requestHash) {
        return commands.findByUserIdAndCommandId(userId, commandId).map(command -> {
            if (requestHash == null || !MessageDigest.isEqual(requestHash.getBytes(StandardCharsets.US_ASCII),
                    command.getRequestHash().getBytes(StandardCharsets.US_ASCII))) {
                throw new BusinessException(DiagnosisErrorCode.COMMAND_CONFLICT);
            }
            try { return mapper.readValue(command.getResponseSnapshot(), DiagnosisResultSummary.class); }
            catch (JsonProcessingException error) { throw new BusinessException(DiagnosisErrorCode.UNAVAILABLE); }
        });
    }
}
