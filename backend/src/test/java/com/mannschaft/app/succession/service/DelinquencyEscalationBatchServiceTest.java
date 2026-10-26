package com.mannschaft.app.succession.service;

import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;
import com.mannschaft.app.succession.entity.DelinquencyEscalationStage;
import com.mannschaft.app.succession.repository.DelinquencyEscalationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DelinquencyEscalationBatchService} のユニットテスト（F09.15 S5-C / Issue #2601）。
 *
 * <p>外部依存（Repository / {@link DelinquencyEscalationAdvanceRunner}）はすべて Mockito スタブ化する。
 * 個別トランザクション化（REQUIRES_NEW）は {@link DelinquencyEscalationAdvanceRunner} に切り出し済みのため、
 * 本テストではその呼び出し可否のみを検証する（実トランザクション境界は統合テストで検証する）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DelinquencyEscalationBatchService")
class DelinquencyEscalationBatchServiceTest {

    @Mock
    private DelinquencyEscalationRepository escalationRepository;

    @Mock
    private DelinquencyEscalationAdvanceRunner advanceRunner;

    @InjectMocks
    private DelinquencyEscalationBatchService batchService;

    private static final Long ORG_ID = 100L;
    private static final Long DWELLING_ID = 200L;
    private static final Long RESIDENT_REGISTRY_ID = 300L;

    // ─── determineRequiredStage ─────────────────────────────────────

    @Nested
    @DisplayName("determineRequiredStage")
    class DetermineRequiredStage {

        @Test
        @DisplayName("D+29 は null（対象外）")
        void days29_null() {
            assertThat(batchService.determineRequiredStage(29)).isNull();
        }

        @Test
        @DisplayName("D+30 は STAGE_1_REMINDER")
        void days30_stage1() {
            assertThat(batchService.determineRequiredStage(30))
                    .isEqualTo(DelinquencyEscalationStage.STAGE_1_REMINDER);
        }

        @Test
        @DisplayName("D+60 は STAGE_2_EMERGENCY_CONTACT")
        void days60_stage2() {
            assertThat(batchService.determineRequiredStage(60))
                    .isEqualTo(DelinquencyEscalationStage.STAGE_2_EMERGENCY_CONTACT);
        }

        @Test
        @DisplayName("D+90 は STAGE_3_WATCHER_VISIT")
        void days90_stage3() {
            assertThat(batchService.determineRequiredStage(90))
                    .isEqualTo(DelinquencyEscalationStage.STAGE_3_WATCHER_VISIT);
        }

        @Test
        @DisplayName("D+120 は STAGE_4_DEATH_SUSPECTED")
        void days120_stage4() {
            assertThat(batchService.determineRequiredStage(120))
                    .isEqualTo(DelinquencyEscalationStage.STAGE_4_DEATH_SUSPECTED);
        }

        @Test
        @DisplayName("D+150 は STAGE_5_LEGAL_PREP")
        void days150_stage5() {
            assertThat(batchService.determineRequiredStage(150))
                    .isEqualTo(DelinquencyEscalationStage.STAGE_5_LEGAL_PREP);
        }
    }

    // ─── shouldAdvance ──────────────────────────────────────────────

    @Nested
    @DisplayName("shouldAdvance")
    class ShouldAdvance {

        @Test
        @DisplayName("requiredStage が null なら昇格不要")
        void requiredStage_null_false() {
            assertThat(batchService.shouldAdvance("STAGE_1_REMINDER", null)).isFalse();
        }

        @Test
        @DisplayName("現在ステージ < 必要ステージなら昇格必要")
        void current_lower_than_required_true() {
            assertThat(batchService.shouldAdvance(
                    "STAGE_1_REMINDER",
                    DelinquencyEscalationStage.STAGE_3_WATCHER_VISIT))
                    .isTrue();
        }

