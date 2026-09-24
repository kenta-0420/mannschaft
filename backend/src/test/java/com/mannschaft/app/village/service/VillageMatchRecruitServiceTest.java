package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MatchApplicationCreateRequest;
import com.mannschaft.app.village.dto.MatchApplicationResponse;
import com.mannschaft.app.village.dto.MatchApplicationReviewRequest;
import com.mannschaft.app.village.dto.MatchRecruitCreateRequest;
import com.mannschaft.app.village.dto.MatchRecruitResponse;
import com.mannschaft.app.village.dto.MatchRecruitUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMatchRecruitApplicationEntity;
import com.mannschaft.app.village.entity.VillageMatchRecruitEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageMatchApplicationStatus;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMatchRecruitApplicationRepository;
import com.mannschaft.app.village.repository.VillageMatchRecruitRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F17.1 Phase 2 U6 — VillageMatchRecruitService 単体テスト。
 *
 * <p>カバレッジ（17 ケース）:</p>
 * <ol>
 *   <li>募集作成成功（村人による）</li>
 *   <li>募集作成 — 非村人なら VILLAGE_007</li>
 *   <li>募集作成 — 時刻順序不正なら VILLAGE_065</li>
 *   <li>募集作成 — BAN 中は非村人と同じ VILLAGE_007 に畳まれる</li>
 *   <li>更新 — 投稿者本人 OK</li>
 *   <li>更新 — 第三者なら COMMON_002</li>
 *   <li>更新 — OPEN 以外なら VILLAGE_064</li>
 *   <li>締切 — 投稿者本人なら CLOSED</li>
 *   <li>締切 — HEADMAN なら CLOSED（投稿者以外の権限委譲）</li>
 *   <li>取消 — VILLAGER（非投稿者）なら VILLAGE_024</li>
 *   <li>応募成功（村人 + OPEN 募集）</li>
 *   <li>応募 — OPEN 以外なら VILLAGE_064</li>
 *   <li>応募 — PENDING 重複なら VILLAGE_067</li>
 *   <li>応募 — 自分の募集に応募は COMMON_002</li>
 *   <li>応募取下げ — 本人なら WITHDRAWN</li>
 *   <li>応募取下げ — 第三者なら COMMON_002</li>
 *   <li>応募審査 — status=PENDING を指定すると VILLAGE_068</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F17.1 VillageMatchRecruitService 単体テスト")
class VillageMatchRecruitServiceTest {

    @Mock private VillageRepository villageRepository;
    @Mock private VillageMembershipRepository membershipRepository;
    @Mock private VillageMatchRecruitRepository recruitRepository;
    @Mock private VillageMatchRecruitApplicationRepository applicationRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private OrganizationRepository organizationRepository;
    /**
     * F17.3 前工程リファクタで表示名解決が共有ヘルパ {@link VillageNicknameResolver} へ移設された。
     * 従来の resolveUserDisplayName はニックネーム未登録時 {@code "USER:#id"} を返していたため、
     * その出力を lenient スタブで完全再現し、既存アサーションのふるまいを不変に保つ（骨抜き禁止）。
     */
    @Mock private VillageNicknameResolver villageNicknameResolver;

    /** 村の存在秘匿ゲート。実物へ委譲させるため {@link VillageAccessGateTestSupport} で結線する。 */
    @Mock
    private VillageAccessGate accessGate;

    @InjectMocks
    private VillageMatchRecruitService service;

    /**
     * 村サービスの村存在確認は {@link VillageAccessGate} へ移った。
     * モックのゲートに実物のゲート（同じモックのリポジトリを注入）を委譲させることで、
     * 本テストが積み上げてきた {@code villageRepository.findById} の stub をそのまま生かしつつ、
     * 可視性判定は実物のロジックで走らせる。
     */
    @BeforeEach
    void wireVillageAccessGate() {
        VillageAccessGateTestSupport.delegateToRealGate(accessGate, villageRepository, membershipRepository);
    }

