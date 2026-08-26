package com.mannschaft.app.reservation.service;

import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.service.TeamPlanService;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.CreateNotificationRecipientRequest;
import com.mannschaft.app.reservation.dto.NotificationRecipientListResponse;
import com.mannschaft.app.reservation.dto.UpdateNotificationRecipientRequest;
import com.mannschaft.app.reservation.entity.ReservationNotificationRecipientEntity;
import com.mannschaft.app.reservation.repository.ReservationNotificationRecipientRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReservationNotificationRecipientService} のフリーミアム件数ゲート単体テスト（機能D・§8 D-3〜D-7）。
 *
 * <p>受け入れ条件:</p>
 * <ul>
 *   <li>D-3: 無料で 3 件あるとき 4 件目 → RESERVATION_029（402）</li>
 *   <li>D-4: 10 件あるとき 11 件目 → RESERVATION_028（400・有料でも 10 件超は不可）</li>
 *   <li>D-5: email 重複 → RESERVATION_030（409）</li>
 *   <li>D-7: 件数ゲートは有効・無効を問わず全登録行で数える（{@code countByTeamId}）</li>
 *   <li>isEnabled=null は Service 層で true 正規化</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationNotificationRecipientService フリーミアム件数ゲート (機能D)")
class ReservationNotificationRecipientServiceTest {

    private static final Long TEAM_ID = 42L;
    private static final Long CREATED_BY = 7L;

    @Mock
    private ReservationNotificationRecipientRepository recipientRepository;

    @Mock
    private TeamPlanService teamPlanService;

    @Mock
    private EntitlementQueryService entitlementQueryService;

    @InjectMocks
    private ReservationNotificationRecipientService service;

    private CreateNotificationRecipientRequest req(String email, Boolean enabled) {
        return new CreateNotificationRecipientRequest(email, "ラベル", enabled);
    }

    @Nested
    @DisplayName("追加（フリーミアム件数ゲート）")
    class AddRecipient {