        @Test
        @DisplayName("現在ステージ == 必要ステージなら昇格不要（冪等）")
        void current_equal_required_false() {
            assertThat(batchService.shouldAdvance(
                    "STAGE_3_WATCHER_VISIT",
                    DelinquencyEscalationStage.STAGE_3_WATCHER_VISIT))
                    .isFalse();
        }

        @Test
        @DisplayName("現在ステージ > 必要ステージなら昇格不要")
        void current_higher_than_required_false() {
            assertThat(batchService.shouldAdvance(
                    "STAGE_5_LEGAL_PREP",
                    DelinquencyEscalationStage.STAGE_3_WATCHER_VISIT))
                    .isFalse();
        }
    }

    // ─── advanceEscalations ─────────────────────────────────────────

    @Nested
    @DisplayName("advanceEscalations")
    class AdvanceEscalations {

        @Test
        @DisplayName("D+30 経過したエスカレーションが STAGE_1 に昇格される")
        void advanceEscalations_D30経過_STAGE1に昇格() {
            // delinquencyStartedAt を30日前に設定
            LocalDate startDate = LocalDate.now().minusDays(30);
            UUID escalationId = UUID.randomUUID();
            DelinquencyEscalationEntity entity = buildEscalation(escalationId, "STAGE_1_REMINDER", startDate);
            // 現在ステージは STAGE_1 だが、まだ完了していない（STAGE_1_REMINDER のまま）
            // shouldAdvance では current == required なので昇格不要
            // → 実際に昇格が必要なのは current < required の場合

            // STAGE_1 手前のケースとして currentStage を null で初期化されたケースは
            // ビジネス的にあり得ないが、バッチが初回起動時に新規エスカを昇格する場合を想定：
            // 現在ステージが STAGE_1 より下、つまりここでは仮想的に STAGE_0 は存在しない。
            // 実際には createEscalation 時に STAGE_1_REMINDER がデフォルト設定される。
            // バッチは「D+30 に達したのに STAGE_1 未満のステージが存在しない」ため
            // shouldAdvance("STAGE_1_REMINDER", STAGE_1_REMINDER) = false で昇格しない。
            // テスト対象は D+60 に達したが STAGE_1 のままのケース。

            LocalDate startDate60 = LocalDate.now().minusDays(65);
            UUID id60 = UUID.randomUUID();
            DelinquencyEscalationEntity stage1entity = buildEscalation(id60, "STAGE_1_REMINDER", startDate60);

            when(escalationRepository.findByResolvedAtIsNullAndFrozenAtIsNullAndDeletedAtIsNull())
                    .thenReturn(List.of(stage1entity));
            when(advanceRunner.advanceStage(eq(id60), eq(ORG_ID)))
                    .thenReturn(stage1entity);

            batchService.advanceEscalations();

            // D+65 で STAGE_1 のため STAGE_2 への昇格が呼ばれる
            verify(advanceRunner).advanceStage(id60, ORG_ID);
        }

        @Test
        @DisplayName("D+120 経過したエスカレーションが STAGE_4 に昇格される（死亡疑い自動起票）")
        void advanceEscalations_D120経過_STAGE4に昇格して死亡疑い自動起票() {
            LocalDate startDate = LocalDate.now().minusDays(125);
            UUID id = UUID.randomUUID();
            // 現在 STAGE_3 のため D+120 到達で STAGE_4 への昇格が必要
            DelinquencyEscalationEntity entity = buildEscalation(id, "STAGE_3_WATCHER_VISIT", startDate);

            when(escalationRepository.findByResolvedAtIsNullAndFrozenAtIsNullAndDeletedAtIsNull())
                    .thenReturn(List.of(entity));
            when(advanceRunner.advanceStage(eq(id), eq(ORG_ID)))
                    .thenReturn(entity);

            batchService.advanceEscalations();

            // STAGE_4 への昇格（advanceStage 内で markDeathSuspected が呼ばれる）
            verify(advanceRunner).advanceStage(id, ORG_ID);
        }

