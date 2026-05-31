package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentContactSpaceRepository;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * {@link TournamentContactAccessService} の単体テスト（F08.7.1 連絡機能 §4 認可）。
 *
 * <p>read（{@code checkView}）/ write（{@code checkPost}）分離、公開トグル ON-OFF、
 * 参加チームメンバー / チーム代表 / 主催組織 ADMIN / SYSTEM_ADMIN / 非メンバー 403 /
 * 存在しないスコープ 404（IDOR）の各分岐を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentContactAccessService 単体テスト")
class TournamentContactAccessServiceTest {

    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private TournamentDivisionRepository divisionRepository;
    @Mock
    private TournamentContactSpaceRepository contactSpaceRepository;
    @Mock
    private TournamentParticipantRepository participantRepository;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private TournamentContactAccessService service;

    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long ORG_ID = 5L;
    private static final Long USER_ID = 10L;

    private TournamentEntity tournament() {
        return TournamentEntity.builder()
                .organizationId(ORG_ID)
                .name("テスト大会")
                .createdBy(1L)
                .build();
    }

    private TournamentDivisionEntity division() {
        return TournamentDivisionEntity.builder()
                .tournamentId(TOURNAMENT_ID)
                .name("1部")
                .build();
    }

    private TournamentContactSpaceEntity space(boolean isPublic) {
        return TournamentContactSpaceEntity.builder()
                .scopeType(ContactSpaceScopeType.TOURNAMENT)
                .scopeId(TOURNAMENT_ID)
                .spaceKind(ContactSpaceKind.BULLETIN)
                .refId(999L)
                .isPublic(isPublic)
                .build();
    }

    // ====================================================================
    // checkView（閲覧・要件②）
    // ====================================================================

    @Nested
    @DisplayName("checkView（TOURNAMENT スコープ）")
    class CheckViewTournament {

        @Test
        @DisplayName("公開スペース_未ログイン含め誰でも閲覧可")
        void publicSpace_anyone() {
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN))
                    .willReturn(Optional.of(space(true)));

            assertThatCode(() -> service.checkView(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("参加チームのアクティブメンバー_閲覧可")
        void participantMember_ok() {
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN))
                    .willReturn(Optional.of(space(false)));
            given(participantRepository.existsActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID))
                    .willReturn(true);