        @Test
        @DisplayName("無料プランで2件登録済み → 3件目は登録できる（境界・3件目まで可）")
        void 無料3件目まで可() {
            // count=2 は FREE(3) 未満のため hasPaidPlan は評価されない（短絡）→ 有料判定の stub は置かない。
            when(recipientRepository.countByTeamId(TEAM_ID)).thenReturn(2L);
            when(recipientRepository.existsByTeamIdAndEmail(TEAM_ID, "a@example.com")).thenReturn(false);
            when(recipientRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

            service.addRecipient(TEAM_ID, req("a@example.com", true), CREATED_BY);

            verify(recipientRepository).saveAndFlush(any());
        }

        @Test
        @DisplayName("AC-15/D-3: 無権利チームで3件登録済み → 4件目は RESERVATION_029（402）維持")
        void 無料4件目は402() {
            // F20.1: 有料判定を isEntitled(TEAM, extended) に置換。無権利=false → 従来どおり 402。
            when(recipientRepository.countByTeamId(TEAM_ID)).thenReturn(3L);
            when(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, TEAM_ID,
                    FeatureKeys.RESERVATION_NOTIFICATION_RECIPIENTS_EXTENDED)).thenReturn(false);

            assertThatThrownBy(() -> service.addRecipient(TEAM_ID, req("a@example.com", true), CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ReservationErrorCode.NOTIFY_RECIPIENT_PAID_PLAN_REQUIRED));

            verify(recipientRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("AC-C1: extended entitlement 保持チームで3件登録済み → 4件目は登録できる（有料は10件まで）")
        void extended権利で4件目可() {
            // extended entitlement 保持（既存有料チームはブリッジで FULL 契約→plan_features 全キーを持つ）→ 通過。
            when(recipientRepository.countByTeamId(TEAM_ID)).thenReturn(3L);
            when(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, TEAM_ID,
                    FeatureKeys.RESERVATION_NOTIFICATION_RECIPIENTS_EXTENDED)).thenReturn(true);
            when(recipientRepository.existsByTeamIdAndEmail(TEAM_ID, "a@example.com")).thenReturn(false);
            when(recipientRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

            service.addRecipient(TEAM_ID, req("a@example.com", true), CREATED_BY);

            verify(recipientRepository).saveAndFlush(any());
        }

        @Test
        @DisplayName("AC-C2: extended 付与のみ（team_subscriptions なし）でも4件目は成功・hasPaidPlan は参照しない")
        void extended付与のみで4件目可_hasPaidPlan不参照() {
            // ゲートは isEntitled のみで判定し、旧 teamPlanService.hasPaidPlan には依存しないことを保証する。
            when(recipientRepository.countByTeamId(TEAM_ID)).thenReturn(3L);
            when(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, TEAM_ID,
                    FeatureKeys.RESERVATION_NOTIFICATION_RECIPIENTS_EXTENDED)).thenReturn(true);
            when(recipientRepository.existsByTeamIdAndEmail(TEAM_ID, "a@example.com")).thenReturn(false);
            when(recipientRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

            service.addRecipient(TEAM_ID, req("a@example.com", true), CREATED_BY);

            verify(recipientRepository).saveAndFlush(any());
            verify(teamPlanService, never()).hasPaidPlan(anyLong());
        }

        @Test
        @DisplayName("D-4: 10件登録済み → 11件目は RESERVATION_028（400・有料でも不可）")
        void 上限10件超は有料でも400() {
            when(recipientRepository.countByTeamId(TEAM_ID)).thenReturn(10L);
            // 上限チェックが先に発火するため hasPaidPlan は評価されない（lenient で未使用許容）。
            lenient().when(teamPlanService.hasPaidPlan(TEAM_ID)).thenReturn(true);

            assertThatThrownBy(() -> service.addRecipient(TEAM_ID, req("a@example.com", true), CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ReservationErrorCode.NOTIFY_RECIPIENT_LIMIT_EXCEEDED));

            verify(recipientRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("D-5: email 重複 → RESERVATION_030（409）")
        void 重複は409() {
            when(recipientRepository.countByTeamId(TEAM_ID)).thenReturn(1L);
            when(recipientRepository.existsByTeamIdAndEmail(TEAM_ID, "dup@example.com")).thenReturn(true);

            assertThatThrownBy(() -> service.addRecipient(TEAM_ID, req("dup@example.com", true), CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ReservationErrorCode.NOTIFY_RECIPIENT_DUPLICATE));

            verify(recipientRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("isEnabled=null は true に正規化して保存する")
        void isEnabledのnullはtrue正規化() {
            when(recipientRepository.countByTeamId(TEAM_ID)).thenReturn(0L);
            when(recipientRepository.existsByTeamIdAndEmail(TEAM_ID, "a@example.com")).thenReturn(false);
            when(recipientRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

            service.addRecipient(TEAM_ID, req("a@example.com", null), CREATED_BY);

            ArgumentCaptor<ReservationNotificationRecipientEntity> captor =
                    ArgumentCaptor.forClass(ReservationNotificationRecipientEntity.class);
            verify(recipientRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getIsEnabled()).isTrue();
        }

        @Test
        @DisplayName("isEnabled=false は false のまま保存する")
        void isEnabledのfalseは保持() {
            when(recipientRepository.countByTeamId(TEAM_ID)).thenReturn(0L);
            when(recipientRepository.existsByTeamIdAndEmail(TEAM_ID, "a@example.com")).thenReturn(false);
            when(recipientRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

            service.addRecipient(TEAM_ID, req("a@example.com", false), CREATED_BY);

            ArgumentCaptor<ReservationNotificationRecipientEntity> captor =
                    ArgumentCaptor.forClass(ReservationNotificationRecipientEntity.class);
            verify(recipientRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getIsEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("一覧（フリーミアム状態）")
    class ListRecipients {

        @Test
        @DisplayName("D-7: enabledCount は有効のみ・totalCount は無効も含めて数える")
        void 件数カウントは有効無効を区別する() {
            ReservationNotificationRecipientEntity enabled = ReservationNotificationRecipientEntity.builder()
                    .teamId(TEAM_ID).email("on@example.com").isEnabled(true).build();
            ReservationNotificationRecipientEntity disabled = ReservationNotificationRecipientEntity.builder()
                    .teamId(TEAM_ID).email("off@example.com").isEnabled(false).build();
            when(recipientRepository.findByTeamIdOrderByCreatedAtAsc(TEAM_ID))
                    .thenReturn(List.of(enabled, disabled));
            when(teamPlanService.hasPaidPlan(TEAM_ID)).thenReturn(false);

            NotificationRecipientListResponse res = service.listRecipients(TEAM_ID);

            assertThat(res.getTotalCount()).isEqualTo(2);
            assertThat(res.getEnabledCount()).isEqualTo(1);
            assertThat(res.getFreeLimit()).isEqualTo(ReservationNotificationRecipientService.FREE_RECIPIENT_LIMIT);
            assertThat(res.getMaxLimit()).isEqualTo(ReservationNotificationRecipientService.MAX_RECIPIENT_LIMIT);
            assertThat(res.isHasPaidPlan()).isFalse();
        }
    }

    @Nested
    @DisplayName("更新・削除（存在確認）")
    class UpdateDelete {

        @Test
        @DisplayName("存在しない宛先の更新は RESERVATION_031（404）")
        void 更新対象不在は404() {
            UUID id = UUID.randomUUID();
            when(recipientRepository.findByIdAndTeamId(id, TEAM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateRecipient(
                    TEAM_ID, id, new UpdateNotificationRecipientRequest("x", false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ReservationErrorCode.NOTIFY_RECIPIENT_NOT_FOUND));
        }

        @Test
        @DisplayName("存在しない宛先の削除は RESERVATION_031（404）")
        void 削除対象不在は404() {
            UUID id = UUID.randomUUID();
            when(recipientRepository.findByIdAndTeamId(id, TEAM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteRecipient(TEAM_ID, id))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ReservationErrorCode.NOTIFY_RECIPIENT_NOT_FOUND));
        }
    }
}
