package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.gamification.AwardedBy;
import com.mannschaft.app.gamification.BadgeConditionType;
import com.mannschaft.app.gamification.BadgeType;
import com.mannschaft.app.gamification.entity.BadgeEntity;
import com.mannschaft.app.gamification.entity.UserBadgeEntity;
import com.mannschaft.app.gamification.repository.BadgeRepository;
import com.mannschaft.app.gamification.repository.UserBadgeRepository;
import com.mannschaft.app.gamification.service.GamificationRanchBadgeQueryService;
import com.mannschaft.app.ranch.dto.RanchLegacySyncRequest;
import com.mannschaft.app.ranch.entity.RanchCollectibleCatalogEntity;
import com.mannschaft.app.ranch.repository.RanchCollectibleCatalogRepository;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchInventoryRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.service.RanchCommandHasher;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchLegacySyncWriter;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** source101境界、未承認再走査、同key固定結果、他人隔離を実MySQLで検証する。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchLegacySyncWriterIT extends AbstractMySqlIntegrationTest {
    private static final String RESOURCE = "/api/v1/me/ranch/collectibles/sync";
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());
    @Autowired UserRepository users;
    @Autowired BadgeRepository badges;
    @Autowired UserBadgeRepository awards;
    @Autowired GamificationRanchBadgeQueryService source;
    @Autowired RanchEnrollmentWriter enrollment;
    @Autowired RanchLegacySyncWriter writer;
    @Autowired RanchCollectibleCatalogRepository catalog;
    @Autowired RanchInventoryRepository inventory;
    @Autowired RanchOwnerRepository owners;
    @Autowired RanchCommandRepository commands;
    @Autowired ObjectMapper json;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    @Test
    void laterCatalogApprovalCanRescanFromZeroWithoutChangingSavedCommand() throws Exception {
        Long me = users.saveAndFlush(RanchTestFixture.user()).getId();
        Long other = users.saveAndFlush(RanchTestFixture.user()).getId();
        enrollment.enroll(me, UUID.randomUUID(), Instant.parse("2026-10-04T01:00:00Z"), PROJECTION);
        var badge = badges.saveAndFlush(BadgeEntity.builder().scopeType("USER").scopeId(me)
                .name("旧試験" + UUID.randomUUID()).badgeType(BadgeType.STANDARD)
                .conditionType(BadgeConditionType.MANUAL).build());
        awards.saveAndFlush(UserBadgeEntity.builder().userId(other).badgeId(badge.getId())
                .earnedOn(LocalDate.of(2026, 10, 4)).periodLabel("other")
                .awardedBy(AwardedBy.ADMIN).build());
        for (int i = 0; i < 102; i++) {
            awards.saveAndFlush(UserBadgeEntity.builder().userId(me).badgeId(badge.getId())
                    .earnedOn(LocalDate.of(2026, 10, 4)).periodLabel("期-" + i)
                    .awardedBy(AwardedBy.ADMIN).build());
        }
        var firstSource = source.page(me, 0L);
        assertThat(firstSource).hasSize(101);
        UUID firstKey = UUID.randomUUID();
        byte[] firstHash = hash("0");
        var absent = writer.sync(me, firstKey, firstHash, 0L, firstSource);
        assertThat(absent.processedCount()).isEqualTo(100);
        assertThat(absent.importedCount()).isZero();
        assertThat(absent.hasNext()).isTrue();
        assertThat(inventory.findByUserIdOrderByAwardedAtDescIdDesc(me)).isEmpty();

        String approvedKey = "LEGACY_BADGE:" + badge.getId();
        Instant now = Instant.parse("2026-10-04T02:00:00Z");
        catalog.saveAndFlush(RanchCollectibleCatalogEntity.builder()
                .collectibleKey(approvedKey).labelKey("legacy.test")
                .assetKey("legacy-test-approved").sourceKind("LEGACY_BADGE")
                .active(true).createdAt(now).updatedAt(now).build());
        var replay = writer.sync(me, firstKey, firstHash, 0L, firstSource);
        assertThat(replay).isEqualTo(absent);
        assertThat(json.readTree(commands.findByUserIdAndIdempotencyKey(me, firstKey)
                .orElseThrow().getResultJson()).path("importedCount").intValue()).isZero();

        var imported = writer.sync(me, UUID.randomUUID(), firstHash, 0L, source.page(me, 0L));
        assertThat(imported.importedCount()).isEqualTo(100);
        assertThat(imported.nextAfterAwardId()).isEqualTo(absent.nextAfterAwardId());
        var tail = writer.sync(me, UUID.randomUUID(), hash(imported.nextAfterAwardId()),
                Long.parseLong(imported.nextAfterAwardId()),
                source.page(me, Long.parseLong(imported.nextAfterAwardId())));
        assertThat(tail.processedCount()).isEqualTo(2);
        assertThat(tail.importedCount()).isEqualTo(2);
        assertThat(tail.hasNext()).isFalse();
        assertThat(inventory.findByUserIdOrderByAwardedAtDescIdDesc(me)).hasSize(102)
                .allSatisfy(item -> {
                    assertThat(item.getAcquisitionKind()).isEqualTo("LEGACY_BADGE");
                    assertThat(item.getCollectibleKey()).isEqualTo(approvedKey);
                    assertThat(item.getLegacyAwardPeriod()).startsWith("期-");
                });
        assertThat(inventory.findByUserIdOrderByAwardedAtDescIdDesc(other)).isEmpty();
        var duplicate = writer.sync(me, UUID.randomUUID(), firstHash, 0L, source.page(me, 0L));
        assertThat(duplicate.importedCount()).isZero();
        assertThat(inventory.findByUserIdOrderByAwardedAtDescIdDesc(me)).hasSize(102);
    }

    private byte[] hash(String after) {
        return hasher.hash("LEGACY_SYNC", RESOURCE, null,
                json.valueToTree(new RanchLegacySyncRequest(after)));
    }
}
