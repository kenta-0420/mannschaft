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

            service.updatePolicy(TEAM_ID, ApprovalMode.MANUAL, 48, "72,24", null, null);

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
            service.updatePolicy(TEAM_ID, ApprovalMode.MANUAL, null, null, null, null);

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

            service.updatePolicy(TEAM_ID, ApprovalMode.MANUAL, 48, null, null, null);

            // null を渡した remindBeforeHours は据え置き、他は更新される。
            assertThat(existing.getApprovalMode()).isEqualTo(ApprovalMode.MANUAL);
            assertThat(existing.getCancelDeadlineHours()).isEqualTo(48);
            assertThat(existing.getRemindBeforeHours()).isEqualTo("24,1");
            verify(policyRepository).save(existing);
        }
    }

    /**
     * 仮押さえ自動失効時間の「値の設定」と「無効化（NULL 化）」の優先規則（F03.4.5 §6.3・AC-6-1）。
     *
     * <p>Controller テストは「優先ルールは Service/Entity 側の単一実装が担う」ことを前提に
     * 素通し（両方をそのまま渡すこと）だけを検査している。その<b>委譲先</b>を検査するのが本 Nested。
     * これが無いと {@code ReservationPolicyEntity#updatePolicy} の if 2 本を入れ替えても
     * 全テストが緑のまま通ってしまう（＝優先規則が実質無検査になる）。</p>
     */
    @Nested
    @DisplayName("updatePolicy — 仮押さえ自動失効の clear 優先規則")
    class UpdatePolicyPendingExpire {

        private ReservationPolicyEntity existingWith(Integer pendingExpireHours) {
            return ReservationPolicyEntity.builder()
                    .teamId(TEAM_ID)
                    .approvalMode(ApprovalMode.AUTO)
                    .cancelDeadlineHours(24)
                    .remindBeforeHours("24,1")
                    .pendingExpireHours(pendingExpireHours)
                    .build();
        }

        @Test
        @DisplayName("(a) 既存行に値48とclear=trueを同時指定 → clear が勝ち NULL になる")
        void 既存行_値とclear同時指定はclearが勝つ() {
            ReservationPolicyEntity existing = existingWith(24);
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(existing));
            given(policyRepository.save(any(ReservationPolicyEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.updatePolicy(TEAM_ID, null, null, null, 48, true);

            assertThat(existing.getPendingExpireHours())
                    .as("「無効化したい」意図の方が強い。48 が残ると自動失効が止まらない")
                    .isNull();
        }

        @Test
        @DisplayName("(b) 既存行に値48・clear=null → 48 が設定される")
        void 既存行_値のみ指定は値が入る() {
            ReservationPolicyEntity existing = existingWith(24);
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(existing));
            given(policyRepository.save(any(ReservationPolicyEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.updatePolicy(TEAM_ID, null, null, null, 48, null);

            assertThat(existing.getPendingExpireHours()).isEqualTo(48);
        }

        @Test
        @DisplayName("(c) 新規行で値48とclear=trueを同時指定 → NULL で作成される（既定24へ戻らない）")
        void 新規行_値とclear同時指定はNULLで作成される() {
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(policyRepository.save(any(ReservationPolicyEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.updatePolicy(TEAM_ID, null, null, null, 48, true);

            ArgumentCaptor<ReservationPolicyEntity> captor =
                    ArgumentCaptor.forClass(ReservationPolicyEntity.class);
            verify(policyRepository).save(captor.capture());
            assertThat(captor.getValue().getPendingExpireHours())
                    .as("@Builder.Default の 24 に戻ると『無効化したのに失効し続ける』ことになる")
                    .isNull();
        }

        @Test
        @DisplayName("(d) 既存行に clear=false のみ → 据え置き（false は無効化ではない）")
        void 既存行_clearFalseは据え置き() {
            ReservationPolicyEntity existing = existingWith(36);
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(existing));
            given(policyRepository.save(any(ReservationPolicyEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.updatePolicy(TEAM_ID, null, null, null, null, false);

            assertThat(existing.getPendingExpireHours()).isEqualTo(36);
        }

        @Test
        @DisplayName("(e) 新規行で無指定 → 既定 24 で作成される（DB DEFAULT と一致）")
        void 新規行_無指定は既定24() {
            given(policyRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(policyRepository.save(any(ReservationPolicyEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.updatePolicy(TEAM_ID, ApprovalMode.MANUAL, null, null, null, null);

            ArgumentCaptor<ReservationPolicyEntity> captor =
                    ArgumentCaptor.forClass(ReservationPolicyEntity.class);
            verify(policyRepository).save(captor.capture());
            assertThat(captor.getValue().getPendingExpireHours())
                    .isEqualTo(ReservationPolicyEntity.DEFAULT_PENDING_EXPIRE_HOURS);
        }
    }
}
