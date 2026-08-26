package com.mannschaft.app.skill;

import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.repository.MemberSkillQueryRepository;
import com.mannschaft.app.skill.repository.MemberSkillRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 資格期限リマインダーの<b>対象抽出</b>の契約テスト（Gate 基盤工事④-B 第三陣 / Codex 検分指摘の根治）。
 *
 * <h2>何を守るテストか</h2>
 * <p>{@code MemberSkillQueryRepository#findExpiringSoon} は初版で
 * {@code expires_at <= threshold AND status = 'ACTIVE'} しか見ておらず、
 * <b>下限が無かった</b>。そのため<b>既に失効した資格</b>まで対象に入り、
 * 障害や停止でバッチが数日走らなかっただけで、再開時に
 * 「期限まで 30 日です」という通知が失効済みの資格へ送られていた。</p>
 *
 * <p>これは機能フラグとは無関係に存在する潜在バグであり、
 * {@code expires_at >= today} の下限を入れて根治した。本テストはその再発を防ぐ。</p>
 */
@DisplayName("資格期限リマインダー: 既に失効した資格は通知対象に入らない")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Transactional
class SkillExpiryReminderTargetIT extends AbstractMySqlIntegrationTest {

    private static final String SCOPE_TYPE = "TEAM";
    private static final long SCOPE_ID = 970_001L;
    private static final long USER_ID = 970_002L;

    @Autowired
    private MemberSkillQueryRepository memberSkillQueryRepository;

    @Autowired
    private MemberSkillRepository memberSkillRepository;

    @PersistenceContext
    private EntityManager em;

    private MemberSkillEntity save(String name, LocalDate expiresAt) {
        MemberSkillEntity skill = MemberSkillEntity.builder()
                .userId(USER_ID)
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .name(name)
                .expiresAt(expiresAt)
                .status(SkillStatus.ACTIVE)
                .build();
        return memberSkillRepository.save(skill);
    }

    @Test
    @DisplayName("失効済み（expires_at < today）の ACTIVE 資格は 30 日前通知の対象にならない")
    void 失効済みは通知対象に入らない() {
        LocalDate today = LocalDate.now();

        // 30日以内に失効する（＝通知すべき）
        MemberSkillEntity soon = save("まだ有効・10日後に失効", today.plusDays(10));
        // 既に失効している（＝通知してはならない）。下限が無いと <= threshold に引っかかる
        MemberSkillEntity alreadyExpired = save("失効済み・5日前に失効", today.minusDays(5));
        // ちょうど本日失効（境界: 下限は today を含む＝まだ通知してよい）
        MemberSkillEntity boundary = save("本日失効", today);
        em.flush();

        List<String> names = memberSkillQueryRepository
                .findExpiringSoon(today, today.plusDays(30), "DAYS_30")
                .stream()
                .filter(s -> s.getUserId() == USER_ID)
                .map(MemberSkillEntity::getName)
                .toList();

        assertThat(names)
                .as("失効済みの資格に「期限まで30日です」と通知してはならない")
                .doesNotContain(alreadyExpired.getName());
        assertThat(names)
                .as("まだ失効していない資格は通知対象に残らねばならない")
                .contains(soon.getName(), boundary.getName());
    }
}
