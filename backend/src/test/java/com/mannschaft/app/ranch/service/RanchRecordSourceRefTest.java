package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.entity.RanchRewardDecisionEntity;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 台帳表示に使うIDは保存済み本人決定からのみ復元し、壊れた値ではリンク候補を作らない。 */
class RanchRecordSourceRefTest {
    @Test
    void typedSavedKeyRestoresOnlyItsCanonicalSourceIdentity() {
        var saved = row(RanchRewardSourceType.BLOG_FIRST_PUBLISH, 21L,
                "BLOG_FIRST_PUBLISH:LONG:37:USER:21", null);
        assertThat(RanchRecordSourceRef.from(saved)).contains(new RanchRecordSourceRef(
                RanchRewardSourceType.BLOG_FIRST_PUBLISH, RanchRewardEnvelope.IdType.LONG, "37"));
    }

    @Test
    void mismatchedOwnerWeekAndInjectedDelimiterCannotBecomeSourceRef() {
        assertThat(RanchRecordSourceRef.from(row(RanchRewardSourceType.BLOG_FIRST_PUBLISH, 21L,
                "BLOG_FIRST_PUBLISH:LONG:37:USER:22", null))).isEmpty();
        assertThat(RanchRecordSourceRef.from(row(RanchRewardSourceType.BLOG_FIRST_PUBLISH, 21L,
                "BLOG_FIRST_PUBLISH:LONG:37:ADMIN:21", null))).isEmpty();
        assertThat(RanchRecordSourceRef.from(row(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE, 21L,
                "PERSONAL_RECALL_COMPLETE:UUID:550e8400-e29b-41d4-a716-446655440000:USER:21:WEEK:2026-10-12",
                LocalDate.parse("2026-10-05")))).isEmpty();
    }

    @Test
    void personalRecallRestoresCanonicalEntryUuidAndSavedWeek() {
        var saved = row(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE, 21L,
                "PERSONAL_RECALL_COMPLETE:UUID:550e8400-e29b-71d4-a716-446655440000:USER:21:WEEK:2026-10-05",
                LocalDate.parse("2026-10-05"));
        assertThat(RanchRecordSourceRef.from(saved)).contains(new RanchRecordSourceRef(
                RanchRewardSourceType.PERSONAL_RECALL_COMPLETE, RanchRewardEnvelope.IdType.UUID,
                "550e8400-e29b-71d4-a716-446655440000"));
    }

    @Test
    void nonCanonicalIdentifiersAndExtraKeyPartsNeverBecomeLinkCandidates() {
        for (String key : new String[] {
                "BLOG_FIRST_PUBLISH:LONG:037:USER:21",
                "BLOG_FIRST_PUBLISH:LONG:9223372036854775808:USER:21",
                "BLOG_FIRST_PUBLISH:LONG:0:USER:21",
                "BLOG_FIRST_PUBLISH:LONG:37:USER:21:WEEK:2026-10-05",
                "BLOG_FIRST_PUBLISH:UNKNOWN:37:USER:21",
                "TIMELINE_ORIGINAL:LONG:37:USER:21"}) {
            assertThat(RanchRecordSourceRef.from(row(
                    RanchRewardSourceType.BLOG_FIRST_PUBLISH, 21L, key, null))).isEmpty();
        }
        assertThat(RanchRecordSourceRef.from(row(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE, 21L,
                "PERSONAL_RECALL_COMPLETE:UUID:550E8400-E29B-41D4-A716-446655440000:USER:21:WEEK:2026-10-05",
                LocalDate.parse("2026-10-05")))).isEmpty();
    }

    @Test
    void nullSavedKeyWrongSourceIdTypeAndNonV7RecallAreRejected() {
        assertThat(RanchRecordSourceRef.from(RanchRewardDecisionEntity.builder()
                .sourceType(RanchRewardSourceType.BLOG_FIRST_PUBLISH).userId(21L).build())).isEmpty();
        assertThat(RanchRecordSourceRef.from(row(RanchRewardSourceType.BLOG_FIRST_PUBLISH, 21L,
                "BLOG_FIRST_PUBLISH:UUID:550e8400-e29b-71d4-a716-446655440000:USER:21", null))).isEmpty();
        assertThat(RanchRecordSourceRef.from(row(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE, 21L,
                "PERSONAL_RECALL_COMPLETE:LONG:37:USER:21:WEEK:2026-10-05",
                LocalDate.parse("2026-10-05")))).isEmpty();
        assertThat(RanchRecordSourceRef.from(row(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE, 21L,
                "PERSONAL_RECALL_COMPLETE:UUID:550e8400-e29b-41d4-a716-446655440000:USER:21:WEEK:2026-10-05",
                LocalDate.parse("2026-10-05")))).isEmpty();
    }

    private static RanchRewardDecisionEntity row(RanchRewardSourceType source, Long userId,
                                                  String key, LocalDate week) {
        return RanchRewardDecisionEntity.builder().sourceType(source).userId(userId)
                .canonicalKey(key.getBytes(StandardCharsets.US_ASCII)).rewardWeek(week).build();
    }
}
