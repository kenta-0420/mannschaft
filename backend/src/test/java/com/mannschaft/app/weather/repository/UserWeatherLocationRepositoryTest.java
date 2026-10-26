package com.mannschaft.app.weather.repository;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.weather.entity.UserWeatherLocationEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserWeatherLocationRepository} 結合テスト。
 *
 * <p>UUIDv7 主キー（BINARY(16)）・(user_id, label) ユニーク制約・
 * findByUserIdAndLabel / findByUserId / deleteByUserId の動作を確認する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("UserWeatherLocationRepository 結合テスト")
class UserWeatherLocationRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private UserWeatherLocationRepository repository;

    @PersistenceContext
    private EntityManager em;

    private UserWeatherLocationEntity buildEntity(Long userId, String label) {
        return UserWeatherLocationEntity.builder()
                .userId(userId)
                .label(label)
                .countryCode("JP")
                .postalCodeHash("a".repeat(64))
                .latitudeRounded(new BigDecimal("35.5"))
                .longitudeRounded(new BigDecimal("139.5"))
                .placeNameSnapshot("東京都千代田区")
                .derivedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @Transactional
    @DisplayName("save 後に UUIDv7 が自動採番され、findByUserIdAndLabel で取得できる")
    void shouldSaveAndFindByUserIdAndLabel() {
        UserWeatherLocationEntity saved = repository.saveAndFlush(buildEntity(1001L, "home"));
        em.clear();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getId().version()).isEqualTo(7);
        assertThat(saved.getId().variant()).isEqualTo(2);

        Optional<UserWeatherLocationEntity> found = repository.findByUserIdAndLabel(1001L, "home");
        assertThat(found).isPresent();
        assertThat(found.get().getCountryCode()).isEqualTo("JP");
        assertThat(found.get().getLatitudeRounded()).isEqualByComparingTo("35.5");
        assertThat(found.get().getPlaceNameSnapshot()).isEqualTo("東京都千代田区");
    }

    @Test
    @Transactional
    @DisplayName("findByUserId — 指定ユーザーの全地点を取得できる")
    void shouldFindAllByUserId() {
        repository.saveAndFlush(buildEntity(2001L, "home"));
        // (user_id, label) ユニーク制約があるため別ラベルで保存
        repository.saveAndFlush(buildEntity(2001L, "office"));
        repository.saveAndFlush(buildEntity(2002L, "home"));
        em.clear();

        List<UserWeatherLocationEntity> result = repository.findByUserId(2001L);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(UserWeatherLocationEntity::getLabel)
                .containsExactlyInAnyOrder("home", "office");
    }

    @Test
    @Transactional
    @DisplayName("deleteByUserId — 指定ユーザーの全地点を物理削除する")
    void shouldDeleteByUserId() {
        repository.saveAndFlush(buildEntity(3001L, "home"));
        repository.saveAndFlush(buildEntity(3001L, "office"));
        repository.saveAndFlush(buildEntity(3002L, "home"));
        em.flush();
        em.clear();

        int deleted = repository.deleteByUserId(3001L);
        em.flush();
        em.clear();

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.findByUserId(3001L)).isEmpty();
        assertThat(repository.findByUserId(3002L)).hasSize(1);
    }

    @Test
    @Transactional
    @DisplayName("UUID 型の findById でも取得できる")
    void shouldFindByUuidId() {
        UserWeatherLocationEntity saved = repository.saveAndFlush(buildEntity(4001L, "home"));
        UUID id = saved.getId();
        em.clear();

        Optional<UserWeatherLocationEntity> found = repository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(4001L);
    }

    @Test
    @Transactional
    @DisplayName("既存UUIDv1と新規UUIDv7が混在してもCRUDできる")
    void shouldCrudExistingV1AlongsideNewV7() {
        UUID existingV1 = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        UserWeatherLocationEntity legacy = buildEntity(5001L, "legacy");
        legacy.setId(existingV1);
        repository.saveAndFlush(legacy);

        UserWeatherLocationEntity current = repository.saveAndFlush(buildEntity(5002L, "current"));
        UUID currentV7 = current.getId();
        em.clear();

        assertThat(repository.findById(existingV1)).isPresent();
        assertThat(repository.findById(currentV7)).isPresent();
        assertThat(currentV7.version()).isEqualTo(7);

        UserWeatherLocationEntity reloadedLegacy = repository.findById(existingV1).orElseThrow();
        reloadedLegacy.setPlaceNameSnapshot("更新済み");
        repository.saveAndFlush(reloadedLegacy);
        em.clear();
        assertThat(repository.findById(existingV1).orElseThrow().getPlaceNameSnapshot())
                .isEqualTo("更新済み");

        repository.deleteById(existingV1);
        repository.flush();
        em.clear();
        assertThat(repository.findById(existingV1)).isEmpty();
        assertThat(repository.findById(currentV7)).isPresent();
    }
}
