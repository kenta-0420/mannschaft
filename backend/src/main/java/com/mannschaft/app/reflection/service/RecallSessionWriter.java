package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.reflection.RecallSelfRating;
import com.mannschaft.app.reflection.RecallSessionCommandType;
import com.mannschaft.app.reflection.RecallSessionErrorCode;
import com.mannschaft.app.reflection.RecallSessionStatus;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.ReflectionReminderKind;
import com.mannschaft.app.reflection.ReflectionReminderStatus;
import com.mannschaft.app.reflection.dto.RecallSessionAnswer;
import com.mannschaft.app.reflection.dto.RecallSessionOperationOutcome;
import com.mannschaft.app.reflection.dto.RecallSessionPrompt;
import com.mannschaft.app.reflection.dto.RecallSessionResponse;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.entity.RecallAttemptEntity;
import com.mannschaft.app.reflection.entity.RecallSessionCommandEntity;
import com.mannschaft.app.reflection.entity.RecallSessionEntity;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionSpacedReminderEntity;
import com.mannschaft.app.reflection.repository.RecallAttemptRepository;
import com.mannschaft.app.reflection.repository.RecallSessionCommandRepository;
import com.mannschaft.app.reflection.repository.RecallSessionRepository;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionSpacedReminderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 本人認可の外側窓口から呼ぶ、想起源だけの独立保存境界。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
public class RecallSessionWriter {
    private static final ZoneId STORAGE_ZONE=ZoneId.of(com.mannschaft.app.common.CommonConstants.DEFAULT_TIMEZONE);
    private final RecallSessionRepository sessions;
    private final RecallSessionCommandRepository commands;
    private final ReflectionEntryRepository entries;
    private final RecallAttemptRepository attempts;
    private final ReflectionSpacedReminderRepository reminders;
    private final ReflectionAccessGuard access;
    private final ReflectionMaskEvaluator maskEvaluator;
    private final RecallSessionPromptFactory promptFactory;
    private final RecallSessionInputParser input;
    private final RecallSessionSnapshotCodec codec;
    private final RecallSessionCommandHasher hasher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 保存済み成功命令を先に読む。現在の本文・時計から応答を再生成しない。 */
    public RecallSessionOperationOutcome start(long userId,UUID entryId,UUID key,LocalDate today) {
        byte[] hash=hasher.hash(userId,RecallSessionCommandType.START,entryId,0,List.of(),null);
        var existing=commands.findOwnedForUpdate(userId,key);
        if(existing.isPresent()) return replay(existing.get(),RecallSessionCommandType.START,hash);
        if(today==null) throw unavailable();
        var entry=ownedEntry(userId,entryId);
        var theme=access.requireOwnedTheme(userId,entry.getThemeId());
        JsonNode content=validatedContent(entry.getStructuredContent());
        var prompts=promptFactory.fromValidatedContent(content,maskEvaluator.resolveDirection(entry,theme,today));
        // 元のMapperのパース失敗ログへ本文causeを渡さず、検証済み内容から同じ公開DTOを凍結する。
        var original=ReflectionEntryResponse.builder().id(entry.getId().toString())
                .themeId(entry.getThemeId().toString()).targetDate(entry.getTargetDate())
                .isMasked(false).structuredContent(content.deepCopy()).maskedHint(null)
                .visibility(entry.getVisibility()).version(entry.getVersion()).updatedAt(entry.getUpdatedAt())
                .exportedBlogPostId(entry.getExportedBlogPostId()).build();
        Instant now=now();
        var session=RecallSessionEntity.builder().id(UuidV7.generate()).userId(userId)
                .entryIdType("UUID").entrySourceId(entryId.toString()).status(RecallSessionStatus.STARTED)
                .promptSnapshot(codec.promptsJson(prompts)).originalSnapshot(codec.originalJson(original))
                .answersJson(codec.answersJson(List.of())).version(0).startedAt(now).createdAt(now).updatedAt(now).build();
        sessions.saveAndFlush(session);
        var response=response(session);
        saveCommand(userId,key,RecallSessionCommandType.START,hash,response,now);
        return new RecallSessionOperationOutcome(response,false);
    }

    /** 本人SQLで取得し、原文開示前にも現在のエントリ所有を確認する。 */
    public RecallSessionResponse get(long userId,UUID sessionId) {
        var session=ownedSession(userId,sessionId);
        access.requireOwnedEntry(userId,entryId(session));
        return response(session);
    }

