package com.mannschaft.app.diagnosis.service;

import com.mannschaft.app.auth.service.BirthProfileFacade;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.dto.DiagnosisSessionResponse;
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
    // 最終候補ではDTO/Listの明示importと@RequiredArgsConstructorの新fieldを追加する。
    private final DiagnosisSessionWriter sessionWriter;

    public DiagnosisSessionResponse startSession(Long userId, UUID key) {
        return users.withActiveUser(userId, () -> sessionWriter.start(userId,key));
    }
    public DiagnosisSessionResponse readSession(Long userId, UUID id) {
        return users.withActiveUser(userId, () -> sessionWriter.read(userId,id));
    }
    public DiagnosisSessionResponse readPendingSession(Long userId) {
        return users.withActiveUser(userId, () -> sessionWriter.readPending(userId));
    }
    public DiagnosisSessionResponse answerSession(Long userId, UUID id, UUID key, DiagnosisSessionInputParser.Answers request) {
        return users.withActiveUser(userId, () -> sessionWriter.answer(userId,id,key,request.version(),request.answers()));
    }
    public DiagnosisSessionResponse completeSession(Long userId, UUID id, UUID key, DiagnosisSessionInputParser.Complete request) {
        return users.withActiveUser(userId, () -> sessionWriter.complete(userId,id,key,request.version(),request.answerRevision(),request.ties()));
    }
    public DiagnosisSessionResponse cancelSession(Long userId, UUID id, UUID key, long version) {
        return users.withActiveUser(userId, () -> sessionWriter.cancel(userId,id,key,version));
    }

}
