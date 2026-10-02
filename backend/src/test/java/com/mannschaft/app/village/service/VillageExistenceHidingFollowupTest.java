package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.PinListResponse;
import com.mannschaft.app.village.dto.VillageFeedResponse;
import com.mannschaft.app.village.entity.UserVillagePinEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageMatchRecruitRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRecruitCategoryRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
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
 * 番人 {@code VillageExistenceCheckCentralizationGuardTest} が暴いた
 * <b>残存 10 箇所</b>のうち、{@link VillageAccessGate} へ寄せた経路の結線を検証する試練。
 *
 * <p>先行の {@link VillageServiceExistenceHidingTest} は「15 サービス結線」の代表 4 つを見ている。
 * 本試練はその走査が取りこぼしていた経路
 * （{@code private loadActiveVillage} ヘルパを持たず、インラインで {@code villageRepository} を
 * 引いていたもの）を対象とする。</p>
 *
 * <p>検証の軸は先行試練と同じで、HTTP ステータスではなく {@link VillageErrorCode} そのものを
 * 「実在しない村 ID を同じ入口へ投げたときの応答」と突き合わせる。ステータスが 404 で揃っていても
 * {@code error.code} が割れれば応答本文だけで村の実在が判別できてしまうためである。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("村サービス群 — 非公開村の存在秘匿（番人が暴いた残存経路の結線）")
class VillageExistenceHidingFollowupTest {

    private static final UUID VILLAGE_ID = UUID.fromString("019200bb-0000-7000-8000-000000000001");
    /** 実在しない村 ID。UNLISTED 村への応答がこれと一致することが本試練の本体。 */
    private static final UUID MISSING_VILLAGE_ID = UUID.fromString("019200bb-0000-7000-8000-0000000000ff");

    private static final Long STRANGER_ID = 8001L;
    private static final Long MEMBER_ID = 8002L;

    // --- 共通 ---
    @Mock private VillageRepository villageRepository;
    @Mock private VillageMembershipRepository membershipRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private VillageAccessGate accessGate;
    @Mock private AuditLogService auditLogService;
    @Mock private MediaUrlResolver mediaUrlResolver;

    // --- 募集カテゴリ ---
    @Mock private VillageRecruitCategoryRepository categoryRepository;
    @Mock private VillageMatchRecruitRepository recruitRepository;

    // --- フィード / ピン ---
    @Mock private UserVillagePinRepository pinRepository;
    @Mock private TimelinePostRepository timelinePostRepository;
    @Mock private BulletinThreadRepository bulletinThreadRepository;
    @Mock private ChatChannelRepository chatChannelRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private PostingIdentityService postingIdentityService;

    @InjectMocks private VillageRecruitCategoryService recruitCategoryService;
    @InjectMocks private VillageMembershipProfileService membershipProfileService;
    @InjectMocks private VillageAffinityService affinityService;
    @InjectMocks private VillageFeedService feedService;
    @InjectMocks private VillagePinService pinService;

    @BeforeEach
    void wireGate() {
        VillageAccessGateTestSupport.delegateToRealGate(
                accessGate, villageRepository, membershipRepository, accessControlService);
        lenient().when(villageRepository.findById(MISSING_VILLAGE_ID)).thenReturn(Optional.empty());
        lenient().when(membershipRepository.findActiveByVillageIdAndSubject(any(), any(), anyLong()))
                .thenReturn(Optional.empty());
        lenient().when(membershipRepository.findActiveUserMemberships(anyLong())).thenReturn(List.of());
        lenient().when(accessControlService.isSystemAdmin(anyLong())).thenReturn(false);
    }

    // ==================================================================
    // フィクスチャ
    // ==================================================================

