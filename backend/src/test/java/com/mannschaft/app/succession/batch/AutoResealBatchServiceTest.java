package com.mannschaft.app.succession.batch;

import com.mannschaft.app.succession.entity.SuccessionPreRegistrationEntity;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;
import com.mannschaft.app.succession.repository.SuccessionPreRegistrationRepository;
import com.mannschaft.app.succession.repository.UnsealRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AutoResealBatchService} のユニットテスト（F09.15 S2-D）。
 *
 * <p>外部依存（Repository）はすべて Mockito スタブ化する。
 * 期限切れ申請の RE_SEALED 遷移・エラー時の処理継続を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AutoResealBatchService")
class AutoResealBatchServiceTest {

    @Mock
    private UnsealRequestRepository unsealRequestRepo;
    @Mock
    private SuccessionPreRegistrationRepository preRegRepo;

    @InjectMocks
    private AutoResealBatchService service;

    // ─── 期限切れレコードなし ────────────────────────────

    @Test
    @DisplayName("期限切れレコードなし: findBy... が空リストを返すと save は一切呼ばれない")
    void autoReseal_no_expired_records() {
        when(unsealRequestRepo.findByAutoResealAtBeforeAndReSealedAtIsNullAndDeletedAtIsNull(any()))
                .thenReturn(List.of());

        service.autoReseal();

        verify(unsealRequestRepo, never()).save(any());
        verify(preRegRepo, never()).save(any());
    }

    // ─── 正常系（1件）───────────────────────────────────

