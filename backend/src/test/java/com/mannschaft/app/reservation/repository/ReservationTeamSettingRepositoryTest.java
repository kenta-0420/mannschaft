package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReservationTeamSettingRepository} 番人テスト。
 *
 * <p>以下を検証する:</p>
 * <ul>
 *   <li>テーブルが正しく作成され Entity とマッピング整合していること（from-scratch確認）</li>
 *   <li>{@code allow_public_reservation} の DEFAULT が {@code false} であること</li>
 *   <li>{@link ReservationTeamSettingRepository#findByTeamId} が正しく動作すること</li>
 *   <li>{@link ReservationTeamSettingRepository#existsByTeamId} が正しく動作すること</li>
 * </ul>
 */
@Transactional
@DisplayName("ReservationTeamSettingRepository 番人テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationTeamSettingRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationTeamSettingRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long TEAM_A = 1001L;
    private static final Long TEAM_B = 1002L;

    /** テスト用の設定エンティティを永続化して返す。 */
    private ReservationTeamSettingEntity persistSetting(Long teamId, boolean allowPublicReservation) {
        ReservationTeamSettingEntity entity = ReservationTeamSettingEntity.builder()
                .teamId(teamId)
                .allowPublicReservation(allowPublicReservation)
                .build();
        em.persist(entity);
        em.flush();
        em.clear();
        return entity;
    }

    // ========================================
    // テーブル作成 / マッピング整合
    // ========================================

    @Nested
    @DisplayName("テーブル作成 / マッピング整合（from-scratch番人）")
    class TableMapping {

        @Test
        @DisplayName("エンティティを永続化できる（テーブル・カラム定義が整合している）")
        void 永続化できる_テーブルマッピング整合() {
            // When: 永続化
            ReservationTeamSettingEntity entity = persistSetting(TEAM_A, true);

            // Then: IDが自動採番されUUIDv7として設定されている
            assertThat(entity.getId()).isNotNull();

            // Then: teamIdが正しく保存されている
            Optional<ReservationTeamSettingEntity> found = repository.findByTeamId(TEAM_A);
            assertThat(found).isPresent();
            assertThat(found.get().getTeamId()).isEqualTo(TEAM_A);
            assertThat(found.get().isAllowPublicReservation()).isTrue();
        }

        @Test
        @DisplayName("監査カラム（created_at / updated_at）が自動セットされる")
        void 監査カラム_自動セット() {
            // When
            persistSetting(TEAM_A, false);

            // Then
            ReservationTeamSettingEntity found = repository.findByTeamId(TEAM_A).orElseThrow();
            assertThat(found.getCreatedAt()).isNotNull();
            assertThat(found.getUpdatedAt()).isNotNull();
        }
    }

    // ========================================
    // allow_public_reservation デフォルト値
    // ========================================

    @Nested
    @DisplayName("allow_public_reservation デフォルト値検証（既定値番人）")
    class DefaultValue {

        @Test
        @DisplayName("Builderでallow_public_reservationを指定しない場合はfalseになる")
        void allowPublicReservation未指定_falseになる() {
            // Given: allowPublicReservation を明示せずビルド
            ReservationTeamSettingEntity entity = ReservationTeamSettingEntity.builder()
                    .teamId(TEAM_A)
                    .build();
            em.persist(entity);
            em.flush();
            em.clear();

            // When
            ReservationTeamSettingEntity found = repository.findByTeamId(TEAM_A).orElseThrow();

            // Then: デフォルトはfalse
            assertThat(found.isAllowPublicReservation()).isFalse();
        }

        @Test
        @DisplayName("allow_public_reservation=falseで明示的に保存できる")
        void allowPublicReservation_false明示保存() {
            // When
            persistSetting(TEAM_A, false);

            // Then
            ReservationTeamSettingEntity found = repository.findByTeamId(TEAM_A).orElseThrow();
            assertThat(found.isAllowPublicReservation()).isFalse();
        }

        @Test
        @DisplayName("allow_public_reservation=trueで保存できる")
        void allowPublicReservation_true保存() {
            // When
            persistSetting(TEAM_A, true);

            // Then
            ReservationTeamSettingEntity found = repository.findByTeamId(TEAM_A).orElseThrow();
            assertThat(found.isAllowPublicReservation()).isTrue();
        }
    }

    // ========================================
    // findByTeamId
    // ========================================

    @Nested
    @DisplayName("findByTeamId")
    class FindByTeamId {

        @Test
        @DisplayName("存在するチームIDでOptional.presentが返る")
        void 存在するteamId_present返却() {
            // Given
            persistSetting(TEAM_A, false);

            // When
            Optional<ReservationTeamSettingEntity> result = repository.findByTeamId(TEAM_A);

            // Then
            assertThat(result).isPresent();
            assertThat(result.get().getTeamId()).isEqualTo(TEAM_A);
        }

        @Test
        @DisplayName("存在しないチームIDでOptional.emptyが返る")
        void 存在しないteamId_empty返却() {
            // When
            Optional<ReservationTeamSettingEntity> result = repository.findByTeamId(9999L);

            // Then: レコードなし → empty（サービス層でfalse扱いとする）
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("別チームのレコードと混在しても正しいチームの設定が返る")
        void 複数チーム混在_正しいチームが返る() {
            // Given
            persistSetting(TEAM_A, false);
            persistSetting(TEAM_B, true);

            // When
            Optional<ReservationTeamSettingEntity> resultA = repository.findByTeamId(TEAM_A);
            Optional<ReservationTeamSettingEntity> resultB = repository.findByTeamId(TEAM_B);

            // Then
            assertThat(resultA).isPresent();
            assertThat(resultA.get().isAllowPublicReservation()).isFalse();
            assertThat(resultB).isPresent();
            assertThat(resultB.get().isAllowPublicReservation()).isTrue();
        }
    }

    // ========================================
    // existsByTeamId
    // ========================================

    @Nested
    @DisplayName("existsByTeamId")
    class ExistsByTeamId {

        @Test
        @DisplayName("設定が存在するチームIDでtrueが返る")
        void 設定あり_true返却() {
            // Given
            persistSetting(TEAM_A, false);

            // When & Then
            assertThat(repository.existsByTeamId(TEAM_A)).isTrue();
        }

        @Test
        @DisplayName("設定が存在しないチームIDでfalseが返る")
        void 設定なし_false返却() {
            // When & Then
            assertThat(repository.existsByTeamId(9999L)).isFalse();
        }
    }

    // ========================================
    // updateAllowPublicReservation
    // ========================================

    @Nested
    @DisplayName("updateAllowPublicReservation（Entityメソッド）")
    class UpdateAllowPublicReservation {

        @Test
        @DisplayName("false→trueに更新できる")
        void false_to_true更新() {
            // Given
            persistSetting(TEAM_A, false);
            ReservationTeamSettingEntity entity = repository.findByTeamId(TEAM_A).orElseThrow();

            // When
            entity.updateAllowPublicReservation(true);
            em.flush();
            em.clear();

            // Then
            ReservationTeamSettingEntity updated = repository.findByTeamId(TEAM_A).orElseThrow();
            assertThat(updated.isAllowPublicReservation()).isTrue();
            // updated_at も更新されていること
            assertThat(updated.getUpdatedAt()).isNotNull();
        }
    }
}
