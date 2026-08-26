package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.VillageJoinRequestRepository;
import com.mannschaft.app.village.repository.VillageLobbyDailyThreadRepository;
import com.mannschaft.app.village.repository.VillageMeetupAttendanceRepository;
import com.mannschaft.app.village.repository.VillageMeetupCandidateDateRepository;
import com.mannschaft.app.village.repository.VillageMeetupCommentRepository;
import com.mannschaft.app.village.repository.VillageMeetupRepository;
import com.mannschaft.app.village.repository.VillageMeetupTodoRepository;
import com.mannschaft.app.village.repository.VillageMeetupVoteRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * 村サービス群の<b>存在秘匿ゲート適用</b>を検証する試練（受け入れ条件 AC-1〜AC-5）。
 *
 * <p>本試練が守る契約は「<b>非公開(UNLISTED)村の存在を、非村人には一切悟らせない</b>」ことである。
 * よって HTTP ステータスではなく <b>{@link VillageErrorCode} そのもの</b>を、
 * 「実在しない村 ID を同じ入口へ投げたとき」の応答と突き合わせる。
 * ステータスだけを見ると、たとえば 404 同士でも {@code error.code} が
 * {@code VILLAGE_007}（非メンバー）と {@code VILLAGE_001}（不在）に割れて存在が漏れるため、
 * <b>コード一致まで見ないと番人にならない</b>。</p>
 *
 * <p>代表として寄合 / メンバーシップ / 参加申請 / ロビーの 4 サービスを取る。
 * ゲート自体の網羅組合せは {@link VillageAccessGateTest} が持ち、ここは
 * 「各サービスがそのゲートを実際に通しているか」の結線を見る。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("村サービス群 — 非公開村の存在秘匿（ゲート結線）")
class VillageServiceExistenceHidingTest {

    private static final UUID VILLAGE_ID = UUID.fromString("019200aa-0000-7000-8000-000000000001");
    /** 実在しない村 ID。UNLISTED 村への応答がこれと一致することが AC-1 の本体。 */
    private static final UUID MISSING_VILLAGE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000ff");

    private static final Long STRANGER_ID = 7001L;
    private static final Long MEMBER_ID = 7002L;
    private static final Long BANNED_ID = 7003L;
    private static final Long LEFT_ID = 7004L;
    private static final Long ADMIN_ID = 7005L;

    // --- 共通 ---
    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private VillageAccessGate accessGate;

    // --- 寄合 ---
    @Mock
    private VillageMeetupRepository meetupRepository;
    @Mock
    private VillageMeetupCandidateDateRepository candidateDateRepository;
    @Mock
    private VillageMeetupVoteRepository voteRepository;
    @Mock
    private VillageMeetupAttendanceRepository attendanceRepository;
    @Mock
    private VillageMeetupCommentRepository commentRepository;
    @Mock
    private VillageMeetupTodoRepository todoRepository;
    @Mock
    private UserVillageNicknameRepository nicknameRepository;
    @Mock
    private VillageNicknameResolver villageNicknameResolver;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    // --- メンバーシップ ---
    @Mock
    private UserRoleRepository userRoleRepository;

    // --- 参加申請 ---
    @Mock
    private VillageJoinRequestRepository joinRequestRepository;
    @Mock
    private VillageMembershipService membershipService;

    // --- ロビー ---
    @Mock
    private VillageLobbyDailyThreadRepository dailyThreadRepository;
    @Mock
    private ChatChannelRepository chatChannelRepository;

    @InjectMocks
    private VillageMeetupService meetupService;
    @InjectMocks
    private VillageMembershipService membershipServiceUnderTest;
    @InjectMocks
    private VillageJoinRequestService joinRequestService;
    @InjectMocks
    private VillageLobbyService lobbyService;

