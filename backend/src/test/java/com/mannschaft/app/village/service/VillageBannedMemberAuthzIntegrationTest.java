package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.controller.AbstractVillageIntegrationTest;
import com.mannschaft.app.village.dto.FestivalCreateRequest;
import com.mannschaft.app.village.dto.MeetupCandidateDateInput;
import com.mannschaft.app.village.dto.MeetupCreateRequest;
import com.mannschaft.app.village.dto.RepresentativeGrantRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMatchRecruitEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitStatus;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMatchRecruitRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * F17.1 — BAN / 退村メンバーの村内認可ガード統合テスト（#2284 §12）。
 *
 * <h2>本テストが守る不変条件</h2>
 *
 * <p>村ドメインの「HEADMAN or ELDER」判定は 6 名 8 実装にコピーされており、うち
 * {@code bannedAt} を検査していたのは 3 実装（{@code requireModerator} ×2 ＋
 * {@code checkVillageBulletinModerator}）だけだった。残り 5 実装
 * （祭り／お便り／参加申請／代表委任／試合募集レビュー）では
 * <strong>BAN された長老（ELDER）がモデレーション操作を実行できた</strong>。
 * 加えて寄合の {@code requireVillager} も BAN を素通ししていたため、
 * <strong>BAN された村人が寄合を作成できた</strong>。</p>
 *
 * <h2>テストの流儀（重要）</h2>
 *
 * <p><strong>実 MySQL Testcontainers を通す統合テストであること。</strong>
 * 本ドメインの認可判定は「リポジトリの derived query が {@code banned_at IS NULL} を
 * WHERE 句に含むか」に依存する。モック UT ではリポジトリの戻り値をテスト側が捏造するため、
 * 実 SQL の述語が欠けていても green になる（偽 green の既知事例が多数）。</p>
 *
 * <p>日時フィクスチャは文字列リテラルではなく {@link LocalDateTime} を bind する
 * （文字列リテラルは JST/UTC 境界で 9 時間ズレ、CI のみで再現する既知地雷）。</p>
 *
 * <h2>受け入れ条件との対応</h2>
 * <ul>
 *   <li>AC1 — BAN された ELDER のモデレーション操作は拒否される</li>
 *   <li>AC2 — BAN されていない ELDER は従来どおり成功する（過剰ブロックしていない裏取り）</li>
 *   <li>AC3 — BAN された VILLAGER の一般操作は拒否される</li>
 *   <li>AC4 — 退村済み（{@code leftAt} あり）のメンバーも拒否される</li>
 *   <li>AC5 — HEADMAN は影響を受けない</li>
 * </ul>
 */
