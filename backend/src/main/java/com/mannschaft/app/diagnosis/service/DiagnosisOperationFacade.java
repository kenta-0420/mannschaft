package com.mannschaft.app.diagnosis.service;

import com.mannschaft.app.auth.service.BirthProfileFacade;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 非TX本人入口。auth受付の外側から独立診断read/writeを順次呼ぶ。 */
@Service
@RequiredArgsConstructor
public class DiagnosisOperationFacade {
    private final UserOperationGuard users;
    private final BirthProfileFacade birthProfiles;
    private final DiagnosisResultReadFacade resultReader;
    private final DiagnosisResultListReader listReader;
    private final DiagnosisCommandReadService commandReader;
    private final DiagnosisBirthResultWriter birthWriter;

    public DiagnosisResultSummary getResult(Long userId, UUID id) {
        return users.withActiveUser(userId, () -> resultReader.findOwnedSummary(userId, id)
                .orElseThrow(() -> new BusinessException(DiagnosisErrorCode.NOT_FOUND)).savedSummary());
    }
    public CursorPagedResponse<DiagnosisResultSummary> listResults(Long userId, DiagnosisMethod method, String cursor, int limit) {
        return users.withActiveUser(userId, () -> listReader.list(userId, method, cursor, limit));
    }
    public DiagnosisResultSummary birthResult(Long userId, UUID commandId, UUID ref) {
        if (commandId == null || ref == null) throw new BusinessException(DiagnosisErrorCode.INVALID_INPUT);
        String hash = hash("birth-style-result:v1|" + ref);
        return birthProfiles.withConfirmedBirthProfile(userId, ref,
                () -> commandReader.findBirthResult(userId, commandId, hash),
                numbers -> birthWriter.create(userId, commandId, hash, numbers));
    }
    private static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("命令比較方式が使用できません"); }
    }
}