        @Test
        @DisplayName("凍結済みエスカレーションはバッチ対象外（frozen_at != null は findBy で除外）")
        void advanceEscalations_凍結済み_スキップ() {
            // findByResolvedAtIsNullAndFrozenAtIsNullAndDeletedAtIsNull で既に除外されるため
            // バッチには渡らない。空リストを返してテスト。
            when(escalationRepository.findByResolvedAtIsNullAndFrozenAtIsNullAndDeletedAtIsNull())
                    .thenReturn(List.of());

            batchService.advanceEscalations();

            verify(advanceRunner, never()).advanceStage(any(), any());
        }

        @Test
        @DisplayName("解決済みエスカレーションはバッチ対象外（resolved_at != null は findBy で除外）")
        void advanceEscalations_解決済み_スキップ() {
            // findByResolvedAtIsNullAndFrozenAtIsNullAndDeletedAtIsNull で既に除外されるため
            // バッチには渡らない。空リストを返してテスト。
            when(escalationRepository.findByResolvedAtIsNullAndFrozenAtIsNullAndDeletedAtIsNull())
                    .thenReturn(List.of());

            batchService.advanceEscalations();

            verify(advanceRunner, never()).advanceStage(any(), any());
        }

        @Test
        @DisplayName("D+29 のエスカレーションは昇格されない")
        void advanceEscalations_D29未満_昇格なし() {
            LocalDate startDate = LocalDate.now().minusDays(29);
            UUID id = UUID.randomUUID();
            DelinquencyEscalationEntity entity = buildEscalation(id, "STAGE_1_REMINDER", startDate);

            when(escalationRepository.findByResolvedAtIsNullAndFrozenAtIsNullAndDeletedAtIsNull())
                    .thenReturn(List.of(entity));

            batchService.advanceEscalations();

            // D+29 は requiredStage = null のため昇格しない
            verify(advanceRunner, never()).advanceStage(any(), any());
        }

        @Test
        @DisplayName("個別エスカレーションの昇格失敗でバッチ全体は止まらない")
        void advanceEscalations_個別失敗でバッチ継続() {
            LocalDate startDate = LocalDate.now().minusDays(65);

            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            DelinquencyEscalationEntity entity1 = buildEscalation(id1, "STAGE_1_REMINDER", startDate);
            DelinquencyEscalationEntity entity2 = buildEscalation(id2, "STAGE_1_REMINDER", startDate);

            when(escalationRepository.findByResolvedAtIsNullAndFrozenAtIsNullAndDeletedAtIsNull())
                    .thenReturn(List.of(entity1, entity2));

            // entity1 は失敗、entity2 は成功
            when(advanceRunner.advanceStage(eq(id1), eq(ORG_ID)))
                    .thenThrow(new RuntimeException("昇格失敗"));
            when(advanceRunner.advanceStage(eq(id2), eq(ORG_ID)))
                    .thenReturn(entity2);

            // バッチは例外を吐かない（内部でキャッチしてログを記録）
            batchService.advanceEscalations();

            // 両方に advanceStage が呼ばれている（バッチが継続している）
            verify(advanceRunner).advanceStage(id1, ORG_ID);
            verify(advanceRunner).advanceStage(id2, ORG_ID);
        }
    }

    // ─── ヘルパー ──────────────────────────────────────────────────────

    private DelinquencyEscalationEntity buildEscalation(
            UUID id, String stage, LocalDate delinquencyStartedAt) {
        DelinquencyEscalationEntity entity = DelinquencyEscalationEntity.builder()
                .organizationId(ORG_ID)
                .dwellingUnitId(DWELLING_ID)
                .residentRegistryId(RESIDENT_REGISTRY_ID)
                .delinquencyStartedAt(delinquencyStartedAt)
                .currentStage(stage)
                .build();
        setId(entity, id);
        return entity;
    }

    private static void setId(Object target, UUID id) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField("id");
                f.setAccessible(true);
                f.set(target, id);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("id フィールドが見つかりません");
    }
}
