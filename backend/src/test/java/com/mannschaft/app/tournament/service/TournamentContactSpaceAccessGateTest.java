package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link TournamentContactSpaceAccessGate} の単体テスト（CMP-261116-0726）。
 *
 * <p>閲覧権なし・スペース不在・大会／ディビジョン不在は呼び出し側指定の不在コードに畳まれ、
 * 閲覧可・権限なしだけが 403（投稿 TOUR_031／公開設定 TOUR_032）になること、
 * 投稿者は閲覧判定を走らせないこと（AC-20）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentContactSpaceAccessGate 単体テスト")
class TournamentContactSpaceAccessGateTest {

    @Mock
    private TournamentContactSpaceRepository contactSpaceRepository;
    @Mock
    private TournamentParticipantRepository participantRepository;
    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private TournamentDivisionRepository divisionRepository;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private TournamentContactSpaceAccessGate gate;

    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long ORG_ID = 5L;
    private static final Long USER_ID = 10L;
    private static final ErrorCode NOT_FOUND = TournamentErrorCode.CONTACT_SPACE_NOT_FOUND;
    private static final ContactSpaceScopeType T = ContactSpaceScopeType.TOURNAMENT;
    private static final ContactSpaceScopeType D = ContactSpaceScopeType.TOURNAMENT_DIVISION;
    private static final ContactSpaceKind BULLETIN = ContactSpaceKind.BULLETIN;
    private static final ContactSpaceKind CHAT = ContactSpaceKind.CHAT;

    private TournamentEntity tournament() {
        return TournamentEntity.builder().organizationId(ORG_ID).name("テスト大会").createdBy(1L).build();
    }

    private TournamentDivisionEntity division() {
        return TournamentDivisionEntity.builder().tournamentId(TOURNAMENT_ID).name("1部").build();
    }

    private TournamentContactSpaceEntity space(ContactSpaceScopeType scopeType, Long scopeId,
                                               ContactSpaceKind kind, boolean isPublic) {
        return TournamentContactSpaceEntity.builder()
                .scopeType(scopeType).scopeId(scopeId).spaceKind(kind).refId(999L).isPublic(isPublic).build();
    }