    /** 部分回答を開始時設問順に統合する。版照合は成功再送の後に行う。 */
    public RecallSessionOperationOutcome answers(long userId,UUID sessionId,UUID key,JsonNode body) {
        var old=commands.findOwnedForUpdate(userId,key);
        if(old.isPresent()) {
            if(old.get().getCommandType()!=RecallSessionCommandType.ANSWERS) throw commandConflict();
            var saved=codec.response(old.get().getResultJson());
            var parsed=input.answers(body,saved.prompts());
            return replay(old.get(),RecallSessionCommandType.ANSWERS,
                    hasher.hash(userId,RecallSessionCommandType.ANSWERS,sessionId,parsed.version(),parsed.answers(),null));
        }
        var session=ownedSession(userId,sessionId);
        var prompts=codec.prompts(session.getPromptSnapshot());
        var parsed=input.answers(body,prompts);
        requireStartedVersion(session,parsed.version());
        var merged=merge(prompts,codec.answers(session.getAnswersJson()),parsed.answers());
        Instant now=now();
        session.saveAnswers(codec.answersJson(merged),now);
        sessions.flush();
        var response=response(session);
        saveCommand(userId,key,RecallSessionCommandType.ANSWERS,
                hasher.hash(userId,RecallSessionCommandType.ANSWERS,sessionId,parsed.version(),parsed.answers(),null),response,now);
        return new RecallSessionOperationOutcome(response,false);
    }

    /** 現在設定の取得前に、保存済み完了ACKを本人条件で照合する。 */
    public Optional<RecallSessionResponse> completeReplay(long userId,UUID sessionId,UUID key,JsonNode body) {
        var old=commands.findOwnedForUpdate(userId,key);
        if(old.isEmpty()) return Optional.empty();
        if(old.get().getCommandType()!=RecallSessionCommandType.COMPLETE) throw commandConflict();
        var saved=codec.response(old.get().getResultJson());
        var parsed=input.complete(body,saved.prompts());
        return Optional.of(replay(old.get(),RecallSessionCommandType.COMPLETE,
                hasher.hash(userId,RecallSessionCommandType.COMPLETE,sessionId,parsed.version(),parsed.answers(),parsed.selfRating())).response());
    }

    /** 現在設定が必要なのは未成功の初回保存だけ。再送は保存ACKを先に返す。 */
    public RecallSessionOperationOutcome complete(long userId,UUID sessionId,UUID key,JsonNode body,
            ZoneId userZone,int remindHour) {
        var old=commands.findOwnedForUpdate(userId,key);
        if(old.isPresent()) {
            if(old.get().getCommandType()!=RecallSessionCommandType.COMPLETE) throw commandConflict();
            var saved=codec.response(old.get().getResultJson());
            var parsed=input.complete(body,saved.prompts());
            return replay(old.get(),RecallSessionCommandType.COMPLETE,
                    hasher.hash(userId,RecallSessionCommandType.COMPLETE,sessionId,parsed.version(),parsed.answers(),parsed.selfRating()));
        }
        if(userZone==null || remindHour<0 || remindHour>23) throw unavailable();
        var session=ownedSession(userId,sessionId);
        var prompts=codec.prompts(session.getPromptSnapshot());
        var parsed=input.complete(body,prompts);
        requireStartedVersion(session,parsed.version());
        var entry=ownedEntry(userId,entryId(session));
        access.requireOwnedTheme(userId,entry.getThemeId());
        var merged=merge(prompts,codec.answers(session.getAnswersJson()),parsed.answers());
        String compressed=input.compressedAttempt(sessionId,prompts,merged);
        Instant now=now();
        LocalDate recallDate=now.atZone(userZone).toLocalDate();
        LocalDateTime storedAt=LocalDateTime.ofInstant(now,STORAGE_ZONE);
        attempts.save(RecallAttemptEntity.builder().entryId(entry.getId()).userId(userId)
                .recallDate(recallDate).recalledContent(compressed).selfRating(parsed.selfRating())
                .revealedAt(storedAt).createdAt(storedAt).build());
        if(parsed.selfRating()==RecallSelfRating.FORGOT) {
            // TZと時刻設定は外側で確定済み。authや共有cacheをcallback内で読み直さない。
            LocalDateTime remindAt=recallDate.plusDays(1).atTime(remindHour,0).atZone(userZone)
                    .withZoneSameInstant(STORAGE_ZONE).toLocalDateTime();
            reminders.save(ReflectionSpacedReminderEntity.builder().entryId(entry.getId()).themeId(null)
                    .userId(userId).remindAt(remindAt).intervalDays(null).kind(ReflectionReminderKind.SPACED)
                    .status(ReflectionReminderStatus.PENDING).build());
        }
        LocalDate week=now.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        session.complete(codec.answersJson(merged),parsed.selfRating(),week,now);
        sessions.flush();
        var response=response(session);
        saveCommand(userId,key,RecallSessionCommandType.COMPLETE,
                hasher.hash(userId,RecallSessionCommandType.COMPLETE,sessionId,parsed.version(),parsed.answers(),parsed.selfRating()),response,now);
        // 初回完了の保存事実だけを返す。報酬資格はこのbooleanから推定しない。
        return new RecallSessionOperationOutcome(response,true);
    }

