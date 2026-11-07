package com.mannschaft.app.reflection.dto;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope.IdType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope.ScopeType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope.ActorKind;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope.Origin;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope.PersonalRecall;
import java.time.Instant;
import java.util.UUID;

/** 源が確定した技術IDと事実だけを保存する。本文・姓名・出生情報は持たない。 */
public record ReflectionRecallRewardPayload(
        UUID eventId, int schemaVersion, RanchRewardSourceType sourceType,
        IdType sourceIdType, String canonicalSourceId,
        ScopeType scopeType, IdType scopeIdType, String canonicalScopeId,
        ActorKind actorKind, Long actorUserId, Long originalAdminId,
        Long subjectUserId, Long recipientUserId, Instant occurredAt, Origin origin, PersonalRecall facts) {
    public ReflectionRecallRewardPayload {
        if(schemaVersion!=1 || sourceType!=RanchRewardSourceType.PERSONAL_RECALL_COMPLETE)
            throw new IllegalArgumentException("報酬源payloadの版または源種別が不正です");
        // public consumer DTOと同じ正準ID・scope・actor・MICROS・origin規則を適用する。
        new RanchRewardEnvelope(eventId,schemaVersion,sourceType,sourceIdType,canonicalSourceId,
                scopeType,scopeIdType,canonicalScopeId,actorKind,actorUserId,originalAdminId,
                subjectUserId,recipientUserId,occurredAt,origin,facts);
    }
    public RanchRewardEnvelope toEnvelope() {
        return new RanchRewardEnvelope(eventId,schemaVersion,sourceType,sourceIdType,canonicalSourceId,
                scopeType,scopeIdType,canonicalScopeId,actorKind,actorUserId,originalAdminId,
                subjectUserId,recipientUserId,occurredAt,origin,facts);
    }
}
