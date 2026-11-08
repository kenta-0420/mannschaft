package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.reflection.RecallSessionStatus;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import com.mannschaft.app.reflection.dto.ReflectionRecallRewardPayload;
import com.mannschaft.app.reflection.repository.RecallSessionRepository;
import com.mannschaft.app.reflection.repository.ReflectionRanchTransportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/** 保護中に捕捉した源事実を、元本体とは別のreflection TXで耐久受付する。 */
@Service
@RequiredArgsConstructor
public class ReflectionRanchTransportWriter {
    private final RecallSessionRepository sessions;
    private final ReflectionRanchTransportRepository transport;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    public boolean accept(ReflectionRecallRewardPayload fact) {
        if(fact.scopeType()!=RanchRewardEnvelope.ScopeType.PERSONAL
                || fact.sourceIdType()!=RanchRewardEnvelope.IdType.UUID
                || fact.actorKind()!=RanchRewardEnvelope.ActorKind.USER || fact.originalAdminId()!=null
                || !fact.recipientUserId().equals(fact.actorUserId())
                || !fact.recipientUserId().equals(fact.subjectUserId())) return false;
        // 完了行は再構成元ではなく、不変captureの整合検査だけに使う。
        var saved=sessions.findOwnedForUpdate(fact.facts().sessionId(),fact.recipientUserId());
        if(saved.isEmpty()) return false;
        var session=saved.get();
        if(session.getStatus()!=RecallSessionStatus.COMPLETED
                || !session.getEntrySourceId().equals(fact.canonicalSourceId())
                || !session.getCompletedAt().equals(fact.occurredAt())
                || !session.getRewardWeek().equals(fact.facts().completionWeek())
                || !fact.facts().firstCompletion() || fact.facts().promptCount()>1501) return false;
        try {
            var prompts=mapper.readTree(session.getPromptSnapshot());
            if(prompts==null || !prompts.isArray() || prompts.size()!=fact.facts().promptCount()) return false;
            return transport.insertQualified(fact,mapper.writeValueAsString(fact),
                    clock.instant().truncatedTo(ChronoUnit.MICROS));
        } catch(JsonProcessingException ignored) {
            throw new IllegalStateException("想起配送事実の符号化に失敗しました");
        }
    }
}
