package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.UserCalendarLayerSettingEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.19 — {@link UserCalendarLayerSettingRepository} 結合テスト（Wave 1-a）。
 *
 * <p>設計書: {@code docs/features/F03.19_unified_calendar_view.md} §3.1/§4.3〜4.5。
 * {@link UserCalendarLayerSettingEntity} の永続化往復と、UUIDv7 主キーが実際に採番されること、
 * §4.3〜4.5 が要求する finder（一覧・upsertキー・削除・件数）がランタイムで解決されることを検証する。</p>
 */
@Transactional
@DisplayName("UserCalendarLayerSettingRepository 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserCalendarLayerSettingRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private UserCalendarLayerSettingRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long USER_A = 5001L;
    private static final Long USER_B = 5002L;

    private UserCalendarLayerSettingEntity persist(Long userId, String scopeType, Long scopeId,
                                                    String color, boolean hidden) {
        UserCalendarLayerSettingEntity e = UserCalendarLayerSettingEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .color(color)
                .hidden(hidden)
                .build();
        em.persist(e);
        em.flush();
        em.clear();
        return e;
    }

    @Test
    @DisplayName("保存_全フィールドが永続化され_UUIDv7主キーが採番される")
    void 保存_全フィールドが永続化される() {
        UserCalendarLayerSettingEntity saved = persist(USER_A, "TEAM", 42L, "#DC2626", false);

        assertThat(saved.getId()).isNotNull();

        UserCalendarLayerSettingEntity found = em.find(UserCalendarLayerSettingEntity.class, saved.getId());
        assertThat(found).isNotNull();
        assertThat(found.getUserId()).isEqualTo(USER_A);
        assertThat(found.getScopeType()).isEqualTo("TEAM");
        assertThat(found.getScopeId()).isEqualTo(42L);
        assertThat(found.getColor()).isEqualTo("#DC2626");
        assertThat(found.getHidden()).isFalse();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("PERSONAL行はscopeId=0センチネルで保存される")
    void PERSONAL行はscopeIdゼロで保存される() {
        UserCalendarLayerSettingEntity saved = persist(USER_A, "PERSONAL", 0L, null, false);

        UserCalendarLayerSettingEntity found = em.find(UserCalendarLayerSettingEntity.class, saved.getId());
        assertThat(found.getScopeType()).isEqualTo("PERSONAL");
        assertThat(found.getScopeId()).isZero();
        assertThat(found.getColor()).isNull();
    }

    @Test
    @DisplayName("findByUserId — 本人の設定行を全件取得できる（他人の行は混ざらない）")
    void findByUserIdで本人の設定行を全件取得できる() {
        persist(USER_A, "PERSONAL", 0L, null, false);
        persist(USER_A, "TEAM", 42L, "#DC2626", true);
        persist(USER_B, "TEAM", 42L, "#2563EB", false);

        List<UserCalendarLayerSettingEntity> result = repository.findByUserId(USER_A);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> e.getUserId().equals(USER_A));
    }

    @Test
    @DisplayName("findByUserIdAndScopeTypeAndScopeId — upsertキーで1件解決できる")
    void findByUserIdAndScopeTypeAndScopeIdで1件解決できる() {
        persist(USER_A, "TEAM", 42L, "#DC2626", false);

        Optional<UserCalendarLayerSettingEntity> found =
                repository.findByUserIdAndScopeTypeAndScopeId(USER_A, "TEAM", 42L);
        assertThat(found).isPresent();
        assertThat(found.get().getColor()).isEqualTo("#DC2626");

        assertThat(repository.findByUserIdAndScopeTypeAndScopeId(USER_B, "TEAM", 42L)).isEmpty();
    }

    @Test
    @DisplayName("deleteByUserIdAndScopeTypeAndScopeId — 設定削除で自動色に戻る（行が消える）")
    void deleteByUserIdAndScopeTypeAndScopeIdで行が消える() {
        persist(USER_A, "TEAM", 42L, "#DC2626", false);

        repository.deleteByUserIdAndScopeTypeAndScopeId(USER_A, "TEAM", 42L);
        em.flush();
        em.clear();

        assertThat(repository.findByUserIdAndScopeTypeAndScopeId(USER_A, "TEAM", 42L)).isEmpty();
    }

    @Test
    @DisplayName("countByUserId — 行数上限チェック用の件数が取れる")
    void countByUserIdで件数が取れる() {
        persist(USER_A, "PERSONAL", 0L, null, false);
        persist(USER_A, "TEAM", 42L, "#DC2626", false);
        persist(USER_B, "TEAM", 42L, "#2563EB", false);

        assertThat(repository.countByUserId(USER_A)).isEqualTo(2L);
    }
}