    @org.junit.jupiter.api.BeforeEach
    void stubNicknameResolver() {
        org.mockito.Mockito.lenient()
                .when(villageNicknameResolver.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    Long uid = inv.getArgument(0);
                    return uid == null ? null : "USER:#" + uid;
                });
    }

    private static final UUID VILLAGE_ID = UUID.randomUUID();
    private static final Long ACTOR = 100L;
    private static final Long OTHER = 200L;
    private static final Long ELDER_USER = 300L;

    // ------------------------------------------------------------------------
    // ヘルパ
    // ------------------------------------------------------------------------

    private VillageEntity activeVillage() {
        return VillageEntity.builder()
                .slug("dojo")
                .name("道場")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(1L)
                .createdByUserId(50L)
                .build();
    }

    private VillageMembershipEntity villagerMembership(Long userId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        return m;
    }

    private VillageMembershipEntity roleMembership(Long userId, VillageRole role) {
        VillageMembershipEntity m = villagerMembership(userId);
        m.setRole(role);
        return m;
    }

    private MatchRecruitCreateRequest validCreate() {
        return new MatchRecruitCreateRequest(
                VillageMatchRecruitCategory.PRACTICE_MATCH,
                "練習試合相手募集",
                "土曜午後で1チーム",
                LocalDate.of(2026, 6, 15),
                LocalTime.of(14, 0),
                LocalTime.of(16, 0),
                "市営グラウンド",
                1,
                "DM 連絡",
                LocalDateTime.of(2026, 6, 10, 23, 59),
                null
        );
    }

    private VillageMatchRecruitEntity openRecruit(Long postedBy) {
        VillageMatchRecruitEntity e = VillageMatchRecruitEntity.builder()
                .villageId(VILLAGE_ID)
                .postedByUserId(postedBy)
                .category(VillageMatchRecruitCategory.PRACTICE_MATCH)
                .title("test")
                .matchDate(LocalDate.of(2026, 6, 15))
                .matchTimeStart(LocalTime.of(14, 0))
                .matchTimeEnd(LocalTime.of(16, 0))
                .status(VillageMatchRecruitStatus.OPEN)
                .build();
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        return e;
    }

    private VillageMatchRecruitApplicationEntity pendingApplication(UUID recruitId, Long applicantUserId) {
        VillageMatchRecruitApplicationEntity a = VillageMatchRecruitApplicationEntity.builder()
                .recruitId(recruitId)
                .applicantUserId(applicantUserId)
                .status(VillageMatchApplicationStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        return a;
    }

    // ------------------------------------------------------------------------
    // 1. 募集作成成功
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("01. 募集作成成功 — 村人による作成で OPEN 状態保存")
    void create_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(villagerMembership(ACTOR)));
        given(recruitRepository.save(any())).willAnswer(inv -> {
            VillageMatchRecruitEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            e.setCreatedAt(LocalDateTime.now());
            return e;
        });

        MatchRecruitResponse res = service.createRecruit(VILLAGE_ID, validCreate(), ACTOR);

        assertThat(res.status()).isEqualTo(VillageMatchRecruitStatus.OPEN);
        assertThat(res.category()).isEqualTo(VillageMatchRecruitCategory.PRACTICE_MATCH);
        assertThat(res.postedByUserId()).isEqualTo(ACTOR);

        ArgumentCaptor<VillageMatchRecruitEntity> cap = ArgumentCaptor.forClass(VillageMatchRecruitEntity.class);
        verify(recruitRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(VillageMatchRecruitStatus.OPEN);
    }

    // ------------------------------------------------------------------------
    // 2. 募集作成 — 非村人
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("02. 募集作成 — 非村人なら VILLAGE_007 (NOT_MEMBER)")
    void create_notMember() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRecruit(VILLAGE_ID, validCreate(), ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.NOT_MEMBER);

        verify(recruitRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------
    // 3. 募集作成 — 時刻順序不正
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("03. 募集作成 — 試合終了 < 開始 なら VILLAGE_065")
    void create_invalidTime() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(villagerMembership(ACTOR)));

        MatchRecruitCreateRequest bad = new MatchRecruitCreateRequest(
                VillageMatchRecruitCategory.PRACTICE_MATCH,
                "x", null, LocalDate.of(2026, 6, 15),
                LocalTime.of(16, 0), LocalTime.of(14, 0),
                null, null, null, null, null);

        assertThatThrownBy(() -> service.createRecruit(VILLAGE_ID, bad, ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.MATCH_RECRUIT_TIME_INVALID);

        verify(recruitRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------
    // 4. 募集作成 — BAN 中
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("04. 募集作成 — BAN 中ユーザーは非村人と同じ VILLAGE_007 (NOT_MEMBER) に畳まれる")
    void create_banned() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        // findActiveByVillageIdAndSubject は BAN 済みを現役メンバーとして返さない
        // （BAN・退村・非村人はいずれも Optional.empty() → NOT_MEMBER に畳んで状態を秘匿する）。
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRecruit(VILLAGE_ID, validCreate(), ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    // ------------------------------------------------------------------------
    // 5. 更新 — 投稿者本人 OK
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("05. 更新 — 投稿者本人なら title 更新成功")
    void update_byAuthor() {
        VillageMatchRecruitEntity entity = openRecruit(ACTOR);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(villagerMembership(ACTOR)));
        given(recruitRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        MatchRecruitUpdateRequest req = new MatchRecruitUpdateRequest(
                null, "更新後タイトル", null, null, null, null, null, null, null, null, null);

        MatchRecruitResponse res = service.updateRecruit(VILLAGE_ID, entity.getId(), req, ACTOR);

        assertThat(res.title()).isEqualTo("更新後タイトル");
    }

    // ------------------------------------------------------------------------
    // 6. 更新 — 第三者は禁止
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("06. 更新 — 投稿者以外なら COMMON_002")
    void update_byOther() {
        VillageMatchRecruitEntity entity = openRecruit(ACTOR);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));

        MatchRecruitUpdateRequest req = new MatchRecruitUpdateRequest(
                null, "他人の改ざん", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.updateRecruit(VILLAGE_ID, entity.getId(), req, OTHER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(recruitRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------
    // 7. 更新 — OPEN 以外
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("07. 更新 — OPEN 以外なら VILLAGE_064")
    void update_notOpen() {
        VillageMatchRecruitEntity entity = openRecruit(ACTOR);
        entity.setStatus(VillageMatchRecruitStatus.CLOSED);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(villagerMembership(ACTOR)));

        MatchRecruitUpdateRequest req = new MatchRecruitUpdateRequest(
                null, "x", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.updateRecruit(VILLAGE_ID, entity.getId(), req, ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.MATCH_RECRUIT_NOT_OPEN);
    }

    // ------------------------------------------------------------------------
    // 7b. 更新 — BAN 済み投稿者は本人でも締め出す（認可 Wave3・村ロットA・真の穴の修正）
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("07b. 更新 — BAN 済みの投稿者本人は更新できない（現役性チェック）")
    void update_bannedAuthor() {
        VillageMatchRecruitEntity entity = openRecruit(ACTOR);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));
        // BAN 済み（退村扱い）のため findActiveByVillageIdAndSubject は現役メンバーを返さない
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.empty());

        MatchRecruitUpdateRequest req = new MatchRecruitUpdateRequest(
                null, "BAN逃れの改ざん", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.updateRecruit(VILLAGE_ID, entity.getId(), req, ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(recruitRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------
    // 8. 締切 — 投稿者本人
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("08. 締切 — 投稿者本人なら CLOSED に遷移")
    void close_byAuthor() {
        VillageMatchRecruitEntity entity = openRecruit(ACTOR);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));
        // 投稿者本人であっても「現役メンバーであること」が前提（#2284 §12）。
        // BAN / 退村した投稿者は自分の募集でもレビュー不可のため、現役メンバーシップを stub する。
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(villagerMembership(ACTOR)));
        given(recruitRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        MatchRecruitResponse res = service.closeRecruit(VILLAGE_ID, entity.getId(), ACTOR);

        assertThat(res.status()).isEqualTo(VillageMatchRecruitStatus.CLOSED);
    }

    // ------------------------------------------------------------------------
    // 9. 締切 — HEADMAN なら他人の募集でも可
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("09. 締切 — HEADMAN なら他人の募集でも CLOSED 可能")
    void close_byHeadman() {
        VillageMatchRecruitEntity entity = openRecruit(OTHER); // 投稿者は OTHER

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(roleMembership(ACTOR, VillageRole.HEADMAN)));
        given(recruitRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        MatchRecruitResponse res = service.closeRecruit(VILLAGE_ID, entity.getId(), ACTOR);

        assertThat(res.status()).isEqualTo(VillageMatchRecruitStatus.CLOSED);
    }

    // ------------------------------------------------------------------------
    // 10. 取消 — VILLAGER（非投稿者）は禁止
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("10. 取消 — 投稿者でない VILLAGER なら VILLAGE_024")
    void cancel_byVillagerNotAuthor() {
        VillageMatchRecruitEntity entity = openRecruit(OTHER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(villagerMembership(ACTOR)));

        assertThatThrownBy(() -> service.cancelRecruit(VILLAGE_ID, entity.getId(), ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);

        verify(recruitRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------
    // 11. 応募成功
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("11. 応募成功 — OPEN 募集 + 村人 + 重複なし")
    void apply_success() {
        VillageMatchRecruitEntity entity = openRecruit(OTHER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(villagerMembership(ACTOR)));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));
        given(applicationRepository.findByRecruitIdAndApplicantUserIdAndStatus(
                eq(entity.getId()), eq(ACTOR), eq(VillageMatchApplicationStatus.PENDING)))
                .willReturn(Optional.empty());
        given(applicationRepository.save(any())).willAnswer(inv -> {
            VillageMatchRecruitApplicationEntity a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            a.setCreatedAt(LocalDateTime.now());
            return a;
        });

        MatchApplicationResponse res = service.applyToRecruit(
                VILLAGE_ID, entity.getId(),
                new MatchApplicationCreateRequest("よろしくお願いします", null), ACTOR);

        assertThat(res.status()).isEqualTo(VillageMatchApplicationStatus.PENDING);
        assertThat(res.applicantUserId()).isEqualTo(ACTOR);
        assertThat(res.message()).isEqualTo("よろしくお願いします");
    }

    // ------------------------------------------------------------------------
    // 12. 応募 — OPEN 以外
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("12. 応募 — OPEN 以外なら VILLAGE_064")
    void apply_notOpen() {
        VillageMatchRecruitEntity entity = openRecruit(OTHER);
        entity.setStatus(VillageMatchRecruitStatus.CLOSED);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(villagerMembership(ACTOR)));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.applyToRecruit(
                VILLAGE_ID, entity.getId(), new MatchApplicationCreateRequest(null, null), ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.MATCH_RECRUIT_NOT_OPEN);

        verify(applicationRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------
    // 13. 応募 — PENDING 重複
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("13. 応募 — 同ユーザーの PENDING 重複なら VILLAGE_067")
    void apply_duplicatePending() {
        VillageMatchRecruitEntity entity = openRecruit(OTHER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(villagerMembership(ACTOR)));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));
        given(applicationRepository.findByRecruitIdAndApplicantUserIdAndStatus(
                eq(entity.getId()), eq(ACTOR), eq(VillageMatchApplicationStatus.PENDING)))
                .willReturn(Optional.of(pendingApplication(entity.getId(), ACTOR)));

        assertThatThrownBy(() -> service.applyToRecruit(
                VILLAGE_ID, entity.getId(), new MatchApplicationCreateRequest(null, null), ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.MATCH_APPLICATION_DUPLICATE);

        verify(applicationRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------
    // 14. 応募 — 自分の募集に自分で応募
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("14. 応募 — 投稿者本人が自分の募集に応募 → COMMON_002")
    void apply_selfRecruit() {
        VillageMatchRecruitEntity entity = openRecruit(ACTOR); // 投稿者 = ACTOR

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(villagerMembership(ACTOR)));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.applyToRecruit(
                VILLAGE_ID, entity.getId(), new MatchApplicationCreateRequest(null, null), ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(applicationRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------
    // 15. 応募取下げ — 本人
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("15. 応募取下げ — 応募者本人なら WITHDRAWN")
    void withdraw_byApplicant() {
        VillageMatchRecruitEntity entity = openRecruit(OTHER);
        VillageMatchRecruitApplicationEntity app = pendingApplication(entity.getId(), ACTOR);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));
        given(applicationRepository.findById(app.getId())).willReturn(Optional.of(app));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        MatchApplicationResponse res = service.withdrawApplication(
                VILLAGE_ID, entity.getId(), app.getId(), ACTOR);

        assertThat(res.status()).isEqualTo(VillageMatchApplicationStatus.WITHDRAWN);
    }

    // ------------------------------------------------------------------------
    // 16. 応募取下げ — 第三者は禁止
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("16. 応募取下げ — 第三者なら COMMON_002")
    void withdraw_byOther() {
        VillageMatchRecruitEntity entity = openRecruit(OTHER);
        VillageMatchRecruitApplicationEntity app = pendingApplication(entity.getId(), ACTOR);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));
        given(applicationRepository.findById(app.getId())).willReturn(Optional.of(app));

        assertThatThrownBy(() -> service.withdrawApplication(
                VILLAGE_ID, entity.getId(), app.getId(), ELDER_USER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(applicationRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------
    // 17. 応募審査 — status=PENDING を指定すると VILLAGE_068
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("17. 応募審査 — status=PENDING/WITHDRAWN は VILLAGE_068")
    void review_invalidStatus() {
        // PENDING を指定（ACCEPTED/REJECTED 以外）
        MatchApplicationReviewRequest req = new MatchApplicationReviewRequest(
                VillageMatchApplicationStatus.PENDING, "コメント");

        assertThatThrownBy(() -> service.reviewApplication(
                VILLAGE_ID, UUID.randomUUID(), UUID.randomUUID(), req, ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.MATCH_APPLICATION_INVALID_STATUS);

        // WITHDRAWN も同様
        MatchApplicationReviewRequest req2 = new MatchApplicationReviewRequest(
                VillageMatchApplicationStatus.WITHDRAWN, null);
        assertThatThrownBy(() -> service.reviewApplication(
                VILLAGE_ID, UUID.randomUUID(), UUID.randomUUID(), req2, ACTOR))
                .isInstanceOf(BusinessException.class);

        verify(applicationRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------
    // 18. 応募審査 — ACCEPTED 成功（投稿者本人による）
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("18. 応募審査 — ACCEPTED に遷移成功（投稿者本人レビュー）")
    void review_acceptedByAuthor() {
        VillageMatchRecruitEntity entity = openRecruit(ACTOR);
        VillageMatchRecruitApplicationEntity app = pendingApplication(entity.getId(), OTHER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(recruitRepository.findById(entity.getId())).willReturn(Optional.of(entity));
        // 投稿者本人であっても「現役メンバーであること」が前提（#2284 §12）。
        // BAN / 退村した投稿者は自分の募集でもレビュー不可のため、現役メンバーシップを stub する。
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(villagerMembership(ACTOR)));
        given(applicationRepository.findById(app.getId())).willReturn(Optional.of(app));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        MatchApplicationResponse res = service.reviewApplication(
                VILLAGE_ID, entity.getId(), app.getId(),
                new MatchApplicationReviewRequest(VillageMatchApplicationStatus.ACCEPTED, "歓迎"),
                ACTOR);

        assertThat(res.status()).isEqualTo(VillageMatchApplicationStatus.ACCEPTED);
        assertThat(res.reviewedByUserId()).isEqualTo(ACTOR);
        assertThat(res.reviewComment()).isEqualTo("歓迎");
    }

    // ------------------------------------------------------------------------
    // 19. 一覧 — 非村人は VILLAGE_007（認可 Wave3・村ロットA・真の穴の修正）
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("19. 一覧 — 非村人は VILLAGE_007（村人限定閲覧の欠落を修正）")
    void list_notMember() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.listRecruits(
                VILLAGE_ID, null, null, null, null, 0, 20, ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.NOT_MEMBER);

        verify(recruitRepository, never()).findByVillageIdAndDeletedAtIsNull(any(), any());
    }

    // ------------------------------------------------------------------------
    // 20. 一覧 — 村人なら閲覧成功
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("20. 一覧 — 村人なら一覧を取得できる")
    void list_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ACTOR)))
                .willReturn(Optional.of(villagerMembership(ACTOR)));
        given(recruitRepository.findByVillageIdAndDeletedAtIsNull(eq(VILLAGE_ID), any()))
                .willReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        var res = service.listRecruits(VILLAGE_ID, null, null, null, null, 0, 20, ACTOR);

        assertThat(res.items()).isEmpty();
    }
}