    private void givenSpace(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind, boolean isPublic) {
        given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(scopeType, scopeId, kind))
                .willReturn(Optional.of(space(scopeType, scopeId, kind, isPublic)));
    }

    private void givenNoSpace(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind) {
        given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(scopeType, scopeId, kind))
                .willReturn(Optional.empty());
    }

    private void givenTournamentExists() {
        given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
    }

    private static void assertThrowsCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(expected));
    }

    // ====================================================================
    // requireView
    // ====================================================================

    @Nested
    @DisplayName("requireView")
    class RequireView {

        @Test
        @DisplayName("公開スペースは未ログインでも許可し、所属判定を走らせない")
        void 公開スペースは未ログインでも許可() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, true);

            assertThatCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, null, NOT_FOUND))
                    .doesNotThrowAnyException();
            verifyNoInteractions(participantRepository, accessControlService);
        }

        @Test
        @DisplayName("非公開スペースに未ログインは不在コード（旧 403 TOUR_030 ではない）")
        void 非公開に未ログインは不在() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);

            assertThrowsCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, null, NOT_FOUND), NOT_FOUND);
        }

        @Test
        @DisplayName("スペース不在は呼び出し側が渡した不在コード")
        void スペース不在は指定コード() {
            givenNoSpace(T, TOURNAMENT_ID, BULLETIN);

            assertThrowsCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, USER_ID,
                    FileSharingErrorCode.FOLDER_NOT_FOUND), FileSharingErrorCode.FOLDER_NOT_FOUND);
        }

        @Test
        @DisplayName("参加チームの active member は許可（組織解決に進まない）")
        void activeMemberは許可() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThatCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
            verifyNoInteractions(tournamentRepository, accessControlService);
        }

        @Test
        @DisplayName("主催組織 ADMIN は許可")
        void 主催ADMINは許可() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThatCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は許可")
        void SYSTEM_ADMINは許可() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("閲覧権なし（部外者・主催組織一般・他テナントADMIN）は不在コード")
        void 閲覧権なしは不在() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();

            assertThrowsCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND), NOT_FOUND);
        }

        @Test
        @DisplayName("ディビジョン: 組織はディビジョン→大会を辿って解決し、主催 ADMIN を許可する")
        void ディビジョンの主催ADMINは許可() {
            givenSpace(D, DIVISION_ID, CHAT, false);
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division()));
            givenTournamentExists();
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThatCode(() -> gate.requireView(D, DIVISION_ID, CHAT, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
            verify(participantRepository).countActiveMemberOfDivisionParticipantTeam(DIVISION_ID, USER_ID);
        }

        @Test
        @DisplayName("ディビジョン: スペースは残るが大会が論理削除済みなら不在コード")
        void ディビジョンの大会不在は不在() {
            givenSpace(D, DIVISION_ID, BULLETIN, false);
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division()));
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThrowsCode(() -> gate.requireView(D, DIVISION_ID, BULLETIN, USER_ID, NOT_FOUND), NOT_FOUND);
            verifyNoInteractions(accessControlService);
        }
    }

    // ====================================================================
    // requirePost
    // ====================================================================

    @Nested
    @DisplayName("requirePost")
    class RequirePost {

        @Test
        @DisplayName("参加チームの代表は許可し、閲覧判定（active member・公開設定）を走らせない（AC-20）")
        void チーム代表は許可() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            given(participantRepository.countTeamAdminOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThatCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
            verify(participantRepository, never()).countActiveMemberOfAnyParticipantTeam(anyLong(), anyLong());
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("ディビジョン参加チームの代表は許可")
        void ディビジョンのチーム代表は許可() {
            givenSpace(D, DIVISION_ID, BULLETIN, false);
            given(participantRepository.countTeamAdminOfDivisionParticipantTeam(DIVISION_ID, USER_ID)).willReturn(1);

            assertThatCode(() -> gate.requirePost(D, DIVISION_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("主催組織 ADMIN・SYSTEM_ADMIN は許可")
        void 主催ADMINとSYSTEM_ADMINは許可() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
            verify(accessControlService).isAdmin(USER_ID, ORG_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("スペース（kind）欠落は投稿者でも不在コード（AC-19）")
        void スペース欠落は投稿者でも不在() {
            givenNoSpace(T, TOURNAMENT_ID, BULLETIN);

            assertThrowsCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND), NOT_FOUND);
            verifyNoInteractions(participantRepository, accessControlService);
        }

        @Test
        @DisplayName("参加チームの一般 active member（閲覧可・投稿不可）は 403 TOUR_031")
        void 一般メンバーは403() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();
            given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThrowsCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND),
                    TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }

        @Test
        @DisplayName("公開スペース（kind 側）の部外者は 403 TOUR_031（AC-17）")
        void 公開スペースの部外者は403() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, true);
            givenTournamentExists();

            assertThrowsCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND),
                    TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }

        @Test
        @DisplayName("公開スペースに未ログインは 403 TOUR_031、非公開スペースに未ログインは不在コード")
        void 未ログインは公開設定次第() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, true);
            givenSpace(T, TOURNAMENT_ID, CHAT, false);

            assertThrowsCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, null, NOT_FOUND),
                    TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
            assertThrowsCode(() -> gate.requirePost(T, TOURNAMENT_ID, CHAT, null, NOT_FOUND), NOT_FOUND);
            verifyNoInteractions(participantRepository, accessControlService);
        }

        @Test
        @DisplayName("閲覧権なし（非公開・非メンバー・非ADMIN）は不在コード")
        void 閲覧権なしは不在() {
            givenSpace(T, TOURNAMENT_ID, CHAT, false);
            givenTournamentExists();

            assertThrowsCode(() -> gate.requirePost(T, TOURNAMENT_ID, CHAT, USER_ID,
                    FileSharingErrorCode.FILE_NOT_FOUND), FileSharingErrorCode.FILE_NOT_FOUND);
        }
    }

    // ====================================================================
    // requireAuthorOrPost
    // ====================================================================

    @Nested
    @DisplayName("requireAuthorOrPost")
    class RequireAuthorOrPost {

        @Test
        @DisplayName("本人は閲覧だけを要求する（一般 active member でも許可）")
        void 本人は閲覧のみ要求() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThatCode(() -> gate.requireAuthorOrPost(T, TOURNAMENT_ID, BULLETIN, USER_ID, true, NOT_FOUND))
                    .doesNotThrowAnyException();
            verify(participantRepository, never()).countTeamAdminOfAnyParticipantTeam(anyLong(), anyLong());
        }

        @Test
        @DisplayName("資格を失った本人（WITHDRAWN/DISQUALIFIED で閲覧不可）は不在コード（AC-16）")
        void 資格喪失の本人は不在() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();

            assertThrowsCode(() -> gate.requireAuthorOrPost(T, TOURNAMENT_ID, BULLETIN, USER_ID, true, NOT_FOUND),
                    NOT_FOUND);
        }

        @Test
        @DisplayName("本人でなければ投稿権を要求する（一般 active member は 403 TOUR_031）")
        void 本人以外は投稿権要求() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();
            given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThrowsCode(() -> gate.requireAuthorOrPost(T, TOURNAMENT_ID, BULLETIN, USER_ID, false, NOT_FOUND),
                    TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }
    }

    // ====================================================================
    // requireViewResolvingLevel
    // ====================================================================

    @Nested
    @DisplayName("requireViewResolvingLevel")
    class RequireViewResolvingLevel {

        @Test
        @DisplayName("投稿者は POST を返し、閲覧判定を走らせない")
        void 投稿者はPOST() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            given(participantRepository.countTeamAdminOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThat(gate.requireViewResolvingLevel(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .isEqualTo(ContactAccessLevel.POST);
            verify(participantRepository, never()).countActiveMemberOfAnyParticipantTeam(anyLong(), anyLong());
        }

        @Test
        @DisplayName("一般 active member は VIEW")
        void 一般メンバーはVIEW() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();
            given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThat(gate.requireViewResolvingLevel(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .isEqualTo(ContactAccessLevel.VIEW);
        }

        @Test
        @DisplayName("公開スペースの未ログインは VIEW")
        void 公開の未ログインはVIEW() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, true);

            assertThat(gate.requireViewResolvingLevel(T, TOURNAMENT_ID, BULLETIN, null, NOT_FOUND))
                    .isEqualTo(ContactAccessLevel.VIEW);
        }

        @Test
        @DisplayName("閲覧権なし・スペース不在は不在コード")
        void 閲覧権なしは不在() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenNoSpace(T, TOURNAMENT_ID, CHAT);
            givenTournamentExists();

            assertThrowsCode(() -> gate.requireViewResolvingLevel(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND),
                    NOT_FOUND);
            assertThrowsCode(() -> gate.requireViewResolvingLevel(T, TOURNAMENT_ID, CHAT, USER_ID, NOT_FOUND),
                    NOT_FOUND);
        }
    }

    // ====================================================================
    // requireVisibilityManage
    // ====================================================================

    @Nested
    @DisplayName("requireVisibilityManage")
    class RequireVisibilityManage {

        @Test
        @DisplayName("主催組織 ADMIN は許可し、スペース一覧を引かない")
        void 主催ADMINは許可() {
            givenTournamentExists();
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThatCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, USER_ID))
                    .doesNotThrowAnyException();
            verifyNoInteractions(contactSpaceRepository);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は正しい大会配下のディビジョンで許可")
        void SYSTEM_ADMINはディビジョンで許可() {
            givenTournamentExists();
            given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                    .willReturn(Optional.of(division()));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, DIVISION_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("大会不在は管理者でも 404 TOUR_029")
        void 大会不在は404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThrowsCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, USER_ID), NOT_FOUND);
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("パスの大会 ID とディビジョンの帰属が食い違えば SYSTEM_ADMIN でも 404 TOUR_029（AC-18）")
        void 帰属不一致は404() {
            givenTournamentExists();
            given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                    .willReturn(Optional.empty());

            assertThrowsCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, DIVISION_ID, USER_ID), NOT_FOUND);
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("参加チームの active member（知り得るが管理権なし）は 403 TOUR_032")
        void activeMemberは403() {
            givenTournamentExists();
            given(contactSpaceRepository.findByScopeTypeAndScopeId(T, TOURNAMENT_ID))
                    .willReturn(List.of(space(T, TOURNAMENT_ID, BULLETIN, false), space(T, TOURNAMENT_ID, CHAT, false)));
            given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThrowsCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, USER_ID),
                    TournamentErrorCode.CONTACT_SPACE_VISIBILITY_FORBIDDEN);
        }

        @Test
        @DisplayName("いずれかのスペースが公開なら部外者も知り得るため 403 TOUR_032（AC-17）")
        void 公開スペースありの部外者は403() {
            givenTournamentExists();
            given(contactSpaceRepository.findByScopeTypeAndScopeId(T, TOURNAMENT_ID))
                    .willReturn(List.of(space(T, TOURNAMENT_ID, BULLETIN, false), space(T, TOURNAMENT_ID, CHAT, true)));

            assertThrowsCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, USER_ID),
                    TournamentErrorCode.CONTACT_SPACE_VISIBILITY_FORBIDDEN);
            verifyNoInteractions(participantRepository);
        }

        @Test
        @DisplayName("ディビジョン: 全スペース非公開で非メンバーは不在と同一の 404 TOUR_029")
        void 知り得ない者は404() {
            givenTournamentExists();
            given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                    .willReturn(Optional.of(division()));
            given(contactSpaceRepository.findByScopeTypeAndScopeId(D, DIVISION_ID))
                    .willReturn(List.of(space(D, DIVISION_ID, BULLETIN, false)));

            assertThrowsCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, DIVISION_ID, USER_ID), NOT_FOUND);
            verify(participantRepository).countActiveMemberOfDivisionParticipantTeam(DIVISION_ID, USER_ID);
        }

        @Test
        @DisplayName("スペースが 1 件も無ければ active member でも閲覧対象が無く 404 TOUR_029")
        void スペース皆無は404() {
            givenTournamentExists();
            given(contactSpaceRepository.findByScopeTypeAndScopeId(T, TOURNAMENT_ID)).willReturn(List.of());

            assertThrowsCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, USER_ID), NOT_FOUND);
            verify(participantRepository, never()).countActiveMemberOfAnyParticipantTeam(any(), any());
        }
    }

    // ====================================================================
    // resolveLevelsForSpaces（CMP-261118-0900 §10.3.5・試練 red）
    // ====================================================================

    /**
     * 一括解決の単体テスト。
     *
     * <p>利用者の所属（どの部門の参加チームの代表／有効メンバーか・どの組織の ADMIN か・SYSTEM_ADMIN か）を
     * 1 つの「世界」として持ち、既存 Gate が使う単発クエリと、一括解決が使う集約クエリ
     * （{@code findDivisionIdsWhereTeamAdmin}／{@code findDivisionIdsWhereActiveMember}）を同じ世界から
     * 矛盾なく答えさせる（lenient）。そのうえで、全 (scope, kind) について一括解決の結果が
     * {@link TournamentContactSpaceAccessGate#requireViewResolvingLevel} と一致すること（AC-P34。例外＝マップ不在）を
     * 突き合わせる。大会配下でない部門のスペース（X1）・大会不在（X3）・主催者（AC-P33）・種別ごとの公開（P34 修正）も固定する。</p>
     *
     * <p>部門→大会の帰属は {@code divisionRepository} の {@code findById}／{@code findAllById}／
     * {@code findByIdAndTournamentId}／{@code findByTournamentIdOrderByLevelAscSortOrderAsc} のどれで引いても
     * 同じ答えになるよう用意した。別の手段（新クエリ）で引く実装にした場合は、この世界にその答えを足すこと。</p>
     */
    @Nested
    @DisplayName("resolveLevelsForSpaces（CMP-261118-0900）")
    class ResolveLevelsForSpaces {

        private static final Long D1 = 201L;
        private static final Long D2 = 202L;
        private static final Long OTHER_TOURNAMENT_ID = 101L;
        private static final Long OTHER_ORG_ID = 6L;
        private static final Long OTHER_DIVISION_ID = 299L;

        /** 利用者の所属の世界。 */
        private record World(java.util.Set<Long> adminDivisions, java.util.Set<Long> memberDivisions,
                             java.util.Set<Long> adminOrgs, boolean systemAdmin, boolean tournamentExists) {

            static World of(java.util.Set<Long> adminDivisions, java.util.Set<Long> memberOnlyDivisions,
                            java.util.Set<Long> adminOrgs, boolean systemAdmin) {
                java.util.Set<Long> members = new java.util.HashSet<>(memberOnlyDivisions);
                members.addAll(adminDivisions); // 代表は有効メンバーでもある
                return new World(adminDivisions, members, adminOrgs, systemAdmin, true);
            }

            World withoutTournament() {
                return new World(adminDivisions, memberDivisions, adminOrgs, systemAdmin, false);
            }
        }

        private Long tournamentOf(Long divisionId) {
            if (divisionId.equals(D1) || divisionId.equals(D2)) {
                return TOURNAMENT_ID;
            }
            return divisionId.equals(OTHER_DIVISION_ID) ? OTHER_TOURNAMENT_ID : null;
        }

        private TournamentDivisionEntity divisionEntity(Long id) {
            return TournamentDivisionEntity.builder().id(id).tournamentId(tournamentOf(id)).name("部門" + id).build();
        }

        private TournamentContactSpaceEntity spaceWithId(ContactSpaceScopeType scopeType, Long scopeId,
                                                         ContactSpaceKind kind, boolean isPublic) {
            TournamentContactSpaceEntity s = space(scopeType, scopeId, kind, isPublic);
            s.setId(java.util.UUID.randomUUID());
            return s;
        }

        /** 世界とスペース群から、Gate が引きうる全クエリの答えを lenient に用意する。 */
        private void install(World w, List<TournamentContactSpaceEntity> spaces) {
            org.mockito.Mockito.reset(contactSpaceRepository, participantRepository, tournamentRepository,
                    divisionRepository, accessControlService);
            org.mockito.Mockito.lenient().when(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    any(), any(), any())).thenAnswer(inv -> spaces.stream()
                    .filter(s -> s.getScopeType() == inv.getArgument(0) && s.getScopeId().equals(inv.getArgument(1))
                            && s.getSpaceKind() == inv.getArgument(2))
                    .findFirst());
            org.mockito.Mockito.lenient().when(contactSpaceRepository.findByScopeTypeAndScopeId(any(), any()))
                    .thenAnswer(inv -> spaces.stream()
                            .filter(s -> s.getScopeType() == inv.getArgument(0)
                                    && s.getScopeId().equals(inv.getArgument(1)))
                            .toList());

            org.mockito.Mockito.lenient().when(participantRepository.countTeamAdminOfAnyParticipantTeam(any(), any()))
                    .thenAnswer(inv -> w.adminDivisions().stream()
                            .anyMatch(d -> inv.getArgument(0).equals(tournamentOf(d))) ? 1 : 0);
            org.mockito.Mockito.lenient().when(participantRepository.countActiveMemberOfAnyParticipantTeam(any(), any()))
                    .thenAnswer(inv -> w.memberDivisions().stream()
                            .anyMatch(d -> inv.getArgument(0).equals(tournamentOf(d))) ? 1 : 0);
            org.mockito.Mockito.lenient().when(participantRepository.countTeamAdminOfDivisionParticipantTeam(any(), any()))
                    .thenAnswer(inv -> w.adminDivisions().contains(inv.<Long>getArgument(0)) ? 1 : 0);
            org.mockito.Mockito.lenient().when(participantRepository.countActiveMemberOfDivisionParticipantTeam(any(), any()))
                    .thenAnswer(inv -> w.memberDivisions().contains(inv.<Long>getArgument(0)) ? 1 : 0);
            // 集約クエリは骨格の SQL どおり大会で絞らない（部門 ID 群 ∩ 所属）
            org.mockito.Mockito.lenient().when(participantRepository.findDivisionIdsWhereTeamAdmin(any(), any()))
                    .thenAnswer(inv -> inv.<java.util.Collection<Long>>getArgument(0).stream()
                            .filter(w.adminDivisions()::contains).distinct().toList());
            org.mockito.Mockito.lenient().when(participantRepository.findDivisionIdsWhereActiveMember(any(), any()))
                    .thenAnswer(inv -> inv.<java.util.Collection<Long>>getArgument(0).stream()
                            .filter(w.memberDivisions()::contains).distinct().toList());

            org.mockito.Mockito.lenient().when(tournamentRepository.findById(any())).thenAnswer(inv -> {
                Long id = inv.getArgument(0);
                if (id.equals(TOURNAMENT_ID) && w.tournamentExists()) {
                    return Optional.of(TournamentEntity.builder().id(TOURNAMENT_ID).organizationId(ORG_ID)
                            .name("大会").createdBy(1L).build());
                }
                if (id.equals(OTHER_TOURNAMENT_ID)) {
                    return Optional.of(TournamentEntity.builder().id(OTHER_TOURNAMENT_ID).organizationId(OTHER_ORG_ID)
                            .name("別大会").createdBy(1L).build());
                }
                return Optional.empty();
            });
            org.mockito.Mockito.lenient().when(divisionRepository.findById(any())).thenAnswer(inv -> {
                Long id = inv.getArgument(0);
                return tournamentOf(id) == null ? Optional.empty() : Optional.of(divisionEntity(id));
            });
            org.mockito.Mockito.lenient().when(divisionRepository.findAllById(any())).thenAnswer(inv -> {
                List<TournamentDivisionEntity> found = new java.util.ArrayList<>();
                for (Long id : inv.<Iterable<Long>>getArgument(0)) {
                    if (tournamentOf(id) != null) {
                        found.add(divisionEntity(id));
                    }
                }
                return found;
            });
            org.mockito.Mockito.lenient().when(divisionRepository.findByIdAndTournamentId(any(), any()))
                    .thenAnswer(inv -> inv.getArgument(1).equals(tournamentOf(inv.getArgument(0)))
                            ? Optional.of(divisionEntity(inv.getArgument(0))) : Optional.empty());
            org.mockito.Mockito.lenient().when(divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(any()))
                    .thenAnswer(inv -> java.util.stream.Stream.of(D1, D2, OTHER_DIVISION_ID)
                            .filter(d -> inv.getArgument(0).equals(tournamentOf(d)))
                            .map(this::divisionEntity).toList());

            org.mockito.Mockito.lenient().when(accessControlService.isAdmin(any(), any(), any()))
                    .thenAnswer(inv -> "ORGANIZATION".equals(inv.getArgument(2))
                            && w.adminOrgs().contains(inv.<Long>getArgument(1)));
            org.mockito.Mockito.lenient().when(accessControlService.isSystemAdmin(any()))
                    .thenAnswer(inv -> w.systemAdmin());
        }

        /** 既存 Gate の単発判定で期待値を作る（例外＝閲覧不可＝マップ不在）。 */
        private java.util.Map<java.util.UUID, ContactAccessLevel> expectedBySingleCalls(
                List<TournamentContactSpaceEntity> spaces) {
            java.util.Map<java.util.UUID, ContactAccessLevel> expected = new java.util.HashMap<>();
            for (TournamentContactSpaceEntity s : spaces) {
                try {
                    expected.put(s.getId(), gate.requireViewResolvingLevel(
                            s.getScopeType(), s.getScopeId(), s.getSpaceKind(), USER_ID, NOT_FOUND));
                } catch (BusinessException notViewable) {
                    // 閲覧不可はマップに含めない
                }
            }
            return expected;
        }

        private List<TournamentContactSpaceEntity> allPrivate() {
            return List.of(
                    spaceWithId(T, TOURNAMENT_ID, BULLETIN, false), spaceWithId(T, TOURNAMENT_ID, CHAT, false),
                    spaceWithId(D, D1, BULLETIN, false), spaceWithId(D, D1, CHAT, false),
                    spaceWithId(D, D2, BULLETIN, false), spaceWithId(D, D2, CHAT, false));
        }

        private List<TournamentContactSpaceEntity> mixedPublic() {
            return List.of(
                    spaceWithId(T, TOURNAMENT_ID, BULLETIN, true), spaceWithId(T, TOURNAMENT_ID, CHAT, false),
                    spaceWithId(D, D1, BULLETIN, false), spaceWithId(D, D1, CHAT, true),
                    spaceWithId(D, D2, BULLETIN, true), spaceWithId(D, D2, CHAT, false));
        }

        private java.util.Map<String, World> profiles() {
            java.util.Map<String, World> p = new java.util.LinkedHashMap<>();
            p.put("a1（D1 代表）", World.of(java.util.Set.of(D1), java.util.Set.of(), java.util.Set.of(), false));
            p.put("a3（D1 一般）", World.of(java.util.Set.of(), java.util.Set.of(D1), java.util.Set.of(), false));
            p.put("x（D1・D2 一般）", World.of(java.util.Set.of(), java.util.Set.of(D1, D2), java.util.Set.of(), false));
            p.put("xa（D1 代表・D2 一般）", World.of(java.util.Set.of(D1), java.util.Set.of(D2), java.util.Set.of(), false));
            p.put("b1（D2 代表）", World.of(java.util.Set.of(D2), java.util.Set.of(), java.util.Set.of(), false));
            p.put("z（部外者）", World.of(java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), false));
            p.put("o1（主催組織 ADMIN・非所属）", World.of(java.util.Set.of(), java.util.Set.of(), java.util.Set.of(ORG_ID), false));
            p.put("oMemberA（主催 ADMIN 兼 D1 一般）",
                    World.of(java.util.Set.of(), java.util.Set.of(D1), java.util.Set.of(ORG_ID), false));
            p.put("fAdminA（別組織 ADMIN 兼 D1 一般・X9）",
                    World.of(java.util.Set.of(), java.util.Set.of(D1), java.util.Set.of(OTHER_ORG_ID), false));
            p.put("sysAdmin（非所属）", World.of(java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), true));
            p.put("他大会の部門 D9 の代表", World.of(java.util.Set.of(OTHER_DIVISION_ID), java.util.Set.of(),
                    java.util.Set.of(), false));
            return p;
        }

        @Test
        @DisplayName("P34: 全利用者×全 (scope, kind)（全非公開・種別ごとに公開が混在）で、一括解決は requireViewResolvingLevel と一致する（例外＝マップ不在）")
        void P34_単発判定と等価() {
            for (List<TournamentContactSpaceEntity> spaces : List.of(allPrivate(), mixedPublic())) {
                for (java.util.Map.Entry<String, World> p : profiles().entrySet()) {
                    install(p.getValue(), spaces);
                    java.util.Map<java.util.UUID, ContactAccessLevel> expected = expectedBySingleCalls(spaces);

                    java.util.Map<java.util.UUID, ContactAccessLevel> actual =
                            gate.resolveLevelsForSpaces(USER_ID, TOURNAMENT_ID, spaces);

                    assertThat(actual).as("利用者=%s スペース=%s", p.getKey(), spaces).isEqualTo(expected);
                }
            }
        }

        @Test
        @DisplayName("P34: 非所属部門（a3 に対する D2）の非公開スペースと、部外者 z の大会全体の非公開スペースはマップに含まれない")
        void P34_非所属は不在() {
            List<TournamentContactSpaceEntity> spaces = allPrivate();
            install(World.of(java.util.Set.of(), java.util.Set.of(D1), java.util.Set.of(), false), spaces);

            java.util.Map<java.util.UUID, ContactAccessLevel> a3 = gate.resolveLevelsForSpaces(USER_ID, TOURNAMENT_ID, spaces);

            assertThat(a3).containsOnlyKeys(spaces.get(0).getId(), spaces.get(1).getId(),
                    spaces.get(2).getId(), spaces.get(3).getId());
            assertThat(a3.values()).containsOnly(ContactAccessLevel.VIEW);

            install(World.of(java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), false), spaces);
            assertThat(gate.resolveLevelsForSpaces(USER_ID, TOURNAMENT_ID, spaces)).isEmpty();
        }

        @Test
        @DisplayName("P34 修正: 種別ごとに公開状態で分かれる（部外者 z に大会全体の掲示板だけ公開→掲示板 VIEW・チャット不在）")
        void P34_種別ごとの公開() {
            TournamentContactSpaceEntity bulletin = spaceWithId(T, TOURNAMENT_ID, BULLETIN, true);
            TournamentContactSpaceEntity chat = spaceWithId(T, TOURNAMENT_ID, CHAT, false);
            install(World.of(java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), false), List.of(bulletin, chat));

            java.util.Map<java.util.UUID, ContactAccessLevel> z =
                    gate.resolveLevelsForSpaces(USER_ID, TOURNAMENT_ID, List.of(bulletin, chat));

            assertThat(z).containsExactlyEntriesOf(java.util.Map.of(bulletin.getId(), ContactAccessLevel.VIEW));
        }

        @Test
        @DisplayName("P33: 主催組織 ADMIN・SYSTEM_ADMIN は、チームに属さなくても大会配下の全スペースが POST（404 にするのは Facade の参加判定）")
        void P33_主催者は全POST() {
            for (World w : List.of(
                    World.of(java.util.Set.of(), java.util.Set.of(), java.util.Set.of(ORG_ID), false),
                    World.of(java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), true))) {
                List<TournamentContactSpaceEntity> spaces = allPrivate();
                install(w, spaces);

                java.util.Map<java.util.UUID, ContactAccessLevel> actual =
                        gate.resolveLevelsForSpaces(USER_ID, TOURNAMENT_ID, spaces);

                assertThat(actual).hasSize(spaces.size());
                assertThat(actual.values()).containsOnly(ContactAccessLevel.POST);
            }
        }

        @Test
        @DisplayName("X1: 大会配下でない部門・別大会の大会全体スペースが渡されたら、代表でも SYSTEM_ADMIN でもマップに含めない")
        void X1_大会配下でないスペースは含めない() {
            TournamentContactSpaceEntity own = spaceWithId(T, TOURNAMENT_ID, BULLETIN, false);
            TournamentContactSpaceEntity foreignDivision = spaceWithId(D, OTHER_DIVISION_ID, BULLETIN, true);
            TournamentContactSpaceEntity foreignTournament = spaceWithId(T, OTHER_TOURNAMENT_ID, CHAT, true);
            TournamentContactSpaceEntity unknownDivision = spaceWithId(D, 98765L, CHAT, true);
            List<TournamentContactSpaceEntity> spaces = List.of(own, foreignDivision, foreignTournament, unknownDivision);

            for (World w : List.of(
                    World.of(java.util.Set.of(OTHER_DIVISION_ID), java.util.Set.of(), java.util.Set.of(), false),
                    World.of(java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), true))) {
                install(w, spaces);

                java.util.Map<java.util.UUID, ContactAccessLevel> actual =
                        gate.resolveLevelsForSpaces(USER_ID, TOURNAMENT_ID, spaces);

                assertThat(actual).doesNotContainKeys(foreignDivision.getId(), foreignTournament.getId(),
                        unknownDivision.getId());
            }
        }

        @Test
        @DisplayName("X3: 大会が見つからない（論理削除・レース）なら例外を投げず空マップ（代表・公開スペースでも）")
        void X3_大会不在は空マップ() {
            List<TournamentContactSpaceEntity> spaces = mixedPublic();
            install(World.of(java.util.Set.of(D1), java.util.Set.of(D2), java.util.Set.of(ORG_ID), true)
                    .withoutTournament(), spaces);

            java.util.Map<java.util.UUID, ContactAccessLevel> actual =
                    gate.resolveLevelsForSpaces(USER_ID, TOURNAMENT_ID, spaces);

            assertThat(actual).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("空のスペース群には空マップを返す")
        void 空入力は空マップ() {
            install(World.of(java.util.Set.of(D1), java.util.Set.of(), java.util.Set.of(), false), List.of());

            assertThat(gate.resolveLevelsForSpaces(USER_ID, TOURNAMENT_ID, List.of())).isEmpty();
        }

        @Test
        @DisplayName("P20/§10.3.5: 部門ごとの単発クエリ・スペースの再取得をせず、集約クエリと主催者判定は高々 1 回ずつ")
        void P20_クエリはDに比例しない() {
            List<TournamentContactSpaceEntity> spaces = allPrivate();
            install(World.of(java.util.Set.of(D1), java.util.Set.of(D2), java.util.Set.of(), false), spaces);

            gate.resolveLevelsForSpaces(USER_ID, TOURNAMENT_ID, spaces);

            verify(participantRepository, never()).countTeamAdminOfDivisionParticipantTeam(any(), any());
            verify(participantRepository, never()).countActiveMemberOfDivisionParticipantTeam(any(), any());
            verify(participantRepository, org.mockito.Mockito.atMost(1)).findDivisionIdsWhereTeamAdmin(any(), any());
            verify(participantRepository, org.mockito.Mockito.atMost(1)).findDivisionIdsWhereActiveMember(any(), any());
            verify(participantRepository, org.mockito.Mockito.atMost(1)).countTeamAdminOfAnyParticipantTeam(any(), any());
            verify(participantRepository, org.mockito.Mockito.atMost(1)).countActiveMemberOfAnyParticipantTeam(any(), any());
            verify(contactSpaceRepository, never()).findByScopeTypeAndScopeIdAndSpaceKind(any(), any(), any());
            verify(contactSpaceRepository, never()).findByScopeTypeAndScopeId(any(), any());
            verify(divisionRepository, never()).findById(any());
            verify(tournamentRepository, org.mockito.Mockito.atMost(1)).findById(any());
            verify(accessControlService, org.mockito.Mockito.atMost(1)).isAdmin(any(), any(), any());
            verify(accessControlService, org.mockito.Mockito.atMost(1)).isSystemAdmin(any());
        }
    }
}
