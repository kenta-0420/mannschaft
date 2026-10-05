package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.entity.RanchRewardDecisionEntity;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/** 保存済み決定から、源に再認可を依頼するための型付き識別子だけを復元する。 */
record RanchRecordSourceRef(RanchRewardSourceType sourceType,
                            RanchRewardEnvelope.IdType idType, String sourceId) {
    static Optional<RanchRecordSourceRef> from(RanchRewardDecisionEntity decision) {
        if (decision == null || decision.getUserId() == null || decision.getSourceType() == null) {
            return Optional.empty();
        }
        byte[] stored = decision.getCanonicalKey();
        for (byte value : stored) {
            if (value < 0x20 || value > 0x7e) return Optional.empty();
        }
        String[] parts = new String(stored, StandardCharsets.US_ASCII).split(":", -1);
        boolean recall = decision.getSourceType() == RanchRewardSourceType.PERSONAL_RECALL_COMPLETE;
        if (parts.length != (recall ? 7 : 5)
                || !parts[0].equals(decision.getSourceType().name())
                || !"USER".equals(parts[3])
                || !parts[4].equals(Long.toString(decision.getUserId()))
                || (recall && (!"WEEK".equals(parts[5])
                || decision.getRewardWeek() == null
                || !parts[6].equals(decision.getRewardWeek().toString())))) {
            return Optional.empty();
        }
        try {
            RanchRewardEnvelope.IdType type = RanchRewardEnvelope.IdType.valueOf(parts[1]);
            String id = parts[2];
            if (id.isEmpty() || id.length() > 80) return Optional.empty();
            if (type == RanchRewardEnvelope.IdType.UUID) {
                if (!UUID.fromString(id).toString().equals(id)) return Optional.empty();
            } else if (!id.matches("[1-9][0-9]*")
                    || !Long.toString(Long.parseLong(id)).equals(id)) {
                return Optional.empty();
            }
            return Optional.of(new RanchRecordSourceRef(decision.getSourceType(), type, id));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
