package com.mannschaft.app.ranch;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.entity.RanchCollectibleCatalogEntity;
import com.mannschaft.app.ranch.entity.RanchInventoryEntity;
import com.mannschaft.app.ranch.entity.RanchPointLedgerEntity;
import com.mannschaft.app.ranch.entity.RanchRewardDecisionEntity;
import com.mannschaft.app.ranch.reward.RanchRewardDecisionStatus;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

/** keyset専用の人工代表行。Repository保存・正式policyや素材の公開は行わない。 */
final class RanchReadKeysetTestFixture {
    private RanchReadKeysetTestFixture() { }

    static Rows rows(Long userId, RanchEnrollmentWriter.EnrollmentOutcome owner,
                     UUID recordId, UUID inventoryId, int index, Instant at) throws Exception {
        UUID decisionId = UuidV7.generate();
        byte[] canonical = ("ATTENDANCE_RESPONSE:UUID:" + UuidV7.generate() + ":USER:" + userId)
                .getBytes(StandardCharsets.US_ASCII);
        var decision = RanchRewardDecisionEntity.builder().id(decisionId)
                .ownerId(owner.ownerId()).userId(userId).eventId(UuidV7.generate())
                .sourceType(RanchRewardSourceType.ATTENDANCE_RESPONSE)
                .canonicalKey(canonical).canonicalKeyHash(MessageDigest.getInstance("SHA-256").digest(canonical))
                .rewardWeek(at.atZone(ZoneOffset.UTC).toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
                .policyId(UuidV7.generate())
                .status(RanchRewardDecisionStatus.AWARDED).requestedPoints(1).awardedPoints(1)
                .occurredAt(at).decidedAt(at).createdAt(at).build();
        var record = RanchPointLedgerEntity.builder().id(recordId)
                .ownerId(owner.ownerId()).userId(userId).decisionId(decisionId)
                .entryKind("REWARD").deltaPoints(1).balanceAfter(index + 1L).deltaXp(0)
                .dinosaurId(owner.dinosaurId()).ruleSnapshot("{}")
                .occurredAt(at).createdAt(at).build();
        // 人工の非公開catalogだけを作り、正式素材・価格・policyを承認しない。
        String key = "KEYSET_FIXTURE_" + inventoryId;
        var catalog = RanchCollectibleCatalogEntity.builder().collectibleKey(key)
                .labelKey("keyset-fixture").assetKey("keyset-fixture")
                .sourceKind("LEGACY_BADGE").active(false).createdAt(at).updatedAt(at).build();
        var item = RanchInventoryEntity.builder().id(inventoryId)
                .ownerId(owner.ownerId()).userId(userId).collectibleKey(key)
                .acquisitionKind("LEGACY_BADGE")
                .acquisitionKey(("KEYSET:" + inventoryId).getBytes(StandardCharsets.US_ASCII))
                .legacyBadgeId("KEYSET_FIXTURE").legacyAwardPeriod("keyset-fixture")
                .awardedAt(at).createdAt(at).build();
        return new Rows(decision, record, catalog, item);
    }

    record Rows(RanchRewardDecisionEntity decision, RanchPointLedgerEntity record,
                RanchCollectibleCatalogEntity catalog, RanchInventoryEntity inventory) { }
}
