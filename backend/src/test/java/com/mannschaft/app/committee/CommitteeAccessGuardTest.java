package com.mannschaft.app.committee;

import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.service.CommitteeAccessGuard;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.committee.error.CommitteeErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link CommitteeAccessGuard} の単体テスト。
 *
 * <p>委員会メンバーシップ・委員会内ロール・招集状の宛先本人性という 3 つの判定軸が、
 * それぞれ<b>実体（{@code committee_members} の現役行 / 招集状エンティティ）</b>に基づくことを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommitteeAccessGuard 単体テスト")
class CommitteeAccessGuardTest {

    private static final Long COMMITTEE_ID = 1L;
    private static final Long OTHER_COMMITTEE_ID = 2L;
    private static final Long USER_ID = 900_000_001L;
    private static final Long OTHER_USER_ID = 900_000_002L;

    @Mock
    private CommitteeMemberRepository committeeMemberRepository;

    @InjectMocks
    private CommitteeAccessGuard guard;

    @Nested
    @DisplayName("requireCommitteeMember / requireCommitteeRole")
    class Membership {

        @Test
        @DisplayName("現役メンバーでなければ COMMON_002 で拒否される")
        void 非メンバーは拒否される() {
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.requireCommitteeMember(COMMITTEE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("現役メンバーならメンバー行が返る")
        void メンバーは通過する() {
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.of(member(CommitteeRole.MEMBER)));

            CommitteeMemberEntity result = guard.requireCommitteeMember(COMMITTEE_ID, USER_ID);

            assertThat(result.getRole()).isEqualTo(CommitteeRole.MEMBER);
        }

        @Test
        @DisplayName("要求ロールを満たさないメンバーは COMMON_002 で拒否される")
        void ロール不足は拒否される() {
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.of(member(CommitteeRole.MEMBER)));

            assertThatThrownBy(() -> guard.requireCommitteeRole(
                    COMMITTEE_ID, USER_ID, CommitteeRole.CHAIR, CommitteeRole.VICE_CHAIR))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("要求ロールのいずれかを満たすメンバーは通過する")
        void ロールを満たすと通過する() {
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.of(member(CommitteeRole.VICE_CHAIR)));

            assertThatCode(() -> guard.requireCommitteeRole(
                    COMMITTEE_ID, USER_ID, CommitteeRole.CHAIR, CommitteeRole.VICE_CHAIR))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("hasCommitteeRole は非メンバーに対して false を返す")
        void hasCommitteeRoleは非メンバーでfalse() {
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThat(guard.hasCommitteeRole(COMMITTEE_ID, USER_ID, CommitteeRole.CHAIR)).isFalse();
        }
    }

    @Nested
    @DisplayName("requireInvitee / requireInvitationCanceller")
    class Invitation {

        @Test
        @DisplayName("宛先でない利用者は COMMON_002 で拒否される")
        void 宛先でない利用者は拒否される() {
            assertThatThrownBy(() -> guard.requireInvitee(invitation(COMMITTEE_ID, OTHER_USER_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("宛先本人は通過する")
        void 宛先本人は通過する() {
            assertThatCode(() -> guard.requireInvitee(invitation(COMMITTEE_ID, USER_ID), USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("招集者本人はリポジトリ照会なしで取り下げられる")
        void 招集者本人は取り下げられる() {
            CommitteeInvitationEntity invitation = CommitteeInvitationEntity.builder()
                    .committeeId(COMMITTEE_ID)
                    .inviteeUserId(OTHER_USER_ID)
                    .invitedBy(USER_ID)
                    .inviteToken("token")
                    .expiresAt(LocalDateTime.now().plusDays(1))
                    .build();

            assertThatCode(() -> guard.requireInvitationCanceller(invitation, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("招集者でも当該委員会の CHAIR でもない利用者は拒否される")
        void 権限のない利用者は取り下げられない() {
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.requireInvitationCanceller(
                    invitation(COMMITTEE_ID, OTHER_USER_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    @Nested
    @DisplayName("requireSameCommittee")
    class SameCommittee {

        @Test
        @DisplayName("パスの委員会と実体の委員会が食い違う場合は NOT_FOUND で拒否される")
        void 委員会不一致は拒否される() {
            assertThatThrownBy(() -> guard.requireSameCommittee(COMMITTEE_ID, OTHER_COMMITTEE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommitteeErrorCode.NOT_FOUND));
        }

        @Test
        @DisplayName("一致する場合は通過する")
        void 一致すれば通過する() {
            assertThatCode(() -> guard.requireSameCommittee(COMMITTEE_ID, COMMITTEE_ID))
                    .doesNotThrowAnyException();
        }
    }

    // ─────────────────────────────────────────────
    // フィクスチャ
    // ─────────────────────────────────────────────

    private static CommitteeMemberEntity member(CommitteeRole role) {
        return CommitteeMemberEntity.builder()
                .committeeId(COMMITTEE_ID)
                .userId(USER_ID)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    private static CommitteeInvitationEntity invitation(Long committeeId, Long inviteeUserId) {
        return CommitteeInvitationEntity.builder()
                .committeeId(committeeId)
                .inviteeUserId(inviteeUserId)
                .invitedBy(OTHER_USER_ID)
                .inviteToken("token")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
    }
}
