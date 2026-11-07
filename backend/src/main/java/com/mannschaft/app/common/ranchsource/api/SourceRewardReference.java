package com.mannschaft.app.common.ranchsource.api;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope.IdType;
import java.util.UUID;

/** 源が現在の閲覧認可を判定するための正準技術ID。本文や受益者情報を含めない。 */
public record SourceRewardReference(RanchRewardSourceType sourceType, IdType idType, String sourceId) {
    public SourceRewardReference {
        if (sourceType == null || idType == null || sourceId == null) throw invalid();
        if (sourceType == RanchRewardSourceType.PERSONAL_RECALL_COMPLETE) {
            if (idType != IdType.UUID) throw invalid();
            try {
                UUID id = UUID.fromString(sourceId);
                if (!id.toString().equals(sourceId) || id.version() != 7 || id.variant() != 2) throw invalid();
            } catch (IllegalArgumentException failure) {
                throw invalid();
            }
        } else {
            if (idType != IdType.LONG || !sourceId.matches("[1-9][0-9]{0,18}")) throw invalid();
            try {
                if (Long.parseLong(sourceId) <= 0) throw invalid();
            } catch (NumberFormatException failure) {
                throw invalid();
            }
        }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("報酬源参照の技術IDが不正です");
    }
}
