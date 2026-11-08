package com.mannschaft.app.gamification;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.gamification.entity.BadgeEntity;
import com.mannschaft.app.gamification.entity.UserBadgeEntity;
import com.mannschaft.app.gamification.repository.BadgeRepository;
import com.mannschaft.app.gamification.repository.UserBadgeRepository;
import com.mannschaft.app.gamification.service.GamificationRanchBadgeQueryService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 実DBで本人限定・ページ境界・削除済み素材の区別を確認する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class GamificationRanchBadgeQueryServiceIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private BadgeRepository badges;
    @Autowired private UserBadgeRepository awards;
    @Autowired private GamificationRanchBadgeQueryService reader;

    @Test
    void keysetIsBoundedAndNeverIncludesOtherOwnersAwards() {
        Long me = user();
        Long other = user();
        var badge = badge(me);
        var foreign = award(other, badge.getId(), "foreign");
        for (int index = 0; index < 102; index++) {
            award(me, badge.getId(), "period-" + index);
        }
        var first = reader.page(me, 0L);
        assertThat(first).hasSize(101).allSatisfy(row -> {
            assertThat(row.sourceBadgeAvailable()).isTrue();
            assertThat(row.badgeId()).isEqualTo(Long.toString(badge.getId()));
            assertThat(row.awardRowId()).isNotEqualTo(foreign.getId());
        });
        var second = reader.page(me, first.get(99).awardRowId());
        assertThat(second).hasSize(2);
        assertThat(second.get(0)).isEqualTo(first.get(100));
        assertThat(reader.page(me, second.get(1).awardRowId())).isEmpty();
    }

    @Test
    void inactiveAndDeletedBadgeKeepTheirAwardIdentityButAreUnavailable() {
        Long me = user();
        var active = badge(me);
        var inactive = badge(me);
        inactive.update("非公開", null, null, null, null, false, false);
        badges.saveAndFlush(inactive);
        var deleted = badge(me);
        deleted.softDelete();
        badges.saveAndFlush(deleted);
        var first = award(me, active.getId(), null);
        var second = award(me, inactive.getId(), "2026-W40");
        var third = award(me, deleted.getId(), "2026-W40");
        assertThat(reader.page(me, 0L)).satisfies(rows -> {
            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).awardRowId()).isEqualTo(first.getId());
            assertThat(rows.get(0).periodLabel()).isEmpty();
            assertThat(rows.get(0).sourceBadgeAvailable()).isTrue();
            assertThat(rows.get(1).awardRowId()).isEqualTo(second.getId());
            assertThat(rows.get(1).sourceBadgeAvailable()).isFalse();
            assertThat(rows.get(2).awardRowId()).isEqualTo(third.getId());
            assertThat(rows.get(2).sourceBadgeAvailable()).isFalse();
        });
    }

    private Long user() {
        return users.saveAndFlush(UserEntity.builder()
                .email("badge-" + UUID.randomUUID() + "@example.invalid")
                .lastName("試験").firstName("本人").displayName("置物試験")
                .status(UserEntity.UserStatus.ACTIVE).isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").build()).getId();
    }

    private BadgeEntity badge(Long userId) {
        return badges.saveAndFlush(BadgeEntity.builder().scopeType("USER").scopeId(userId)
                .name("試験" + UUID.randomUUID()).badgeType(BadgeType.STANDARD)
                .conditionType(BadgeConditionType.MANUAL).build());
    }

    private UserBadgeEntity award(Long userId, Long badgeId, String period) {
        return awards.saveAndFlush(UserBadgeEntity.builder().userId(userId).badgeId(badgeId)
                .earnedOn(LocalDate.of(2026, 10, 4)).periodLabel(period)
                .awardedBy(AwardedBy.ADMIN).build());
    }
}
