package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.dto.ConfirmedBirthNumbers;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.dto.DiagnosisNumberSummary;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.entity.DiagnosisCommandEntity;
import com.mannschaft.app.diagnosis.entity.DiagnosisResultEntity;
import com.mannschaft.app.diagnosis.repository.DiagnosisCommandRepository;
import com.mannschaft.app.diagnosis.repository.DiagnosisResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/** authから派生数と内部版だけを受け、不変出生結果を診断自身のTXで保存する。 */
@Service
@RequiredArgsConstructor
public class DiagnosisBirthResultWriter {
    private final DiagnosisResultRepository results;
    private final DiagnosisCommandRepository commands;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DiagnosisResultSummary create(Long userId, UUID commandId, String requestHash, ConfirmedBirthNumbers confirmed) {
        var replay = commands.findByUserIdAndCommandId(userId, commandId);
        if (replay.isPresent()) {
            if (!MessageDigest.isEqual(requestHash.getBytes(StandardCharsets.US_ASCII),
                    replay.get().getRequestHash().getBytes(StandardCharsets.US_ASCII))) {
                throw new BusinessException(DiagnosisErrorCode.COMMAND_CONFLICT);
            }
            try { return mapper.readValue(replay.get().getResponseSnapshot(), DiagnosisResultSummary.class); }
            catch (JsonProcessingException error) { throw unavailable(); }
        }
        if (confirmed == null || confirmed.profileRevision() < 0 || confirmed.numbers() == null) throw unavailable();
        var numbers = confirmed.numbers();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = UuidV7.generate();
        DiagnosisResultSummary summary = new DiagnosisResultSummary(id, DiagnosisMethod.BIRTH_STYLE, now,
                "diagnosis-result-v1", null, null, numbers.normalizationVersion(), numbers.ruleVersion(), null, null,
                Map.of(), new DiagnosisNumberSummary(numbers.lifePathNumber(), numbers.nameNumber(), numbers.dateSum(), numbers.nameSum()),
                Map.of(
                    "ja", "本人が確認した情報から、固定した桁和の規則で1から9へ還元した結果です。",
                    "en", "These numbers were reduced to 1–9 from your confirmed information using the fixed digit-sum rule.",
                    "zh", "根据您确认的信息，按照固定的数字求和规则，将结果归约为1到9。",
                    "ko", "본인이 확인한 정보를 고정된 자릿수 합산 규칙으로 1부터 9까지 줄인 결과입니다.",
                    "es", "Estos números se redujeron a valores del 1 al 9 a partir de la información que confirmaste, según la regla fija de suma de dígitos.",
                    "de", "Diese Zahlen wurden aus deinen bestätigten Angaben nach der festen Quersummenregel auf Werte von 1 bis 9 reduziert."), Map.of(), null);
        String snapshot;
        try { snapshot = mapper.writeValueAsString(summary); }
        catch (JsonProcessingException error) { throw unavailable(); }
        results.saveAndFlush(DiagnosisResultEntity.builder().id(id).userId(userId).method(DiagnosisMethod.BIRTH_STYLE)
                .sourceProfileRevision(confirmed.profileRevision()).summarySnapshot(snapshot).completedAt(now)
                .createdAt(now).updatedAt(now).build());
        commands.saveAndFlush(DiagnosisCommandEntity.builder().id(UuidV7.generate()).userId(userId).commandId(commandId)
                .requestHash(requestHash).responseSnapshot(snapshot).createdAt(now).updatedAt(now).build());
        return summary;
    }
    private static BusinessException unavailable() { return new BusinessException(DiagnosisErrorCode.UNAVAILABLE); }
}
