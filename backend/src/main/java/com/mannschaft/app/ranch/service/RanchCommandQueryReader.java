package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.CommandResult;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** command IDは必ず本人user IDと組にしてPRIMARYから読む。 */
@Service
@RequiredArgsConstructor
public class RanchCommandQueryReader {
    private final RanchCommandRepository commands;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public CommandResult get(Long userId, UUID commandId) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(commandId);
        var command = commands.findByUserIdAndId(userId, commandId)
                .orElseThrow(() -> new BusinessException(RanchErrorCode.RANCH_001,
                        HttpStatus.NOT_FOUND));
        try {
            return new CommandResult(command.getId(), command.getCommandType(),
                    json.readTree(command.getResultJson()), command.getCompletedAt());
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_008, exception);
        }
    }
}
