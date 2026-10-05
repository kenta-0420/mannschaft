package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.schedule.dto.ScheduleRanchRewardPayload;
import com.mannschaft.app.schedule.repository.ScheduleRanchOutboxRepository;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAckRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxDeferRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxDeliveryFacade;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxFailureRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxLeaseRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxLeasedEvent;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 公開設定の値を受け取り出欠だけの短TXを閉じる。consumerやauthを呼ばない。 */
@Service
@RequiredArgsConstructor
public class ScheduleRanchOutboxDeliveryService implements SourceOutboxDeliveryFacade {
    private final ScheduleRanchOutboxRepository outboxes;
    private final ObjectMapper mapper;

    @Override public RanchRewardSourceType sourceType() { return RanchRewardSourceType.ATTENDANCE_RESPONSE; }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    public List<SourceOutboxLeasedEvent> lease(SourceOutboxLeaseRequest request) {
        var leased=new ArrayList<SourceOutboxLeasedEvent>();
        for(var row:outboxes.candidates(request.serverTime(),request.batchSize())) {
            if(row.attempts()>=request.maxAttempts()) {
                outboxes.rejectCandidate(row.eventId(),"ATTEMPT_LIMIT",request.serverTime());continue;
            }
            ScheduleRanchRewardPayload payload;
            try {
                payload=mapper.readValue(row.payload(),ScheduleRanchRewardPayload.class);
                if(payload==null || !row.eventId().equals(payload.eventId()) || row.schemaVersion()!=payload.schemaVersion()
                        || !sourceType().name().equals(row.eventType()) || row.recipient()!=payload.recipientUserId()
                        || !row.occurredAt().equals(payload.occurredAt()) || !row.scopeType().equals(payload.scopeType().name())
                        || !Objects.equals(row.scopeIdType(),payload.scopeIdType()==null?null:payload.scopeIdType().name())
                        || !Arrays.equals(row.scopeId(),payload.canonicalScopeId()==null?null:payload.canonicalScopeId().getBytes(StandardCharsets.US_ASCII)))
                    throw new IllegalArgumentException("源payloadの技術IDが不正です");
            } catch(JsonProcessingException | IllegalArgumentException failure) {
                // 壊れた保存payloadの本文/causeを記録せず、同batchの正常行を継続する。
                outboxes.rejectCandidate(row.eventId(),"PAYLOAD_INVALID",request.serverTime());continue;
            }
            var token=UuidV7.generate();var requestedExpires=request.serverTime().plusSeconds(request.leaseSeconds());
            var expires=outboxes.lease(row.eventId(),token,requestedExpires,request.serverTime());
            if(expires.isEmpty()) continue;
            leased.add(new SourceOutboxLeasedEvent(sourceType(),row.eventId(),token,expires.orElseThrow(),payload.toEnvelope(),row.attempts()+1));
        }
        return List.copyOf(leased);
    }
    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    public boolean acknowledge(SourceOutboxAckRequest request) {
        return outboxes.acknowledge(request.eventId(),request.leaseToken(),request.serverTime(),request.outcome().outcome().name());
    }
    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    public boolean retry(SourceOutboxFailureRequest request) {
        Integer attempt=outboxes.currentAttempt(request.eventId(),request.leaseToken(),request.serverTime());
        if(attempt==null) return false;
        if(attempt>=request.maxAttempts()) return outboxes.fail(request.eventId(),request.leaseToken(),request.serverTime(),request.errorCode());
        long delay=request.initialBackoffSeconds();
        for(int index=1;index<attempt && delay<request.maxBackoffSeconds();index++) delay=Math.min(delay*2L,request.maxBackoffSeconds());
        // full jitterも正数かつ公開max内。設定を別の既定値で補完しない。
        delay=ThreadLocalRandom.current().nextLong(1,delay+1);
        return outboxes.reschedule(request.eventId(),request.leaseToken(),request.serverTime(),request.serverTime().plusSeconds(delay),request.errorCode(),false);
    }
    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    public boolean defer(SourceOutboxDeferRequest request) {
        return outboxes.reschedule(request.eventId(),request.leaseToken(),request.serverTime(),request.nextEligibleAt(),null,true);
    }
}
