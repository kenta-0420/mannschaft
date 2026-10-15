package com.mannschaft.app.succession.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.resident.service.ResidentRegistryService;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;
import com.mannschaft.app.succession.entity.DelinquencyEscalationStage;
import com.mannschaft.app.succession.repository.DelinquencyEscalationRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DelinquencyEscalationService} のユニットテスト（F09.15 S5-C）。
 *
 * <p>外部依存（Repository / ResidentRegistryService）はすべて Mockito スタブ化する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DelinquencyEscalationService")
class DelinquencyEscalationServiceTest {

    @Mock
    private DelinquencyEscalationRepository escalationRepository;

    @Mock
    private ResidentRegistryService residentRegistryService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private DelinquencyEscalationService service;

    private static final Long ORG_ID = 100L;
    private static final Long DWELLING_ID = 200L;
    private static final Long RESIDENT_REGISTRY_ID = 300L;
    private static final Long REQUESTING_USER_ID = 400L;

    // ─── createEscalation ──────────────────────────────────────────────

    @Nested
    @DisplayName("createEscalation")
    class CreateEscalation {

        @Test
        @DisplayName("正常系: 未解決エスカレーションがない場合に新規作成する")
        void createEscalation_新規作成_成功() {
            // 既存エスカ無し
            when(escalationRepository.findByResidentRegistryIdAndDeletedAtIsNull(RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.empty());
            when(escalationRepository.save(any(DelinquencyEscalationEntity.class)))
                    .thenAnswer(inv -> {
                        DelinquencyEscalationEntity e = inv.getArgument(0);
                        setId(e, UUID.randomUUID());
                        return e;
                    });

            DelinquencyEscalationEntity result = service.createEscalation(
                    ORG_ID, RESIDENT_REGISTRY_ID, DWELLING_ID, LocalDate.of(2026, 1, 1));

            assertThat(result).isNotNull();
            assertThat(result.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(result.getResidentRegistryId()).isEqualTo(RESIDENT_REGISTRY_ID);
            assertThat(result.getCurrentStage()).isEqualTo("STAGE_1_REMINDER");
            verify(escalationRepository).save(any(DelinquencyEscalationEntity.class));
        }

        @Test
        @DisplayName("冪等系: 未解決のエスカレーションが既に存在する場合は既存を返す")
        void createEscalation_既存アクティブあり_冪等() {
            DelinquencyEscalationEntity existing = buildEscalation(
                    UUID.randomUUID(), "STAGE_1_REMINDER");

            when(escalationRepository.findByResidentRegistryIdAndDeletedAtIsNull(RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.of(existing));

            DelinquencyEscalationEntity result = service.createEscalation(
                    ORG_ID, RESIDENT_REGISTRY_ID, DWELLING_ID, LocalDate.of(2026, 1, 1));

            // 既存を返すため save は呼ばれない
            verify(escalationRepository, never()).save(any());
            assertThat(result).isSameAs(existing);
        }
    }

    // ─── advanceStage ──────────────────────────────────────────────────

    @Nested
    @DisplayName("advanceStage")
    class AdvanceStage {

        @Test
        @DisplayName("正常系: STAGE_1 から STAGE_2 に進める")
        void advanceStage_STAGE1からSTAGE2に進む() {
            UUID id = UUID.randomUUID();
            DelinquencyEscalationEntity entity = buildEscalation(id, "STAGE_1_REMINDER");

            when(escalationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                    .thenReturn(Optional.of(entity));
            when(escalationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            DelinquencyEscalationEntity result = service.advanceStage(id, ORG_ID);

            assertThat(result.getCurrentStage()).isEqualTo("STAGE_2_EMERGENCY_CONTACT");
            assertThat(result.getStage1CompletedAt()).isNotNull();
            // STAGE_2 到達時は死亡疑い起票が呼ばれない
            verify(residentRegistryService, never()).markDeathSuspected(any());
        }

        @Test
        @DisplayName("正常系: STAGE_3 から STAGE_4 に到達すると死亡疑い自動起票が実行される")
        void advanceStage_STAGE4到達時_死亡疑い自動起票される() {
            UUID id = UUID.randomUUID();
            DelinquencyEscalationEntity entity = buildEscalation(id, "STAGE_3_WATCHER_VISIT");

            when(escalationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                    .thenReturn(Optional.of(entity));
            when(escalationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(residentRegistryService).markDeathSuspected(RESIDENT_REGISTRY_ID);

            DelinquencyEscalationEntity result = service.advanceStage(id, ORG_ID);

            assertThat(result.getCurrentStage()).isEqualTo("STAGE_4_DEATH_SUSPECTED");
            assertThat(result.getStage3CompletedAt()).isNotNull();
            // 死亡疑い起票が呼ばれていること
            verify(residentRegistryService).markDeathSuspected(RESIDENT_REGISTRY_ID);
        }

        @Test
        @DisplayName("準正常系: STAGE_4 到達時に markDeathSuspected が失敗しても昇格は維持される")
        void advanceStage_STAGE4到達時_死亡疑い起票失敗してもエスカレーション維持() {
            UUID id = UUID.randomUUID();
            DelinquencyEscalationEntity entity = buildEscalation(id, "STAGE_3_WATCHER_VISIT");

            when(escalationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                    .thenReturn(Optional.of(entity));
            when(escalationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            // markDeathSuspected が例外を投げる
            doThrow(new RuntimeException("居住者が見つかりません"))
                    .when(residentRegistryService).markDeathSuspected(RESIDENT_REGISTRY_ID);

            // エスカレーション昇格自体は成功する（best-effort）
            DelinquencyEscalationEntity result = service.advanceStage(id, ORG_ID);

            assertThat(result.getCurrentStage()).isEqualTo("STAGE_4_DEATH_SUSPECTED");
            verify(residentRegistryService).markDeathSuspected(RESIDENT_REGISTRY_ID);
        }

        @Test
        @DisplayName("異常系: 最終ステージ（STAGE_5）からの昇格は ESCALATION_ALREADY_FINAL_STAGE")
        void advanceStage_既に最終ステージ_例外() {
            UUID id = UUID.randomUUID();
            DelinquencyEscalationEntity entity = buildEscalation(id, "STAGE_5_LEGAL_PREP");

            when(escalationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                    .thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.advanceStage(id, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.ESCALATION_ALREADY_FINAL_STAGE);
        }

        @Test
        @DisplayName("異常系: 解決済みエスカレーションへの操作は ESCALATION_ALREADY_RESOLVED")
        void advanceStage_解決済み_例外() {
            UUID id = UUID.randomUUID();
            DelinquencyEscalationEntity entity = buildEscalation(id, "STAGE_2_EMERGENCY_CONTACT");
            entity.setResolvedAt(LocalDateTime.now().minusDays(1));

            when(escalationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                    .thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.advanceStage(id, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.ESCALATION_ALREADY_RESOLVED);
        }
    }

    // ─── freeze ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("freeze")
    class Freeze {

        @Test
        @DisplayName("正常系: 凍結処理が実行される")
        void freeze_正常凍結() {
            UUID id = UUID.randomUUID();
            DelinquencyEscalationEntity entity = buildEscalation(id, "STAGE_2_EMERGENCY_CONTACT");

            when(escalationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                    .thenReturn(Optional.of(entity));
            when(escalationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.freeze(id, ORG_ID, "弁護士介入", REQUESTING_USER_ID);

            assertThat(entity.getFrozenAt()).isNotNull();
            assertThat(entity.getFrozenReason()).isEqualTo("弁護士介入");
            verify(escalationRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: 凍結中のエスカレーションへの操作は ESCALATION_FROZEN")
        void freeze_既に凍結済み_例外() {
            UUID id = UUID.randomUUID();
            DelinquencyEscalationEntity entity = buildEscalation(id, "STAGE_2_EMERGENCY_CONTACT");
            entity.setFrozenAt(LocalDateTime.now().minusHours(1));
            entity.setFrozenReason("既存の凍結理由");

            when(escalationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                    .thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.freeze(id, ORG_ID, "新しい理由", REQUESTING_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.ESCALATION_FROZEN);
        }
    }

    // ─── resolve ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("正常系: 解決処理が実行される")
        void resolve_正常解決() {
            UUID id = UUID.randomUUID();
            DelinquencyEscalationEntity entity = buildEscalation(id, "STAGE_3_WATCHER_VISIT");

            when(escalationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                    .thenReturn(Optional.of(entity));
            when(escalationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolve(id, ORG_ID, "PAID", REQUESTING_USER_ID);

            assertThat(entity.getResolvedAt()).isNotNull();
            assertThat(entity.getResolvedReason()).isEqualTo("PAID");
            // 凍結は解除される
            assertThat(entity.getFrozenAt()).isNull();
            verify(escalationRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: 既に解決済みのエスカレーションは ESCALATION_ALREADY_RESOLVED")
        void resolve_既に解決済み_例外() {
            UUID id = UUID.randomUUID();
            DelinquencyEscalationEntity entity = buildEscalation(id, "STAGE_3_WATCHER_VISIT");
            entity.setResolvedAt(LocalDateTime.now().minusDays(1));
            entity.setResolvedReason("PAID");

            when(escalationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                    .thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.resolve(id, ORG_ID, "MANUAL_CLOSE", REQUESTING_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.ESCALATION_ALREADY_RESOLVED);
        }
    }

    // ─── ヘルパー ──────────────────────────────────────────────────────

    private DelinquencyEscalationEntity buildEscalation(UUID id, String stage) {
        DelinquencyEscalationEntity entity = DelinquencyEscalationEntity.builder()
                .organizationId(ORG_ID)
                .dwellingUnitId(DWELLING_ID)
                .residentRegistryId(RESIDENT_REGISTRY_ID)
                .delinquencyStartedAt(LocalDate.of(2026, 1, 1))
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