    private VillageEntity village(UUID id, VillageVisibility visibility) {
        VillageEntity v = VillageEntity.builder()
                .slug("followup-village")
                .name("追撃検証村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(visibility)
                .memberCountCache(3L)
                .build();
        v.setId(id);
        return v;
    }

    private void givenVillage(VillageVisibility visibility) {
        VillageEntity v = village(VILLAGE_ID, visibility);
        lenient().when(villageRepository.findById(VILLAGE_ID)).thenReturn(Optional.of(v));
        // ピン一覧・フィードは村を findAllById で一括取得する（N+1 回避）。
        // 単一取得だけを stub すると、その経路が空を返して試練が意味を失う。
        lenient().when(villageRepository.findAllById(any(Iterable.class))).thenReturn(List.of(v));
    }

    private void givenActiveMember(Long userId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now().minusDays(30))
                .build();
        m.setId(UUID.randomUUID());
        lenient().when(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(userId))).thenReturn(Optional.of(m));
        // 一括版 filterVisible は操作者軸の findActiveUserMemberships を引く。
        // 単一版だけを stub すると一括経路では非メンバー扱いになり、
        // 「現役村人には見える」試練が理由の違うところで落ちる。
        lenient().when(membershipRepository.findActiveUserMemberships(eq(userId)))
                .thenReturn(List.of(m));
    }

    private VillageErrorCode codeOf(Throwable t) {
        assertThat(t).as("BusinessException が投げられること").isInstanceOf(BusinessException.class);
        return (VillageErrorCode) ((BusinessException) t).getErrorCode();
    }

    // ==================================================================
    // AC-4: UNLISTED 村の非村人 = 不在の村 ID と ErrorCode が完全一致
    // ==================================================================

    @Nested
    @DisplayName("UNLISTED 村の非村人は『不在の村 ID』と同一の ErrorCode を受け取る")
    class UnlistedStrangerMatchesMissing {

        @Test
        @DisplayName("募集カテゴリ一覧: UNLISTED 非村人 == 不在村 ID == VILLAGE_NOT_FOUND")
        void recruitCategoryList() {
            givenVillage(VillageVisibility.UNLISTED);

            VillageErrorCode unlisted = codeOf(catchThrowable(
                    () -> recruitCategoryService.list(VILLAGE_ID, STRANGER_ID)));
            VillageErrorCode missing = codeOf(catchThrowable(
                    () -> recruitCategoryService.list(MISSING_VILLAGE_ID, STRANGER_ID)));

            assertThat(unlisted).isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
            assertThat(unlisted)
                    .as("ゲート導入前は NOT_MEMBER(VILLAGE_007) が返り、不在の VILLAGE_001 と割れていた")
                    .isEqualTo(missing);
        }

        @Test
        @DisplayName("所属公開トグル: UNLISTED 非村人 == 不在村 ID == VILLAGE_NOT_FOUND")
        void profileVisibilityToggle() {
            givenVillage(VillageVisibility.UNLISTED);

            VillageErrorCode unlisted = codeOf(catchThrowable(
                    () -> membershipProfileService.updateMyProfileVisibility(VILLAGE_ID, STRANGER_ID, true)));
            VillageErrorCode missing = codeOf(catchThrowable(
                    () -> membershipProfileService.updateMyProfileVisibility(
                            MISSING_VILLAGE_ID, STRANGER_ID, true)));

            assertThat(unlisted).isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
            assertThat(unlisted).isEqualTo(missing);
        }

        @Test
        @DisplayName("加入前相性: UNLISTED 非村人 == 不在村 ID == VILLAGE_NOT_FOUND")
        void affinity() {
            givenVillage(VillageVisibility.UNLISTED);

            VillageErrorCode unlisted = codeOf(catchThrowable(
                    () -> affinityService.getAffinity(VILLAGE_ID, STRANGER_ID)));
            VillageErrorCode missing = codeOf(catchThrowable(
                    () -> affinityService.getAffinity(MISSING_VILLAGE_ID, STRANGER_ID)));

            assertThat(unlisted).isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
            assertThat(unlisted).isEqualTo(missing);
        }
    }

    // ==================================================================
    // AC-2: PUBLIC 村の非村人は 404 に倒れない
    // ==================================================================

    @Nested
    @DisplayName("PUBLIC 村の非村人は従来どおりの『非メンバー』コードのまま（404 に倒れない）")
    class PublicStrangerKeepsForbidden {

        @Test
        @DisplayName("募集カテゴリ一覧: PUBLIC 非村人は NOT_MEMBER（VILLAGE_007）のまま")
        void recruitCategoryList() {
            givenVillage(VillageVisibility.PUBLIC);

            assertThat(codeOf(catchThrowable(
                    () -> recruitCategoryService.list(VILLAGE_ID, STRANGER_ID))))
                    .as("公開村は存在が秘密ではない。404 に倒すと隠すものが無いのに正しい案内を奪う")
                    .isEqualTo(VillageErrorCode.NOT_MEMBER);
        }

        @Test
        @DisplayName("所属公開トグル: PUBLIC 非村人は NOT_MEMBER（VILLAGE_007）のまま")
        void profileVisibilityToggle() {
            givenVillage(VillageVisibility.PUBLIC);

            assertThat(codeOf(catchThrowable(
                    () -> membershipProfileService.updateMyProfileVisibility(VILLAGE_ID, STRANGER_ID, true))))
                    .isEqualTo(VillageErrorCode.NOT_MEMBER);
        }

        @Test
        @DisplayName("加入前相性: PUBLIC 村は非村人でも従来どおり取得できる")
        void affinity() {
            givenVillage(VillageVisibility.PUBLIC);

            assertThatCode(() -> affinityService.getAffinity(VILLAGE_ID, STRANGER_ID))
                    .doesNotThrowAnyException();
        }
    }

    // ==================================================================
    // 既存の秘匿設計を壊していないこと
    // ==================================================================

    @Nested
    @DisplayName("ゲート導入で既存の秘匿・可視設計を壊していない")
    class ExistingContractsPreserved {

        @Test
        @DisplayName("募集カテゴリ: UNLISTED の現役村人は従来どおり一覧を取得できる")
        void unlistedMemberCanList() {
            givenVillage(VillageVisibility.UNLISTED);
            givenActiveMember(MEMBER_ID);

            assertThatCode(() -> recruitCategoryService.list(VILLAGE_ID, MEMBER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("募集カテゴリ: 凍結済み PUBLIC 村でも一覧の閲覧は許す（§6.4 を壊さない）")
        void archivedVillageStillReadable() {
            VillageEntity archived = village(VILLAGE_ID, VillageVisibility.PUBLIC);
            archived.setArchivedAt(LocalDateTime.now().minusDays(1));
            lenient().when(villageRepository.findById(VILLAGE_ID)).thenReturn(Optional.of(archived));
            givenActiveMember(MEMBER_ID);

            assertThatCode(() -> recruitCategoryService.list(VILLAGE_ID, MEMBER_ID))
                    .as("凍結を 409/404 に倒すゲート入口を機械的に当てると、凍結村の一覧が読めなくなる退行になる")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("加入前相性: UNLISTED 村は現役村人であっても 404（§8.7 の PUBLIC 限定契約を維持）")
        void affinityStaysPublicOnly() {
            givenVillage(VillageVisibility.UNLISTED);
            givenActiveMember(MEMBER_ID);

            assertThat(codeOf(catchThrowable(
                    () -> affinityService.getAffinity(VILLAGE_ID, MEMBER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
    }

    // ==================================================================
    // ピン経由の存在漏洩（VillageFeedService）
    // ==================================================================

    @Nested
    @DisplayName("フィードのピン村サマリーは閲覧者に可視な村だけを載せる")
    class FeedPinnedVillageVisibility {

        private UserVillagePinEntity pin(UUID villageId) {
            UserVillagePinEntity p = UserVillagePinEntity.builder()
                    .userId(STRANGER_ID)
                    .villageId(villageId)
                    .sortOrder(0L)
                    .pinnedAt(LocalDateTime.now())
                    .build();
            p.setId(UUID.randomUUID());
            return p;
        }

        @Test
        @DisplayName("退村・BAN 後も残るピンの UNLISTED 村はサマリーに出さない（村名・村紋の漏洩を塞ぐ）")
        void unlistedPinOfFormerMemberIsHidden() {
            givenVillage(VillageVisibility.UNLISTED);
            lenient().when(pinRepository.findByUserIdOrderBySortOrderAsc(STRANGER_ID))
                    .thenReturn(List.of(pin(VILLAGE_ID)));

            VillageFeedResponse res = feedService.build(STRANGER_ID, 20);

            assertThat(res.pinnedVillages())
                    .as("ピンは退村・BAN で削除されないため、可視性を見ないと非公開村の名前が残り続ける")
                    .isEmpty();
        }

        @Test
        @DisplayName("UNLISTED 村でも現役村人にはサマリーに出る")
        void unlistedPinOfActiveMemberIsShown() {
            givenVillage(VillageVisibility.UNLISTED);
            givenActiveMember(STRANGER_ID);
            lenient().when(pinRepository.findByUserIdOrderBySortOrderAsc(STRANGER_ID))
                    .thenReturn(List.of(pin(VILLAGE_ID)));

            VillageFeedResponse res = feedService.build(STRANGER_ID, 20);

            assertThat(res.pinnedVillages()).hasSize(1);
        }

        @Test
        @DisplayName("PUBLIC 村のピンは非村人でも従来どおりサマリーに出る")
        void publicPinIsShown() {
            givenVillage(VillageVisibility.PUBLIC);
            lenient().when(pinRepository.findByUserIdOrderBySortOrderAsc(STRANGER_ID))
                    .thenReturn(List.of(pin(VILLAGE_ID)));

            VillageFeedResponse res = feedService.build(STRANGER_ID, 20);

            assertThat(res.pinnedVillages()).hasSize(1);
        }

        // ------------------------------------------------------------------
        // ピン一覧 API 本体（VillagePinService#listMyPins）も同じ経路を持つ。
        // こちらは findAllById で引くため番人の走査対象メソッドに掛からず、
        // 静的走査では見つからない。試練で押さえる。
        // ------------------------------------------------------------------

        @Test
        @DisplayName("ピン一覧: 退村・BAN 後の UNLISTED 村は村名・アイコンを返さない")
        void pinListHidesUnlistedForFormerMember() {
            lenient().when(pinRepository.findByUserIdOrderBySortOrderAsc(STRANGER_ID))
                    .thenReturn(List.of(pin(VILLAGE_ID)));
            lenient().when(villageRepository.findAllById(any(Iterable.class)))
                    .thenReturn(List.of(village(VILLAGE_ID, VillageVisibility.UNLISTED)));

            PinListResponse res = pinService.listMyPins(STRANGER_ID);

            assertThat(res.items()).hasSize(1);
            assertThat(res.items().get(0).villageName())
                    .as("findAllById は可視性も生存も見ないため、ゲートを通さないと非公開村の名前が残る")
                    .isNull();
        }

        @Test
        @DisplayName("ピン一覧: PUBLIC 村は従来どおり村名を返す")
        void pinListKeepsPublicName() {
            lenient().when(pinRepository.findByUserIdOrderBySortOrderAsc(STRANGER_ID))
                    .thenReturn(List.of(pin(VILLAGE_ID)));
            lenient().when(villageRepository.findAllById(any(Iterable.class)))
                    .thenReturn(List.of(village(VILLAGE_ID, VillageVisibility.PUBLIC)));

            PinListResponse res = pinService.listMyPins(STRANGER_ID);

            assertThat(res.items().get(0).villageName()).isEqualTo("追撃検証村");
        }
    }
}
