package com.mannschaft.app.tournament;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.service.TournamentViewAccessGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TournamentViewAccessGate}（大会閲覧の共通ゲート。部門・参加チーム一覧が同じ判定を使う）の単体テスト。
 * strict stubbing（既定）で動かし、不要なスタブが残らないことも担保する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentViewAccessGate 単体テスト")
class TournamentViewAccessGateTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private TournamentViewAccessGate gate;

    private static final Long ORG_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long TOURNAMENT_ID = 100L;

    @Nested
    @DisplayName("isViewableBy")
    class IsViewableBy {

        @Test
        @DisplayName("組織管理者は F00 Resolver が不可視（他ユーザー作成の DRAFT）でも閲覧できる")
        void 組織管理者はDRAFTでも閲覧可() {
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThat(gate.isViewableBy(TOURNAMENT_ID, ORG_ID, USER_ID)).isTrue();
            // 管理者判定で短絡するため F00 Resolver は呼ばれない
            verify(contentVisibilityChecker, never()).canView(any(), any(), any());
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は閲覧できる")
        void システム管理者は閲覧可() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThat(gate.isViewableBy(TOURNAMENT_ID, ORG_ID, USER_ID)).isTrue();
            verify(contentVisibilityChecker, never()).canView(any(), any(), any());
        }

        @Test
        @DisplayName("権限のない者は Resolver が不可視なら閲覧できない（404 になる）")
        void 権限なしは不可視なら閲覧不可() {
            given(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, TOURNAMENT_ID, USER_ID))
                    .willReturn(false);

            assertThat(gate.isViewableBy(TOURNAMENT_ID, ORG_ID, USER_ID)).isFalse();
        }

        @Test
        @DisplayName("未認証は管理者判定を行わず Resolver に委譲する")
        void 未認証はResolverへ委譲() {
            given(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, TOURNAMENT_ID, null))
                    .willReturn(true);

            assertThat(gate.isViewableBy(TOURNAMENT_ID, ORG_ID, null)).isTrue();
            verify(accessControlService, never()).isSystemAdmin(any());
            verify(accessControlService, never()).isAdminOrAbove(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("verifyViewable")
    class VerifyViewable {

        @Test
        @DisplayName("大会が存在しなければ 404（TOURNAMENT_NOT_FOUND）")
        void 大会不存在は404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> gate.verifyViewable(TOURNAMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("不可視の大会は不在と同じ 404（TOURNAMENT_NOT_FOUND）で拒否される")
        void 不可視は不在と同じ404() {
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(TournamentEntity.builder().organizationId(ORG_ID).build()));
            given(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, TOURNAMENT_ID, USER_ID))
                    .willReturn(false);

            assertThatThrownBy(() -> gate.verifyViewable(TOURNAMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("組織管理者は DRAFT でも例外なく通る")
        void 管理者は通る() {
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(TournamentEntity.builder().organizationId(ORG_ID).build()));
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            gate.verifyViewable(TOURNAMENT_ID, USER_ID);
        }
    }
}