    @BeforeEach
    void wireGate() {
        VillageAccessGateTestSupport.delegateToRealGate(
                accessGate, villageRepository, membershipRepository, accessControlService);
        lenient().when(villageRepository.findById(MISSING_VILLAGE_ID)).thenReturn(Optional.empty());
        lenient().when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(MISSING_VILLAGE_ID))
                .thenReturn(Optional.empty());
        lenient().when(membershipRepository.findActiveByVillageIdAndSubject(any(), any(), anyLong()))
                .thenReturn(Optional.empty());
        lenient().when(membershipRepository
                        .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(any(), any(), anyLong()))
                .thenReturn(Optional.empty());
        lenient().when(accessControlService.isSystemAdmin(anyLong())).thenReturn(false);
    }

    // ==================================================================
    // フィクスチャ
    // ==================================================================

    private VillageEntity village(VillageVisibility visibility, LocalDateTime archivedAt) {
        VillageEntity v = VillageEntity.builder()
                .slug("hidden-village")
                .name("試験村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(visibility)
                .archivedAt(archivedAt)
                .memberCountCache(3L)
                .build();
        v.setId(VILLAGE_ID);
        return v;
    }

    private void givenVillage(VillageVisibility visibility, LocalDateTime archivedAt) {
        VillageEntity v = village(visibility, archivedAt);
        lenient().when(villageRepository.findById(VILLAGE_ID)).thenReturn(Optional.of(v));
        lenient().when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                .thenReturn(archivedAt == null ? Optional.of(v) : Optional.empty());
    }

    /** 現役の村人にする（BAN 済み・退村済みは findActive が空を返すため、既定のままでよい）。 */
    private void givenActiveMember(Long userId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.HEADMAN)
                .joinedAt(LocalDateTime.now().minusDays(30))
                .build();
        m.setId(UUID.randomUUID());
        lenient().when(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(userId))).thenReturn(Optional.of(m));
        lenient().when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(userId))).thenReturn(Optional.of(m));
        Page<VillageMembershipEntity> page = new PageImpl<>(List.of(m));
        lenient().when(membershipRepository
                        .findByVillageIdAndLeftAtIsNullOrderByJoinedAtAsc(eq(VILLAGE_ID), any(Pageable.class)))
                .thenReturn(page);
        lenient().when(joinRequestRepository
                        .findByVillageIdAndRequesterUserIdOrderByCreatedAtDesc(eq(VILLAGE_ID), anyLong()))
                .thenReturn(List.of());
    }

    private VillageErrorCode codeOf(Throwable t) {
        assertThat(t).as("BusinessException が投げられること").isInstanceOf(BusinessException.class);
        return (VillageErrorCode) ((BusinessException) t).getErrorCode();
    }

    // ==================================================================
    // AC-1: UNLISTED 村の非村人 = 不在の村 ID と ErrorCode が完全一致
    // ==================================================================

    @Nested
    @DisplayName("AC-1: UNLISTED 村の非村人は『不在の村 ID』と同一の ErrorCode を受け取る")
    class Ac1UnlistedStrangerMatchesMissing {

        @Test
        @DisplayName("寄合一覧: UNLISTED 非村人 == 不在村 ID == VILLAGE_NOT_FOUND")
        void meetupList() {
            givenVillage(VillageVisibility.UNLISTED, null);

            VillageErrorCode unlisted = codeOf(catchThrowable(
                    () -> meetupService.listMeetups(VILLAGE_ID, null, STRANGER_ID, Pageable.unpaged())));
            VillageErrorCode missing = codeOf(catchThrowable(
                    () -> meetupService.listMeetups(MISSING_VILLAGE_ID, null, STRANGER_ID, Pageable.unpaged())));

            assertThat(unlisted).isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
            assertThat(unlisted).as("非公開村と不在村の応答コードが割れると存在が漏れる").isEqualTo(missing);
        }

        @Test
        @DisplayName("メンバー一覧: UNLISTED 非村人 == 不在村 ID == VILLAGE_NOT_FOUND")
        void membershipList() {
            givenVillage(VillageVisibility.UNLISTED, null);

            VillageErrorCode unlisted = codeOf(catchThrowable(
                    () -> membershipServiceUnderTest.listMembers(VILLAGE_ID, STRANGER_ID, 0, 20)));
            VillageErrorCode missing = codeOf(catchThrowable(
                    () -> membershipServiceUnderTest.listMembers(MISSING_VILLAGE_ID, STRANGER_ID, 0, 20)));

            assertThat(unlisted).isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
            assertThat(unlisted).isEqualTo(missing);
        }

        @Test
        @DisplayName("参加申請（自分の申請一覧）: UNLISTED 非村人 == 不在村 ID == VILLAGE_NOT_FOUND")
        void joinRequestListMine() {
            givenVillage(VillageVisibility.UNLISTED, null);

            VillageErrorCode unlisted = codeOf(catchThrowable(
                    () -> joinRequestService.listMine(VILLAGE_ID, STRANGER_ID)));
            VillageErrorCode missing = codeOf(catchThrowable(
                    () -> joinRequestService.listMine(MISSING_VILLAGE_ID, STRANGER_ID)));

            assertThat(unlisted).isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
            assertThat(unlisted).isEqualTo(missing);
        }

        @Test
        @DisplayName("ロビー: UNLISTED 非村人 == 不在村 ID == VILLAGE_NOT_FOUND")
        void lobbyChannel() {
            givenVillage(VillageVisibility.UNLISTED, null);

            VillageErrorCode unlisted = codeOf(catchThrowable(
                    () -> lobbyService.getLobbyChannel(VILLAGE_ID, STRANGER_ID)));
            VillageErrorCode missing = codeOf(catchThrowable(
                    () -> lobbyService.getLobbyChannel(MISSING_VILLAGE_ID, STRANGER_ID)));

            assertThat(unlisted).isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
            assertThat(unlisted).isEqualTo(missing);
        }
    }

    // ==================================================================
    // AC-2: PUBLIC 村の非村人は従来どおり 403 系（404 に倒れないこと）
    // ==================================================================

    @Nested
    @DisplayName("AC-2: PUBLIC 村の非村人は従来どおりの『非メンバー』コードのまま")
    class Ac2PublicStrangerKeeps403 {

        @Test
        @DisplayName("寄合一覧: PUBLIC 非村人は MEETUP_NOT_MEMBER（VILLAGE_074）のまま")
        void meetupList() {
            givenVillage(VillageVisibility.PUBLIC, null);

            assertThat(codeOf(catchThrowable(
                    () -> meetupService.listMeetups(VILLAGE_ID, null, STRANGER_ID, Pageable.unpaged()))))
                    .as("公開村は存在が秘密ではない。404 に倒すと正しい案内を奪うだけの改悪になる")
                    .isEqualTo(VillageErrorCode.MEETUP_NOT_MEMBER);
        }

        @Test
        @DisplayName("メンバー一覧: PUBLIC 非村人は NOT_MEMBER（VILLAGE_007）のまま")
        void membershipList() {
            givenVillage(VillageVisibility.PUBLIC, null);

            assertThat(codeOf(catchThrowable(
                    () -> membershipServiceUnderTest.listMembers(VILLAGE_ID, STRANGER_ID, 0, 20))))
                    .isEqualTo(VillageErrorCode.NOT_MEMBER);
        }

        @Test
        @DisplayName("ロビー: PUBLIC 非村人は NOT_MEMBER（VILLAGE_007）のまま")
        void lobbyChannel() {
            givenVillage(VillageVisibility.PUBLIC, null);

            assertThat(codeOf(catchThrowable(
                    () -> lobbyService.getLobbyChannel(VILLAGE_ID, STRANGER_ID))))
                    .isEqualTo(VillageErrorCode.NOT_MEMBER);
        }

        @Test
        @DisplayName("参加申請（自分の申請一覧）: PUBLIC 非村人は従来どおり成功する")
        void joinRequestListMine() {
            givenVillage(VillageVisibility.PUBLIC, null);
            lenient().when(joinRequestRepository
                            .findByVillageIdAndRequesterUserIdOrderByCreatedAtDesc(eq(VILLAGE_ID), anyLong()))
                    .thenReturn(List.of());

            assertThatCode(() -> joinRequestService.listMine(VILLAGE_ID, STRANGER_ID))
                    .doesNotThrowAnyException();
        }
    }

    // ==================================================================
    // AC-3: UNLISTED 村の現役村人・SYSTEM_ADMIN は従来どおり通る
    // ==================================================================

    @Nested
    @DisplayName("AC-3: UNLISTED 村の現役村人・SYSTEM_ADMIN はゲートを通過する")
    class Ac3MemberAndAdminPass {

        @Test
        @DisplayName("メンバー一覧: UNLISTED の現役村人は従来どおり取得できる")
        void memberCanList() {
            givenVillage(VillageVisibility.UNLISTED, null);
            givenActiveMember(MEMBER_ID);

            assertThatCode(() -> membershipServiceUnderTest.listMembers(VILLAGE_ID, MEMBER_ID, 0, 20))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("参加申請（自分の申請一覧）: UNLISTED の現役村人は従来どおり取得できる")
        void memberCanListMine() {
            givenVillage(VillageVisibility.UNLISTED, null);
            givenActiveMember(MEMBER_ID);

            assertThatCode(() -> joinRequestService.listMine(VILLAGE_ID, MEMBER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は UNLISTED 村でも村の存在確認を通過する（403 系で止まり 404 にはならない）")
        void systemAdminPassesGate() {
            givenVillage(VillageVisibility.UNLISTED, null);
            lenient().when(accessControlService.isSystemAdmin(ADMIN_ID)).thenReturn(true);

            assertThat(codeOf(catchThrowable(
                    () -> membershipServiceUnderTest.listMembers(VILLAGE_ID, ADMIN_ID, 0, 20))))
                    .as("SYSTEM_ADMIN には村の存在自体は見える。止まるなら村人判定（403 系）であるべき")
                    .isEqualTo(VillageErrorCode.NOT_MEMBER);
        }
    }

    // ==================================================================
    // AC-4: 凍結済み UNLISTED 村の非村人は 409 ではなく VILLAGE_NOT_FOUND
    // ==================================================================

    @Nested
    @DisplayName("AC-4: 凍結済み村 — UNLISTED は 404 に畳み、PUBLIC は従来どおり")
    class Ac4ArchivedVillage {

        @Test
        @DisplayName("凍結済み UNLISTED 村の非村人は VILLAGE_ALREADY_ARCHIVED を漏らさず VILLAGE_NOT_FOUND")
        void archivedUnlistedHidesArchivedCode() {
            givenVillage(VillageVisibility.UNLISTED, LocalDateTime.now().minusDays(1));

            VillageErrorCode unlisted = codeOf(catchThrowable(
                    () -> membershipServiceUnderTest.listMembers(VILLAGE_ID, STRANGER_ID, 0, 20)));

            assertThat(unlisted)
                    .as("409 が返ると『凍結された村がそこに実在する』と分かってしまう")
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("凍結済み PUBLIC 村は従来どおり VILLAGE_ALREADY_ARCHIVED（409）のまま")
        void archivedPublicKeepsArchivedCode() {
            givenVillage(VillageVisibility.PUBLIC, LocalDateTime.now().minusDays(1));

            assertThat(codeOf(catchThrowable(
                    () -> membershipServiceUnderTest.listMembers(VILLAGE_ID, STRANGER_ID, 0, 20))))
                    .isEqualTo(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }
    }

    // ==================================================================
    // AC-5: BAN 済み・退村済みの元村人は UNLISTED 村で VILLAGE_NOT_FOUND
    // ==================================================================

    @Nested
    @DisplayName("AC-5: BAN 済み・退村済みの元村人は UNLISTED 村で VILLAGE_NOT_FOUND")
    class Ac5FormerMembers {

        /**
         * BAN 済み / 退村済みは {@code findActiveByVillageIdAndSubject} が空を返す（#2284 §12）。
         * すなわち既定フィクスチャ（現役メンバー無し）がそのまま両者の状態を表す。
         */
        @Test
        @DisplayName("BAN 済みの元村人はロビーで VILLAGE_NOT_FOUND")
        void bannedMember() {
            givenVillage(VillageVisibility.UNLISTED, null);

            assertThat(codeOf(catchThrowable(() -> lobbyService.getLobbyChannel(VILLAGE_ID, BANNED_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("退村済みの元村人は寄合一覧で VILLAGE_NOT_FOUND")
        void leftMember() {
            givenVillage(VillageVisibility.UNLISTED, null);

            assertThat(codeOf(catchThrowable(
                    () -> meetupService.listMeetups(VILLAGE_ID, null, LEFT_ID, Pageable.unpaged()))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
    }
}
