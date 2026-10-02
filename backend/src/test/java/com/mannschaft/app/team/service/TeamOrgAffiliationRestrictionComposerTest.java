package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind.BLOCK;
import static com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind.COOLDOWN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 AC-G109（BE-UT）— 制限の合成規則（§5.4）の純粋関数のテスト。
 *
 * <ul>
 *   <li>BLOCK は COOLDOWN で上書きしない</li>
 *   <li>COOLDOWN どうしは期限の遅いほうを残す（同時刻は既存を残す）</li>
 *   <li>COOLDOWN は BLOCK で置き換わる</li>
 * </ul>
 */
@DisplayName("F01.2.1 AC-G109 制限の合成規則（純粋関数）")
class TeamOrgAffiliationRestrictionComposerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private static boolean wins(TeamOrgAffiliationRestrictionKind existing, Instant existingUntil,
                                TeamOrgAffiliationRestrictionKind incoming, Instant incomingUntil) {
        return TeamOrgAffiliationRestrictionComposer.incomingWins(existing, existingUntil, incoming, incomingUntil);
    }

    @Test
    @DisplayName("既存が BLOCK なら、COOLDOWN でも BLOCK でも既存を残す")
    void BLOCKは上書きされない() {
        assertThat(wins(BLOCK, null, COOLDOWN, T0.plusSeconds(86_400))).isFalse();
        assertThat(wins(BLOCK, null, COOLDOWN, T0.plusSeconds(10L * 365 * 86_400))).isFalse();
        assertThat(wins(BLOCK, null, BLOCK, null)).isFalse();
    }

    @Test
    @DisplayName("既存が COOLDOWN で新しい制限が BLOCK なら、BLOCK で置き換える（期限の遠近によらない）")
    void COOLDOWNはBLOCKで置き換わる() {
        assertThat(wins(COOLDOWN, T0.plusSeconds(1), BLOCK, null)).isTrue();
        assertThat(wins(COOLDOWN, T0.plusSeconds(30L * 86_400), BLOCK, null)).isTrue();
    }

    @Test
    @DisplayName("COOLDOWN どうしは、期限の遅いほうを残す")
    void COOLDOWNどうしは期限の遅いほうを残す() {
        Instant existing = T0.plusSeconds(30L * 86_400);

        assertThat(wins(COOLDOWN, existing, COOLDOWN, T0.plusSeconds(86_400)))
                .as("新しいほうが早く切れる（30日の冷却に24時間の連打防止が重なった）→ 既存を残す").isFalse();
        assertThat(wins(COOLDOWN, T0.plusSeconds(86_400), COOLDOWN, existing))
                .as("新しいほうが遅く切れる → 新しいほうで置き換える").isTrue();
    }

    @Test
    @DisplayName("COOLDOWN どうしで期限が同時刻なら、既存を残す（無用な更新をしない）")
    void 同時刻は既存を残す() {
        assertThat(wins(COOLDOWN, T0.plusSeconds(86_400), COOLDOWN, T0.plusSeconds(86_400))).isFalse();
    }
}
