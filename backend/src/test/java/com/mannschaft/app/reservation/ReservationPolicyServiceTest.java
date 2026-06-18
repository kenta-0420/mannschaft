package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationPolicyRepository;
import com.mannschaft.app.reservation.service.ReservationPolicyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationPolicyService} の単体テスト。
 *
 * <p>承認モードの解決ルール「枠値優先 → チーム設定 → AUTO」と、
 * getOrDefault の既定値・updatePolicy の upsert（新規/更新/部分更新）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationPolicyService 単体テスト")
class ReservationPolicyServiceTest {

    @Mock
    private ReservationPolicyRepository policyRepository;

    @InjectMocks
    private ReservationPolicyService service;

    private static final Long TEAM_ID = 42L;

    @Nested
    @DisplayName("getOrDefault")
    class GetOrDefault {

        @Test
        @DisplayName("設定なし: 既定値(approvalMode=AUTO/cancel=24/remind=24,1)を返す（DB書き込みなし）")
        void 設定なし_既定値() {
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());

            ReservationPolicyEntity result = service.getOrDefault(TEAM_ID);

            assertThat(result.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(result.getApprovalMode()).isEqualTo(ApprovalMode.AUTO);
            assertThat(result.getCancelDeadlineHours()).isEqualTo(24);
            assertThat(result.getRemindBeforeHours()).isEqualTo("24,1");
        }

        @Test
        @DisplayName("設定あり: 永続化済みの値をそのまま返す")
        void 設定あり() {
            ReservationPolicyEntity entity = ReservationPolicyEntity.builder()
                    .teamId(TEAM_ID)
                    .approvalMode(ApprovalMode.MANUAL)
                    .cancelDeadlineHours(48)
                    .remindBeforeHours("72,24")
                    .build();
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(entity));

            ReservationPolicyEntity result = service.getOrDefault(TEAM_ID);

            assertThat(result.getApprovalMode()).isEqualTo(ApprovalMode.MANUAL);
            assertThat(result.getCancelDeadlineHours()).isEqualTo(48);
            assertThat(result.getRemindBeforeHours()).isEqualTo("72,24");
        }
    }

    @Nested
    @DisplayName("resolveApprovalMode（枠値優先 → チーム設定 → AUTO）")
    class ResolveApprovalMode {

        @Test
        @DisplayName("枠に値あり: チーム設定に関わらず枠の値を優先する")
        void 枠値優先() {
            ReservationSlotEntity slot = ReservationSlotEntity.builder()
                    .teamId(TEAM_ID)
                    .approvalMode(ApprovalMode.MANUAL)
                    .build();
            // チーム設定は AUTO だが、枠の MANUAL が優先される。
            // findByTeamId は呼ばれない想定（呼ばれても問題ないが、優先順位を厳密に検証）。

            ApprovalMode resolved = service.resolveApprovalMode(TEAM_ID, slot);

            assertThat(resolved).isEqualTo(ApprovalMode.MANUAL);
        }

        @Test
        @DisplayName("枠が null かつチーム設定あり: チーム設定の承認モードを使う")
        void チーム設定フォールバック() {
            ReservationSlotEntity slot = ReservationSlotEntity.builder()
                    .teamId(TEAM_ID)
                    .approvalMode(null)
                    .build();
            ReservationPolicyEntity policy = ReservationPolicyEntity.builder()
                    .teamId(TEAM_ID)
                    .approvalMode(ApprovalMode.MANUAL)
                    .build();
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(policy));

            ApprovalMode resolved = service.resolveApprovalMode(TEAM_ID, slot);

            assertThat(resolved).isEqualTo(ApprovalMode.MANUAL);
        }

        @Test
        @DisplayName("枠が null かつチーム設定なし: AUTO へフォールバックする")
        void AUTOフォールバック() {
            ReservationSlotEntity slot = ReservationSlotEntity.builder()
                    .teamId(TEAM_ID)
                    .approvalMode(null)
                    .build();
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());

            ApprovalMode resolved = service.resolveApprovalMode(TEAM_ID, slot);

            assertThat(resolved).isEqualTo(ApprovalMode.AUTO);
        }

        @Test
        @DisplayName("slot 自体が null: チーム設定（なければ AUTO）へフォールバックする")
        void slotがnull() {
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());

            ApprovalMode resolved = service.resolveApprovalMode(TEAM_ID, null);

            assertThat(resolved).isEqualTo(ApprovalMode.AUTO);
        }
    }

    @Nested
    @DisplayName("updatePolicy (upsert)")
    class UpdatePolicy {

        @Test
        @DisplayName("新規: レコードがなければ指定値で作成する")
        void 新規作成() {
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(policyRepository.save(any(ReservationPolicyEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.updatePolicy(TEAM_ID, ApprovalMode.MANUAL, 48, "72,24");

            ArgumentCaptor<ReservationPolicyEntity> captor =
                    ArgumentCaptor.forClass(ReservationPolicyEntity.class);
            verify(policyRepository).save(captor.capture());
            assertThat(captor.getValue().getTeamId()).isEqualTo(TEAM_ID);
            assertThat(captor.getValue().getApprovalMode()).isEqualTo(ApprovalMode.MANUAL);
            assertThat(captor.getValue().getCancelDeadlineHours()).isEqualTo(48);
            assertThat(captor.getValue().getRemindBeforeHours()).isEqualTo("72,24");
        }

        @Test
        @DisplayName("新規 + 一部 null: 渡さなかったフィールドは既定値で作成する")
        void 新規_部分指定で既定補完() {
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(policyRepository.save(any(ReservationPolicyEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // approvalMode だけ指定。cancel/remind は null → 既定（24 / "24,1"）になる。
            service.updatePolicy(TEAM_ID, ApprovalMode.MANUAL, null, null);

            ArgumentCaptor<ReservationPolicyEntity> captor =
                    ArgumentCaptor.forClass(ReservationPolicyEntity.class);
            verify(policyRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovalMode()).isEqualTo(ApprovalMode.MANUAL);
            assertThat(captor.getValue().getCancelDeadlineHours()).isEqualTo(24);
            assertThat(captor.getValue().getRemindBeforeHours()).isEqualTo("24,1");
        }

        @Test
        @DisplayName("更新: 既存レコードがあれば値を更新する")
        void 既存更新() {
            ReservationPolicyEntity existing = ReservationPolicyEntity.builder()
                    .teamId(TEAM_ID)
                    .approvalMode(ApprovalMode.AUTO)
                    .cancelDeadlineHours(24)
                    .remindBeforeHours("24,1")
                    .build();
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(existing));
            given(policyRepository.save(any(ReservationPolicyEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.updatePolicy(TEAM_ID, ApprovalMode.MANUAL, 48, null);

            // null を渡した remindBeforeHours は据え置き、他は更新される。
            assertThat(existing.getApprovalMode()).isEqualTo(ApprovalMode.MANUAL);
            assertThat(existing.getCancelDeadlineHours()).isEqualTo(48);
            assertThat(existing.getRemindBeforeHours()).isEqualTo("24,1");
            verify(policyRepository).save(existing);
        }
    }
}