    @Test
    @DisplayName("正常系(1件): 期限切れ申請に対して reSealedAt がセットされ preReg が RE_SEALED に遷移する")
    void autoReseal_single_record_success() {
        UUID preRegId = UUID.randomUUID();
        Long orgId = 100L;
        LocalDateTime expiredAt = LocalDateTime.now().minusHours(1);

        UnsealRequestEntity req = UnsealRequestEntity.builder()
                .organizationId(orgId)
                .dwellingUnitId(200L)
                .residentRegistryId(300L)
                .preRegistrationId(preRegId)
                .requestedBy(1001L)
                .requestReason("理由")
                .autoResealAt(expiredAt)
                .build();
        setField(req, "id", UUID.randomUUID());

        SuccessionPreRegistrationEntity preReg = SuccessionPreRegistrationEntity.builder()
                .organizationId(orgId)
                .dwellingUnitId(200L)
                .residentRegistryId(300L)
                .ownerUserId(1001L)
                .sealStatus("UNSEALED")
                .autoResealAt(expiredAt)
                .build();
        setField(preReg, "id", preRegId);

        when(unsealRequestRepo.findByAutoResealAtBeforeAndReSealedAtIsNullAndDeletedAtIsNull(any()))
                .thenReturn(List.of(req));
        when(unsealRequestRepo.save(any(UnsealRequestEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(preRegId, orgId))
                .thenReturn(Optional.of(preReg));
        when(preRegRepo.save(any(SuccessionPreRegistrationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.autoReseal();

        // UnsealRequest の reSealedAt がセットされている
        ArgumentCaptor<UnsealRequestEntity> reqCaptor =
                ArgumentCaptor.forClass(UnsealRequestEntity.class);
        verify(unsealRequestRepo).save(reqCaptor.capture());
        assertThat(reqCaptor.getValue().getReSealedAt()).isNotNull();

        // preReg の sealStatus が RE_SEALED・autoResealAt が null になっている
        ArgumentCaptor<SuccessionPreRegistrationEntity> preRegCaptor =
                ArgumentCaptor.forClass(SuccessionPreRegistrationEntity.class);
        verify(preRegRepo).save(preRegCaptor.capture());
        assertThat(preRegCaptor.getValue().getSealStatus()).isEqualTo("RE_SEALED");
        assertThat(preRegCaptor.getValue().getAutoResealAt()).isNull();
    }

    // ─── 複数件 ──────────────────────────────────────────

    @Test
    @DisplayName("複数件: 2件の期限切れ申請がいずれも処理される")
    void autoReseal_multiple_records() {
        Long orgId = 100L;

        UUID preRegId1 = UUID.randomUUID();
        UUID preRegId2 = UUID.randomUUID();

        UnsealRequestEntity req1 = buildUnsealRequest(orgId, preRegId1);
        UnsealRequestEntity req2 = buildUnsealRequest(orgId, preRegId2);

        SuccessionPreRegistrationEntity preReg1 = buildUnsealedPreReg(orgId, preRegId1);
        SuccessionPreRegistrationEntity preReg2 = buildUnsealedPreReg(orgId, preRegId2);

        when(unsealRequestRepo.findByAutoResealAtBeforeAndReSealedAtIsNullAndDeletedAtIsNull(any()))
                .thenReturn(List.of(req1, req2));
        when(unsealRequestRepo.save(any(UnsealRequestEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(preRegId1, orgId))
                .thenReturn(Optional.of(preReg1));
        when(preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(preRegId2, orgId))
                .thenReturn(Optional.of(preReg2));
        when(preRegRepo.save(any(SuccessionPreRegistrationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.autoReseal();

        // 2件の UnsealRequest と 2件の preReg が保存されている
        verify(unsealRequestRepo, times(2)).save(any(UnsealRequestEntity.class));
        verify(preRegRepo, times(2)).save(any(SuccessionPreRegistrationEntity.class));
    }

    // ─── エラー時も継続 ──────────────────────────────────

    @Test
    @DisplayName("エラー時も継続: 1件目で preReg が見つからなくても 2件目は正常に処理される")
    void autoReseal_continues_on_error() {
        Long orgId = 100L;

        UUID preRegId1 = UUID.randomUUID();
        UUID preRegId2 = UUID.randomUUID();

        UnsealRequestEntity req1 = buildUnsealRequest(orgId, preRegId1);
        UnsealRequestEntity req2 = buildUnsealRequest(orgId, preRegId2);

        SuccessionPreRegistrationEntity preReg2 = buildUnsealedPreReg(orgId, preRegId2);

        when(unsealRequestRepo.findByAutoResealAtBeforeAndReSealedAtIsNullAndDeletedAtIsNull(any()))
                .thenReturn(List.of(req1, req2));
        when(unsealRequestRepo.save(any(UnsealRequestEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 1件目: preReg が見つからない（Optional.empty()）
        when(preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(preRegId1, orgId))
                .thenReturn(Optional.empty());
        // 2件目: 正常
        when(preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(preRegId2, orgId))
                .thenReturn(Optional.of(preReg2));
        when(preRegRepo.save(any(SuccessionPreRegistrationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 例外が throw されないこと（バッチはエラーを飲み込んで継続する）
        service.autoReseal();

        // 2件の UnsealRequest は保存され（reSealedAt セット）
        verify(unsealRequestRepo, times(2)).save(any(UnsealRequestEntity.class));

        // preReg の保存は2件目のみ（1件目は Optional.empty() なので ifPresent がスキップ）
        ArgumentCaptor<SuccessionPreRegistrationEntity> preRegCaptor =
                ArgumentCaptor.forClass(SuccessionPreRegistrationEntity.class);
        verify(preRegRepo, times(1)).save(preRegCaptor.capture());
        assertThat(preRegCaptor.getValue().getSealStatus()).isEqualTo("RE_SEALED");
    }

    // ─── ヘルパー ──────────────────────────────────────

    private UnsealRequestEntity buildUnsealRequest(Long orgId, UUID preRegId) {
        UnsealRequestEntity req = UnsealRequestEntity.builder()
                .organizationId(orgId)
                .dwellingUnitId(200L)
                .residentRegistryId(300L)
                .preRegistrationId(preRegId)
                .requestedBy(1001L)
                .requestReason("理由")
                .autoResealAt(LocalDateTime.now().minusHours(1))
                .build();
        setField(req, "id", UUID.randomUUID());
        return req;
    }

    private SuccessionPreRegistrationEntity buildUnsealedPreReg(Long orgId, UUID id) {
        SuccessionPreRegistrationEntity preReg = SuccessionPreRegistrationEntity.builder()
                .organizationId(orgId)
                .dwellingUnitId(200L)
                .residentRegistryId(300L)
                .ownerUserId(1001L)
                .sealStatus("UNSEALED")
                .autoResealAt(LocalDateTime.now().minusHours(1))
                .build();
        setField(preReg, "id", id);
        return preReg;
    }

    private static void setField(Object target, String fieldName, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