    /** 取消でattemptや報酬を作成しない。 */
    public RecallSessionOperationOutcome cancel(long userId,UUID sessionId,UUID key,JsonNode body) {
        long version=input.cancel(body);
        byte[] hash=hasher.hash(userId,RecallSessionCommandType.CANCEL,sessionId,version,List.of(),null);
        var old=commands.findOwnedForUpdate(userId,key);
        if(old.isPresent()) return replay(old.get(),RecallSessionCommandType.CANCEL,hash);
        var session=ownedSession(userId,sessionId);
        requireStartedVersion(session,version);
        Instant now=now(); session.cancel(now); sessions.flush();
        var response=response(session);
        saveCommand(userId,key,RecallSessionCommandType.CANCEL,hash,response,now);
        return new RecallSessionOperationOutcome(response,false);
    }

    private RecallSessionOperationOutcome replay(RecallSessionCommandEntity command,RecallSessionCommandType kind,byte[] hash) {
        if(command.getCommandType()!=kind || command.getBodyHash()==null
                || !MessageDigest.isEqual(command.getBodyHash(),hash)) throw commandConflict();
        return new RecallSessionOperationOutcome(codec.response(command.getResultJson()),false);
    }

    private void saveCommand(long userId,UUID key,RecallSessionCommandType kind,byte[] hash,
            RecallSessionResponse response,Instant now) {
        commands.saveAndFlush(RecallSessionCommandEntity.builder().id(UuidV7.generate()).userId(userId)
                .idempotencyKey(key).sessionId(response.id()).commandType(kind).bodyHash(hash.clone())
                .resultJson(codec.responseJson(response)).completedAt(now).createdAt(now).updatedAt(now).build());
    }

    private RecallSessionResponse response(RecallSessionEntity session) {
        return new RecallSessionResponse(session.getId(),entryId(session),session.getStatus(),Long.toString(session.getVersion()),
                codec.prompts(session.getPromptSnapshot()),codec.answers(session.getAnswersJson()),session.getSelfRating(),
                session.getStartedAt(),session.getCompletedAt(),session.getRewardWeek(),null,
                session.getStatus()==RecallSessionStatus.COMPLETED?codec.original(session.getOriginalSnapshot()):null);
    }

    private RecallSessionEntity ownedSession(long userId,UUID id) {
        return sessions.findOwnedForUpdate(id,userId).orElseThrow(RecallSessionWriter::notFound);
    }
    private ReflectionEntryEntity ownedEntry(long userId,UUID id) {
        return entries.findOwnedForUpdate(id,userId).orElseThrow(RecallSessionWriter::notFound);
    }
    private UUID entryId(RecallSessionEntity session) {
        try {
            UUID id=UUID.fromString(session.getEntrySourceId());
            if(!"UUID".equals(session.getEntryIdType()) || !id.toString().equals(session.getEntrySourceId())) throw unavailable();
            return id;
        }catch(IllegalArgumentException ignored){throw unavailable();}
    }
    private JsonNode validatedContent(String encoded) {
        try {
            JsonNode content=objectMapper.readTree(encoded);
            if(content==null || !content.isObject()) throw unavailable();
            return content;
        }catch(Exception ignored){throw unavailable();}
    }
    private static List<RecallSessionAnswer> merge(List<RecallSessionPrompt> prompts,List<RecallSessionAnswer> old,
            List<RecallSessionAnswer> changed) {
        var byId=new HashMap<UUID,RecallSessionAnswer>();
        for(var answer:old) if(byId.put(answer.promptId(),answer)!=null) throw unavailable();
        changed.forEach(answer->byId.put(answer.promptId(),answer));
        var result=new ArrayList<RecallSessionAnswer>();
        for(var prompt:prompts) if(byId.containsKey(prompt.id())) result.add(byId.remove(prompt.id()));
        if(!byId.isEmpty()) throw unavailable();
        return List.copyOf(result);
    }
    private static void requireStartedVersion(RecallSessionEntity session,long version) {
        if(session.getStatus()!=RecallSessionStatus.STARTED || session.getVersion()!=version)
            throw new BusinessException(RecallSessionErrorCode.STATE_CONFLICT);
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    private static BusinessException notFound(){return new BusinessException(ReflectionErrorCode.REFLECTION_NOT_FOUND);}
    private static BusinessException commandConflict(){return new BusinessException(RecallSessionErrorCode.COMMAND_CONFLICT);}
    private static BusinessException unavailable(){return new BusinessException(RecallSessionErrorCode.UNAVAILABLE);}
}
