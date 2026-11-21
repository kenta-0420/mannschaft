package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.dto.ParticipantContactDivisionRow;
import com.mannschaft.app.tournament.dto.ParticipantContactSpacesResponse;
import com.mannschaft.app.tournament.dto.ParticipantContactSpacesResponse.SpaceItem;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;
import com.mannschaft.app.tournament.repository.TournamentContactSpaceRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-261118-0900 {@link ParticipantContactSpaceFacade} の単体テスト（試練・red）。
 *
 * <p>設計書 §10.3.4 の判定順（slug 解決 → 参加判定 native 1 本 → スペース IN 取得 1 本 → Gate 一括解決 1 回）と、
 * 拒否はすべて同一の {@code BusinessException(TOUR_029)}、閲覧不可の行は応答から外す、並び（X2）、
 * 0 件（AC-P18）、読み取り失敗は握りつぶさない（X12）、TX なし・命名（X8）を固定する。</p>
 *
 * <p>依存は {@code @InjectMocks} で注入する（実装が {@code @RequiredArgsConstructor} のコンストラクタ注入に
 * なってもそのまま効く）。想定する依存: {@link TeamService#resolveFollowableTeamId(String)}、
 * {@link TournamentParticipantRepository#findParticipantContactDivisions}、
 * {@link TournamentContactSpaceRepository#findForTournamentAndDivisions}、
 * {@link TournamentContactSpaceAccessGate#resolveLevelsForSpaces}（いずれも骨格 828a65bf7d のシグネチャ）。</p>
 *
 * <p><b>red の根拠</b>: 骨格の {@code listForParticipant} は {@code UnsupportedOperationException} を投げ、
 * Facade は依存フィールドを持たない（X8 の Gate 保持検査）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParticipantContactSpaceFacade 単体テスト（CMP-261118-0900）")
class ParticipantContactSpaceFacadeTest {

    private static final String SLUG = "team-a";
    private static final Long TEAM_ID = 30L;
    private static final Long TOURNAMENT_ID = 100L;
    private static final Long D1 = 201L;
    private static final Long D2 = 202L;
    private static final Long USER_ID = 10L;
    private static final ContactSpaceScopeType T = ContactSpaceScopeType.TOURNAMENT;
    private static final ContactSpaceScopeType D = ContactSpaceScopeType.TOURNAMENT_DIVISION;
    private static final ContactSpaceKind BULLETIN = ContactSpaceKind.BULLETIN;
    private static final ContactSpaceKind CHAT = ContactSpaceKind.CHAT;

    @Mock
    private TeamService teamService;
    @Mock
    private TournamentParticipantRepository participantRepository;
    @Mock
    private TournamentContactSpaceRepository contactSpaceRepository;
    @Mock
    private TournamentContactSpaceAccessGate gate;

    @InjectMocks
    private ParticipantContactSpaceFacade facade;

    private static TournamentContactSpaceEntity space(ContactSpaceScopeType scopeType, Long scopeId,
                                                      ContactSpaceKind kind, boolean isPublic, long refId) {
        TournamentContactSpaceEntity e = TournamentContactSpaceEntity.builder()
                .scopeType(scopeType).scopeId(scopeId).spaceKind(kind).refId(refId).isPublic(isPublic).build();
        e.setId(UUID.randomUUID());
        return e;
    }

    private static ParticipantContactDivisionRow row(Long divisionId, String divisionName, String tournamentName) {
        return new ParticipantContactDivisionRow() {
            @Override
            public Long getDivisionId() {
                return divisionId;
            }

            @Override
            public String getDivisionName() {
                return divisionName;
            }

            @Override
            public String getTournamentName() {
                return tournamentName;
            }
        };
    }

    private static Map<UUID, ContactAccessLevel> all(List<TournamentContactSpaceEntity> spaces, ContactAccessLevel level) {
        Map<UUID, ContactAccessLevel> m = new HashMap<>();
        spaces.forEach(s -> m.put(s.getId(), level));
        return m;
    }

    private void givenParticipating(List<ParticipantContactDivisionRow> rows) {
        given(teamService.resolveFollowableTeamId(SLUG)).willReturn(Optional.of(TEAM_ID));
        given(participantRepository.findParticipantContactDivisions(TEAM_ID, TOURNAMENT_ID, USER_ID)).willReturn(rows);
    }

    private static void assertNotFound(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND);
    }

    // ====================================================================
    // 拒否（AC-P07〜P09・P11・P14・P17・P35）
    // ====================================================================

    @Nested
    @DisplayName("拒否はすべて同一の TOUR_029")
    class Rejection {

        @Test
        @DisplayName("P11/P35: slug が解決できない（不在・アーカイブ済み・PROVISIONED・数値 slug）なら TOUR_029 で、参加判定・スペース取得・Gate を呼ばない")
        void slug解決不可はTOUR_029() {
            given(teamService.resolveFollowableTeamId(SLUG)).willReturn(Optional.empty());

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> facade.listForParticipant(SLUG, TOURNAMENT_ID, USER_ID));

            assertNotFound(thrown);
            verifyNoInteractions(participantRepository, contactSpaceRepository, gate);
        }

        @Test
        @DisplayName("P07〜P09/P14/P17: 参加判定が 0 行（非所属・非参加・参加外・大会不在）なら TOUR_029 で、スペース取得・Gate を呼ばない")
        void 参加判定0行はTOUR_029() {
            givenParticipating(List.of());

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> facade.listForParticipant(SLUG, TOURNAMENT_ID, USER_ID));

            assertNotFound(thrown);
            verifyNoInteractions(contactSpaceRepository, gate);
        }

        @Test
        @DisplayName("P11: slug 不在と参加判定 0 行の拒否は、同じエラーコード・同じメッセージ")
        void 拒否の形は一つ() {
            given(teamService.resolveFollowableTeamId("absent")).willReturn(Optional.empty());
            givenParticipating(List.of());

            Throwable bySlug = org.assertj.core.api.Assertions.catchThrowable(
                    () -> facade.listForParticipant("absent", TOURNAMENT_ID, USER_ID));
            Throwable byParticipation = org.assertj.core.api.Assertions.catchThrowable(
                    () -> facade.listForParticipant(SLUG, TOURNAMENT_ID, USER_ID));

            assertNotFound(bySlug);
            assertNotFound(byParticipation);
            assertThat(bySlug.getMessage()).isEqualTo(byParticipation.getMessage());
        }
    }

    // ====================================================================
    // 正常系（AC-P05・P16・P18・P20・X2）
    // ====================================================================

    @Nested
    @DisplayName("正常系")
    class Success {

        @Test
        @DisplayName("P05: tournamentName・divisions は参加判定の行から、divisionName は大会全体 null・部門は部門名、各項目はスペース行と Gate の水準から")
        void P05_応答の組み立て() {
            givenParticipating(List.of(row(D1, "1部", "春季大会")));
            TournamentContactSpaceEntity tb = space(T, TOURNAMENT_ID, BULLETIN, false, 11L);
            TournamentContactSpaceEntity tc = space(T, TOURNAMENT_ID, CHAT, true, 12L);
            TournamentContactSpaceEntity db = space(D, D1, BULLETIN, false, 13L);
            TournamentContactSpaceEntity dc = space(D, D1, CHAT, false, 14L);
            List<TournamentContactSpaceEntity> spaces = List.of(dc, tb, db, tc);
            given(contactSpaceRepository.findForTournamentAndDivisions(eq(TOURNAMENT_ID), anyCollection()))
                    .willReturn(spaces);
            given(gate.resolveLevelsForSpaces(eq(USER_ID), eq(TOURNAMENT_ID), anyCollection()))
                    .willReturn(all(spaces, ContactAccessLevel.VIEW));

            ParticipantContactSpacesResponse res = facade.listForParticipant(SLUG, TOURNAMENT_ID, USER_ID);

            assertThat(res.tournamentId()).isEqualTo(TOURNAMENT_ID);
            assertThat(res.tournamentName()).isEqualTo("春季大会");
            assertThat(res.divisions()).containsExactly(new ParticipantContactSpacesResponse.DivisionItem(D1, "1部"));
            assertThat(res.spaces()).containsExactly(
                    new SpaceItem(tb.getId(), T, TOURNAMENT_ID, null, BULLETIN, 11L, false, ContactAccessLevel.VIEW),
                    new SpaceItem(tc.getId(), T, TOURNAMENT_ID, null, CHAT, 12L, true, ContactAccessLevel.VIEW),
                    new SpaceItem(db.getId(), D, D1, "1部", BULLETIN, 13L, false, ContactAccessLevel.VIEW),
                    new SpaceItem(dc.getId(), D, D1, "1部", CHAT, 14L, false, ContactAccessLevel.VIEW));
        }

        @Test
        @DisplayName("X2/P16: 2 部門なら divisions は部門 ID 昇順、spaces は 大会全体→部門 ID 昇順（各 BULLETIN→CHAT）。取得順に依らない")
        void X2_並び順() {
            givenParticipating(List.of(row(D2, "2部", "大会"), row(D1, "1部", "大会")));
            TournamentContactSpaceEntity tb = space(T, TOURNAMENT_ID, BULLETIN, false, 0L);
            TournamentContactSpaceEntity tc = space(T, TOURNAMENT_ID, CHAT, false, 0L);
            TournamentContactSpaceEntity d1b = space(D, D1, BULLETIN, false, 0L);
            TournamentContactSpaceEntity d1c = space(D, D1, CHAT, false, 0L);
            TournamentContactSpaceEntity d2b = space(D, D2, BULLETIN, false, 0L);
            TournamentContactSpaceEntity d2c = space(D, D2, CHAT, false, 0L);
            List<TournamentContactSpaceEntity> spaces = List.of(d2c, d1c, tc, d2b, tb, d1b);
            given(contactSpaceRepository.findForTournamentAndDivisions(eq(TOURNAMENT_ID), anyCollection()))
                    .willReturn(spaces);
            given(gate.resolveLevelsForSpaces(eq(USER_ID), eq(TOURNAMENT_ID), anyCollection()))
                    .willReturn(all(spaces, ContactAccessLevel.POST));

            ParticipantContactSpacesResponse res = facade.listForParticipant(SLUG, TOURNAMENT_ID, USER_ID);

            assertThat(res.divisions()).extracting(ParticipantContactSpacesResponse.DivisionItem::divisionId)
                    .containsExactly(D1, D2);
            assertThat(res.spaces()).extracting(SpaceItem::id).containsExactly(
                    tb.getId(), tc.getId(), d1b.getId(), d1c.getId(), d2b.getId(), d2c.getId());
            assertThat(res.spaces()).extracting(SpaceItem::divisionName)
                    .containsExactly(null, null, "1部", "1部", "2部", "2部");
        }

        @Test
        @DisplayName("P34/P21: Gate のマップに無い行（閲覧不可）は応答から外し、例外にしない")
        void 閲覧不可の行は外す() {
            givenParticipating(List.of(row(D1, "1部", "大会")));
            TournamentContactSpaceEntity tb = space(T, TOURNAMENT_ID, BULLETIN, true, 0L);
            TournamentContactSpaceEntity tc = space(T, TOURNAMENT_ID, CHAT, false, 0L);
            given(contactSpaceRepository.findForTournamentAndDivisions(eq(TOURNAMENT_ID), anyCollection()))
                    .willReturn(List.of(tb, tc));
            given(gate.resolveLevelsForSpaces(eq(USER_ID), eq(TOURNAMENT_ID), anyCollection()))
                    .willReturn(Map.of(tb.getId(), ContactAccessLevel.VIEW));

            ParticipantContactSpacesResponse res = facade.listForParticipant(SLUG, TOURNAMENT_ID, USER_ID);

            assertThat(res.spaces()).extracting(SpaceItem::id).containsExactly(tb.getId());
            assertThat(res.spaces()).extracting(SpaceItem::accessLevel).doesNotContainNull();
        }

        @Test
        @DisplayName("P18: スペースが 0 件なら spaces=[]（例外にしない）、divisions は返す")
        void P18_スペース0件() {
            givenParticipating(List.of(row(D1, "1部", "大会")));
            given(contactSpaceRepository.findForTournamentAndDivisions(eq(TOURNAMENT_ID), anyCollection()))
                    .willReturn(List.of());
            org.mockito.Mockito.lenient()
                    .when(gate.resolveLevelsForSpaces(eq(USER_ID), eq(TOURNAMENT_ID), anyCollection()))
                    .thenReturn(Map.of());

            ParticipantContactSpacesResponse res = facade.listForParticipant(SLUG, TOURNAMENT_ID, USER_ID);

            assertThat(res.spaces()).isEmpty();
            assertThat(res.divisions()).extracting(ParticipantContactSpacesResponse.DivisionItem::divisionId)
                    .containsExactly(D1);
        }

        @Test
        @DisplayName("P20: スペース取得は参加中の部門 ID 群で 1 回、Gate の一括解決は取得した全行で 1 回だけ（部門ごとに呼ばない）")
        void P20_取得と一括解決は1回ずつ() {
            givenParticipating(List.of(row(D1, "1部", "大会"), row(D2, "2部", "大会")));
            List<TournamentContactSpaceEntity> spaces = List.of(
                    space(T, TOURNAMENT_ID, BULLETIN, false, 0L), space(D, D1, CHAT, false, 0L),
                    space(D, D2, BULLETIN, false, 0L));
            given(contactSpaceRepository.findForTournamentAndDivisions(eq(TOURNAMENT_ID), anyCollection()))
                    .willReturn(spaces);
            given(gate.resolveLevelsForSpaces(eq(USER_ID), eq(TOURNAMENT_ID), anyCollection()))
                    .willReturn(all(spaces, ContactAccessLevel.VIEW));

            facade.listForParticipant(SLUG, TOURNAMENT_ID, USER_ID);

            org.mockito.ArgumentCaptor<Collection<Long>> divisionIds = captorOfLongs();
            verify(contactSpaceRepository, times(1)).findForTournamentAndDivisions(eq(TOURNAMENT_ID), divisionIds.capture());
            assertThat(divisionIds.getValue()).containsExactlyInAnyOrder(D1, D2);
            org.mockito.ArgumentCaptor<Collection<TournamentContactSpaceEntity>> passed = captorOfSpaces();
            verify(gate, times(1)).resolveLevelsForSpaces(eq(USER_ID), eq(TOURNAMENT_ID), passed.capture());
            assertThat(passed.getValue()).containsExactlyInAnyOrderElementsOf(spaces);
            verify(participantRepository, times(1)).findParticipantContactDivisions(TEAM_ID, TOURNAMENT_ID, USER_ID);
            verify(participantRepository, never()).countActiveMemberOfDivisionParticipantTeam(anyLong(), any());
            verify(gate, never()).requireViewResolvingLevel(any(), anyLong(), any(), any(), any());
        }
    }

    // ====================================================================
    // 途中失敗（X12）
    // ====================================================================

    @Nested
    @DisplayName("X12: 参加確認後の読み取り失敗は握りつぶさない")
    class Failure {

        @Test
        @DisplayName("X12: スペース取得の DB 失敗はそのまま伝播する（200 の空・TOUR_029 に変換しない）")
        void スペース取得失敗は伝播() {
            givenParticipating(List.of(row(D1, "1部", "大会")));
            DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
            given(contactSpaceRepository.findForTournamentAndDivisions(eq(TOURNAMENT_ID), anyCollection()))
                    .willThrow(failure);

            assertThatThrownBy(() -> facade.listForParticipant(SLUG, TOURNAMENT_ID, USER_ID)).isSameAs(failure);
        }

        @Test
        @DisplayName("X12: Gate の一括解決の失敗はそのまま伝播する")
        void Gate失敗は伝播() {
            givenParticipating(List.of(row(D1, "1部", "大会")));
            given(contactSpaceRepository.findForTournamentAndDivisions(eq(TOURNAMENT_ID), anyCollection()))
                    .willReturn(List.of(space(T, TOURNAMENT_ID, BULLETIN, false, 0L)));
            DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
            given(gate.resolveLevelsForSpaces(eq(USER_ID), eq(TOURNAMENT_ID), anyCollection())).willThrow(failure);

            assertThatThrownBy(() -> facade.listForParticipant(SLUG, TOURNAMENT_ID, USER_ID)).isSameAs(failure);
        }
    }

    // ====================================================================
    // X8: TX なし・命名・Gate を直接持つ（AuthzTxFacadeRegistryArchTest は登録 Facade しか見ないため UT で固定）
    // ====================================================================

    @Nested
    @DisplayName("X8: 構造の番人")
    class Structure {

        private final Class<?> type = ParticipantContactSpaceFacade.class;

        @Test
        @DisplayName("X8: クラス・全メソッドに @Transactional（Spring・Jakarta）が無い")
        void TXを持たない() {
            assertThat(transactionalAnnotations(type.getAnnotations())).as("クラス").isEmpty();
            for (Method m : type.getDeclaredMethods()) {
                assertThat(transactionalAnnotations(m.getAnnotations())).as("メソッド %s", m.getName()).isEmpty();
            }
        }

        @Test
        @DisplayName("X8: 名前は *Facade で *AccessService/*AccessGuard/*AccessGate でなく、tournament.service に置く Spring Bean")
        void 命名と配置() {
            assertThat(type.getSimpleName()).endsWith("Facade")
                    .doesNotEndWith("AccessService").doesNotEndWith("AccessGuard").doesNotEndWith("AccessGate");
            assertThat(type.getPackageName()).isEqualTo("com.mannschaft.app.tournament.service");
            assertThat(type.isAnnotationPresent(Component.class) || type.isAnnotationPresent(Service.class)).isTrue();
        }

        @Test
        @DisplayName("X8: 認可は Gate を直接持って行う（TournamentContactSpaceAccessGate のフィールドがある）")
        void Gateを直接持つ() {
            List<Class<?>> fieldTypes = new ArrayList<>();
            for (Field field : type.getDeclaredFields()) {
                fieldTypes.add(field.getType());
            }
            assertThat(fieldTypes).contains(TournamentContactSpaceAccessGate.class);
        }

        private List<Annotation> transactionalAnnotations(Annotation[] annotations) {
            return Arrays.stream(annotations)
                    .filter(a -> a.annotationType() == org.springframework.transaction.annotation.Transactional.class
                            || a.annotationType() == jakarta.transaction.Transactional.class)
                    .toList();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static org.mockito.ArgumentCaptor<Collection<Long>> captorOfLongs() {
        return (org.mockito.ArgumentCaptor) org.mockito.ArgumentCaptor.forClass(Collection.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static org.mockito.ArgumentCaptor<Collection<TournamentContactSpaceEntity>> captorOfSpaces() {
        return (org.mockito.ArgumentCaptor) org.mockito.ArgumentCaptor.forClass(Collection.class);
    }
}
