package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.common.timezone.UserTimezoneCache;
import com.mannschaft.app.reflection.dto.RecallSessionOperationOutcome;
import com.mannschaft.app.reflection.dto.RecallSessionResponse;
import lombok.RequiredArgsConstructor;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.reflection.dto.ReflectionRecallRewardPayload;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 新ARは現在ACTIVEの本人だけが操作する。報酬用の任意資格取得で本人認可を代用しない。 */
@Service
@RequiredArgsConstructor
public class RecallSessionOperationFacade {
    private static final ZoneId FALLBACK_ZONE=ZoneId.of("Asia/Tokyo");
    private final UserOperationGuard userGuard;
    private final RecallSessionWriter writer;
    private final RecallSessionInputParser input;
    private final UserTimezoneCache timezoneCache;
    private final ReflectionSettingsService settings;
    private final Clock clock;
    private final ReflectionRecallRewardQueue rewardQueue;
    private final ReflectionRanchCaptureTelemetry telemetry;

    /** 共有timezone読取はauthの行ロックを保持する前に済ませる。 */
    public RecallSessionResponse start(Long userId,UUID entryId,UUID key,JsonNode body) {
        LocalDate today=clock.instant().atZone(userZone(userId)).toLocalDate();
        return write(userId,()->{
            input.start(body);
            return writer.start(userId,entryId,key,today);
        }).response();
    }

    public RecallSessionResponse get(Long userId,UUID sessionId) {
        return userGuard.withActiveUser(userId,()->writer.get(userId,sessionId));
    }

    public RecallSessionResponse answers(Long userId,UUID sessionId,UUID key,JsonNode body) {
        return write(userId,()->writer.answers(userId,sessionId,key,body)).response();
    }

    /** 設定/TZもcallback外で取得し、auth+reflectionの同時二接続内へ閉じる。 */
    public RecallSessionResponse complete(Long userId,UUID sessionId,UUID key,JsonNode body) {
        // ACTIVE→PRIMARY成功ACKを現在設定より先に読む。二つのguardは順次で再入しない。
        var saved=userGuard.withActiveUser(userId,()->writer.completeReplay(userId,sessionId,key,body));
        if(saved.isPresent()) return saved.get();
        ZoneId zone=userZone(userId);
        int remindHour=settings.remindHour(userId);
        return write(userId,()->writer.complete(userId,sessionId,key,body,zone,remindHour)).response();
    }

    public RecallSessionResponse cancel(Long userId,UUID sessionId,UUID key,JsonNode body) {
        return write(userId,()->writer.cancel(userId,sessionId,key,body)).response();
    }

    /**
     * callback開始前の資格/容量失敗にはfallbackしない。
     * native独立TXが既にcommitした後の外側auth失敗だけは、成功本体を保持して無報酬にする。
     * 元native commitと外側auth commitの両成立後だけ、有界キューへ不変事実を渡す。
     */
    private RecallSessionOperationOutcome write(Long userId,Supplier<RecallSessionOperationOutcome> nativeWrite) {
        var committed=new AtomicReference<RecallSessionOperationOutcome>();
        try {
            RecallSessionOperationOutcome outcome=userGuard.withActiveUser(userId,()->{
                RecallSessionOperationOutcome result=nativeWrite.get();
                committed.set(result);
                if(result.newCompletion()) {
                    try {
                        var response=result.response();
                        var fact=new ReflectionRecallRewardPayload(UuidV7.generate(),1,
                                RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,RanchRewardEnvelope.IdType.UUID,
                                response.entryId().toString(),RanchRewardEnvelope.ScopeType.PERSONAL,null,null,
                                RanchRewardEnvelope.ActorKind.USER,userId,null,userId,userId,response.completedAt(),
                                RanchRewardEnvelope.Origin.PERSONAL_COMPLETION,
                                new RanchRewardEnvelope.PersonalRecall(response.id(),response.prompts().size(),
                                        response.rewardWeek(),true));
                        result=new RecallSessionOperationOutcome(response,true,fact);
                        committed.set(result);
                    }catch(RuntimeException captureFailure) {
                        telemetry.lost(ReflectionRanchCaptureTelemetry.Reason.CAPTURE_FAILED,captureFailure.getClass());
                    }
                }
                return result;
            });
            if(outcome.rewardCandidate()!=null) rewardQueue.offer(outcome.rewardCandidate());
            return outcome;
        }catch(RuntimeException failure) {
            RecallSessionOperationOutcome result=committed.get();
            if(result==null) throw failure;
            telemetry.lost(ReflectionRanchCaptureTelemetry.Reason.POST_NATIVE_FAILURE,failure.getClass());
            return new RecallSessionOperationOutcome(result.response(),false);
        }
    }

    private ZoneId userZone(Long userId) {
        try{return ZoneId.of(timezoneCache.getTimezone(userId));}
        catch(RuntimeException ignored){return FALLBACK_ZONE;}
    }
}