@DisplayName("F17.1 BAN/退村メンバーの村内認可ガード（#2284 §12）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageBannedMemberAuthzIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    @Autowired
    private VillageFestivalService festivalService;

    @Autowired
    private VillageJoinRequestService joinRequestService;

    @Autowired
    private VillageRepresentativeService representativeService;

    @Autowired
    private VillageMeetupService meetupService;

    @Autowired
    private VillageMatchRecruitService matchRecruitService;

    @Autowired
    private VillageMatchRecruitRepository matchRecruitRepository;

    /** 代表委任のチーム所属判定は他ドメイン依存ゆえモック化（テストコスト圧縮）。 */
    @MockitoBean
    private UserRoleRepository userRoleRepository;

    private static final Long HEADMAN_USER_ID = 9_712_001L;
    private static final Long ACTIVE_ELDER_USER_ID = 9_712_002L;
    private static final Long BANNED_ELDER_USER_ID = 9_712_003L;
    private static final Long BANNED_VILLAGER_USER_ID = 9_712_004L;
    private static final Long LEFT_ELDER_USER_ID = 9_712_005L;
    private static final Long TEAM_ID = 9_712_500L;

    private UUID villageId;
    private UUID headmanMembershipId;

    @BeforeEach
    void setUp() {
        lenient().when(accessControlService.isSystemAdmin(anyLong())).thenReturn(false);
        lenient().when(userRoleRepository.existsByUserIdAndTeamId(anyLong(), anyLong())).thenReturn(true);
        lenient().when(userRoleRepository.existsByUserIdAndOrganizationId(anyLong(), anyLong())).thenReturn(false);

        LocalDateTime now = LocalDateTime.now();

        VillageEntity village = villageRepository.save(VillageEntity.builder()
                .slug("banned-authz-" + UUID.randomUUID().toString().substring(0, 8))
                .name("BAN認可検証村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.APPROVAL)
                .visibility(VillageVisibility.PUBLIC)
                .bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY)
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_USER_ID)
                .createdAt(now)
                .updatedAt(now)
                .version(0L)
                .build());
        villageId = village.getId();

        headmanMembershipId = saveMembership(HEADMAN_USER_ID, VillageRole.HEADMAN, null, null).getId();
        saveMembership(ACTIVE_ELDER_USER_ID, VillageRole.ELDER, null, null);
        // BAN 済み長老 — bannedAt を立てるが leftAt は NULL（＝村には在籍したまま BAN）
        saveMembership(BANNED_ELDER_USER_ID, VillageRole.ELDER, now.minusDays(1), null);
        saveMembership(BANNED_VILLAGER_USER_ID, VillageRole.VILLAGER, now.minusDays(1), null);
        // 退村済み長老 — leftAt を立てる（AC4）
        saveMembership(LEFT_ELDER_USER_ID, VillageRole.ELDER, null, now.minusDays(2));
    }

    private VillageMembershipEntity saveMembership(Long userId, VillageRole role,
                                                   LocalDateTime bannedAt, LocalDateTime leftAt) {
        LocalDateTime now = LocalDateTime.now();
        return membershipRepository.save(VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(now.minusDays(30))
                .bannedAt(bannedAt)
                .bannedReason(bannedAt != null ? "検証用BAN" : null)
                .leftAt(leftAt)
                .createdAt(now)
                .updatedAt(now)
                .version(0L)
                .build());
    }

    private FestivalCreateRequest festivalRequest() {
        LocalDateTime start = LocalDateTime.now().plusDays(7);
        return new FestivalCreateRequest("収穫祭", "説明", start, start.plusHours(3), null, "#047857");
    }

    private MeetupCreateRequest meetupRequest() {
        return new MeetupCreateRequest("寄合", "説明", "公民館",
                List.of(new MeetupCandidateDateInput(LocalDate.now().plusDays(10), null)));
    }

    private RepresentativeGrantRequest representativeGrantRequest() {
        return new RepresentativeGrantRequest(headmanMembershipId, HEADMAN_USER_ID, "検証");
    }

    /** 指定ユーザーを投稿者とする OPEN な試合募集を 1 件作る。 */
    private UUID saveOpenRecruit(Long postedByUserId) {
        LocalDateTime now = LocalDateTime.now();
        return matchRecruitRepository.save(VillageMatchRecruitEntity.builder()
                .villageId(villageId)
                .postedByUserId(postedByUserId)
                .category(VillageMatchRecruitCategory.PRACTICE_MATCH)
                .title("練習試合相手募集")
                .description("土曜午後で1チーム")
                .matchDate(LocalDate.now().plusDays(14))
                .status(VillageMatchRecruitStatus.OPEN)
                .createdAt(now)
                .updatedAt(now)
                .version(0L)
                .build()).getId();
    }

    // ========================================================================
    // AC1 — BAN された ELDER のモデレーション操作は拒否される
    // ========================================================================

    @Nested
    @DisplayName("AC1: BAN された長老（ELDER）のモデレーション操作は拒否される")
    class BannedElderIsRejected {

        @Test
        @DisplayName("祭りの作成（VillageFestivalService.requireHeadmanOrElder）")
        void createFestival() {
            assertThatThrownBy(() -> festivalService.createFestival(
                    villageId, festivalRequest(), BANNED_ELDER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
        }

        @Test
        @DisplayName("参加申請の審査一覧（VillageJoinRequestService.ensureReviewer）")
        void listJoinRequestsForReviewers() {
            assertThatThrownBy(() -> joinRequestService.listForReviewers(
                    villageId, BANNED_ELDER_USER_ID, VillageRequestStatus.PENDING, 0, 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
        }

        @Test
        @DisplayName("代表委任の付与（VillageRepresentativeService.ensureModerator）")
        void grantRepresentative() {
            assertThatThrownBy(() -> representativeService.grantRepresentative(
                    villageId, representativeGrantRequest(), BANNED_ELDER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
        }

        /**
         * ensureRecruitReviewer は「投稿者本人なら即 return」をメンバーシップ照会より前に置いていたため、
         * BAN された投稿者が自分の募集の状態遷移を続行できた（BAN 逃れの抜け道）。
         * 検査順序を入れ替えた根治の回帰防止柵。
         */
        @Test
        @DisplayName("BAN された投稿者は自分の募集でも締切できない（ensureRecruitReviewer の本人バイパス）")
        void closeOwnRecruitAsBannedAuthor() {
            UUID recruitId = saveOpenRecruit(BANNED_ELDER_USER_ID);
            assertThatThrownBy(() -> matchRecruitService.closeRecruit(
                    villageId, recruitId, BANNED_ELDER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
        }
    }

    // ========================================================================
    // AC2 — BAN されていない ELDER は従来どおり成功する（過剰ブロック検出）
    // ========================================================================

    @Nested
    @DisplayName("AC2: BAN されていない長老は従来どおり成功する")
    class ActiveElderStillSucceeds {

        @Test
        @DisplayName("祭りの作成は成功する")
        void createFestival() {
            assertThatCode(() -> festivalService.createFestival(
                    villageId, festivalRequest(), ACTIVE_ELDER_USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("参加申請の審査一覧は成功する")
        void listJoinRequestsForReviewers() {
            assertThatCode(() -> joinRequestService.listForReviewers(
                    villageId, ACTIVE_ELDER_USER_ID, VillageRequestStatus.PENDING, 0, 20))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("寄合の作成は成功する")
        void createMeetup() {
            assertThatCode(() -> meetupService.createMeetup(
                    villageId, meetupRequest(), ACTIVE_ELDER_USER_ID))
                    .doesNotThrowAnyException();
        }

        /** 本人バイパスの検査順序を入れ替えた影響で、現役の投稿者まで塞いでいないことの裏取り。 */
        @Test
        @DisplayName("BAN されていない投稿者は自分の募集を締切できる")
        void closeOwnRecruitAsActiveAuthor() {
            UUID recruitId = saveOpenRecruit(ACTIVE_ELDER_USER_ID);
            assertThatCode(() -> matchRecruitService.closeRecruit(
                    villageId, recruitId, ACTIVE_ELDER_USER_ID))
                    .doesNotThrowAnyException();
        }
    }

    // ========================================================================
    // AC3 — BAN された VILLAGER の一般操作は拒否される
    // ========================================================================

    @Nested
    @DisplayName("AC3: BAN された村人の一般操作は拒否される")
    class BannedVillagerIsRejected {

        @Test
        @DisplayName("寄合の作成（VillageMeetupService.requireVillager）")
        void createMeetup() {
            assertThatThrownBy(() -> meetupService.createMeetup(
                    villageId, meetupRequest(), BANNED_VILLAGER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.MEETUP_NOT_MEMBER);
        }
    }

    // ========================================================================
    // AC4 — 退村済み（leftAt あり）のメンバーも拒否される
    // ========================================================================

    @Nested
    @DisplayName("AC4: 退村済みメンバーも拒否される")
    class LeftMemberIsRejected {

        @Test
        @DisplayName("祭りの作成は拒否される")
        void createFestival() {
            assertThatThrownBy(() -> festivalService.createFestival(
                    villageId, festivalRequest(), LEFT_ELDER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
        }

        @Test
        @DisplayName("寄合の作成は拒否される")
        void createMeetup() {
            assertThatThrownBy(() -> meetupService.createMeetup(
                    villageId, meetupRequest(), LEFT_ELDER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.MEETUP_NOT_MEMBER);
        }
    }

    // ========================================================================
    // AC5 — HEADMAN は影響を受けない
    // ========================================================================

    @Nested
    @DisplayName("AC5: HEADMAN は影響を受けない")
    class HeadmanIsUnaffected {

        @Test
        @DisplayName("祭りの作成は成功する")
        void createFestival() {
            assertThatCode(() -> festivalService.createFestival(
                    villageId, festivalRequest(), HEADMAN_USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("参加申請の審査一覧は成功する")
        void listJoinRequestsForReviewers() {
            assertThatCode(() -> joinRequestService.listForReviewers(
                    villageId, HEADMAN_USER_ID, VillageRequestStatus.PENDING, 0, 20))
                    .doesNotThrowAnyException();
        }
    }
}