            assertThatCode(() -> service.checkView(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("主催組織ADMIN_閲覧可")
        void orgAdmin_ok() {
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN))
                    .willReturn(Optional.of(space(false)));
            given(participantRepository.existsActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID))
                    .willReturn(false);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThatCode(() -> service.checkView(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SYSTEM_ADMIN_閲覧可")
        void systemAdmin_ok() {
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN))
                    .willReturn(Optional.of(space(false)));
            given(participantRepository.existsActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID))
                    .willReturn(false);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> service.checkView(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("非メンバーかつ非ADMIN_403")
        void nonMember_403() {
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN))
                    .willReturn(Optional.of(space(false)));
            given(participantRepository.existsActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID))
                    .willReturn(false);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);

            assertThatThrownBy(() -> service.checkView(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN));
        }

        @Test
        @DisplayName("非公開スペースに未ログイン_404（存在を漏らさない）")
        void nonPublic_anonymous_404() {
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN))
                    .willReturn(Optional.of(space(false)));

            assertThatThrownBy(() -> service.checkView(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN));
        }

        @Test
        @DisplayName("スペースが存在しない_404（IDOR）")
        void spaceMissing_404() {
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.checkView(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("checkView（TOURNAMENT_DIVISION スコープ）")
    class CheckViewDivision {

        private TournamentContactSpaceEntity divSpace(boolean isPublic) {
            return TournamentContactSpaceEntity.builder()
                    .scopeType(ContactSpaceScopeType.TOURNAMENT_DIVISION)
                    .scopeId(DIVISION_ID)
                    .spaceKind(ContactSpaceKind.CHAT)
                    .refId(888L)
                    .isPublic(isPublic)
                    .build();
        }

        @Test
        @DisplayName("ディビジョン参加チームのアクティブメンバー_閲覧可")
        void divisionMember_ok() {
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT_DIVISION, DIVISION_ID, ContactSpaceKind.CHAT))
                    .willReturn(Optional.of(divSpace(false)));
            given(participantRepository.existsActiveMemberOfDivisionParticipantTeam(DIVISION_ID, USER_ID))
                    .willReturn(true);

            assertThatCode(() -> service.checkView(
                    ContactSpaceScopeType.TOURNAMENT_DIVISION, DIVISION_ID, ContactSpaceKind.CHAT, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ディビジョン非メンバー_主催組織ADMINで閲覧可")
        void divisionOrgAdmin_ok() {
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT_DIVISION, DIVISION_ID, ContactSpaceKind.CHAT))
                    .willReturn(Optional.of(divSpace(false)));
            given(participantRepository.existsActiveMemberOfDivisionParticipantTeam(DIVISION_ID, USER_ID))
                    .willReturn(false);
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division()));
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThatCode(() -> service.checkView(
                    ContactSpaceScopeType.TOURNAMENT_DIVISION, DIVISION_ID, ContactSpaceKind.CHAT, USER_ID))
                    .doesNotThrowAnyException();
        }
    }

    // ====================================================================
    // checkPost（投稿・要件③）
    // ====================================================================

    @Nested
    @DisplayName("checkPost（TOURNAMENT スコープ）")
    class CheckPostTournament {

        @Test
        @DisplayName("参加チームの代表（ADMIN/DEPUTY_ADMIN）_投稿可")
        void teamAdmin_ok() {
            given(participantRepository.existsTeamAdminOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID))
                    .willReturn(true);

            assertThatCode(() -> service.checkPost(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("主催組織ADMIN_投稿可")
        void orgAdmin_ok() {
            given(participantRepository.existsTeamAdminOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID))
                    .willReturn(false);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThatCode(() -> service.checkPost(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("一般メンバー（代表でない）_403（権限昇格防止）")
        void plainMember_403() {
            given(participantRepository.existsTeamAdminOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID))
                    .willReturn(false);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);

            assertThatThrownBy(() -> service.checkPost(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN));
        }

        @Test
        @DisplayName("SYSTEM_ADMIN_投稿可")
        void systemAdmin_ok() {
            given(participantRepository.existsTeamAdminOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID))
                    .willReturn(false);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> service.checkPost(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, USER_ID))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("checkPost（TOURNAMENT_DIVISION スコープ）")
    class CheckPostDivision {

        @Test
        @DisplayName("ディビジョン参加チームの代表_投稿可")
        void divisionTeamAdmin_ok() {
            given(participantRepository.existsTeamAdminOfDivisionParticipantTeam(DIVISION_ID, USER_ID))
                    .willReturn(true);

            assertThatCode(() -> service.checkPost(
                    ContactSpaceScopeType.TOURNAMENT_DIVISION, DIVISION_ID, USER_ID))
                    .doesNotThrowAnyException();
        }
    }

    // ====================================================================
    // checkVisibilityManage（公開トグル・§5: 主催組織 ADMIN / SYSTEM_ADMIN 限定）
    // ====================================================================

    @Nested
    @DisplayName("checkVisibilityManage（公開トグル認可）")
    class CheckVisibilityManage {

        @Test
        @DisplayName("主催組織ADMIN_許可")
        void orgAdmin_ok() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThatCode(() -> service.checkVisibilityManage(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("チーム代表でも公開設定は不可_403")
        void teamAdmin_403() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);

            assertThatThrownBy(() -> service.checkVisibilityManage(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TournamentErrorCode.CONTACT_SPACE_VISIBILITY_FORBIDDEN));
        }

        @Test
        @DisplayName("SYSTEM_ADMIN_許可（組織解決を経ず即許可）")
        void systemAdmin_ok() {
            // SYSTEM_ADMIN は最初に判定され、組織解決（findById）には到達しない
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> service.checkVisibilityManage(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("大会が存在しない_404（IDOR）")
        void tournamentMissing_404() {
            lenient().when(accessControlService.isSystemAdmin(USER_ID)).thenReturn(false);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.checkVisibilityManage(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));
        }
    }
}
