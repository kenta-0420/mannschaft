package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageRecruitCategoryCreateRequest;
import com.mannschaft.app.village.dto.VillageRecruitCategoryOrderRequest;
import com.mannschaft.app.village.dto.VillageRecruitCategoryResponse;
import com.mannschaft.app.village.dto.VillageRecruitCategoryUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageRecruitCategoryEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMatchRecruitRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRecruitCategoryRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F17.1 P2 — VillageRecruitCategoryService 単体テスト。
 *
 * <p>設計書 {@code docs/features/F17.1_village_headman_console_and_recruit_categories.md}
 * §9.1（受け入れ条件 AC-01〜17）に対応する。認可は 7 ケース（HEADMAN / ELDER /
 * BAN された ELDER / VILLAGER / VISITOR / 非村人 / 退村済み元 ELDER）を網羅する
 * （memory {@code project_authz_idor_audit_campaign}）。</p>
 *
 * <p>金型: {@code VillageJoinRequestServiceTest} / {@code VillageReportServiceTest}。
 * {@code membershipRepository.findActiveByVillageIdAndSubject} は default メソッドのため、
 * Mockito は明示的にスタブしない限り {@code Optional.empty()} を返す（実 delegate 先の
 * derived query には委譲されない）。BAN 済み / 退村済みのケースは、実 DB では
 * {@code findActiveByVillageIdAndSubject} 自体が空を返す（#2284 §12 の述語）ため、本テストでは
 * その状態を {@code Optional.empty()} スタブで模擬する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F17.1 VillageRecruitCategoryService 単体テスト")
class VillageRecruitCategoryServiceTest {

    @Mock
    private VillageRepository villageRepository;
    /** 存在確認・可視性判定の共通ゲート（実物へ委譲。VillageAccessGateTestSupport 参照）。 */
    @Mock
    private VillageAccessGate accessGate;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private VillageRecruitCategoryRepository categoryRepository;
    @Mock
    private VillageMatchRecruitRepository recruitRepository;


    @org.junit.jupiter.api.BeforeEach
    void wireAccessGate() {
        // 存在確認・可視性判定は VillageAccessGate へ一元化された。ゲートのモックをそのまま使うと
        // 既存試練の villageRepository stub が読まれなくなるため、実物ゲートへ委譲させる。
        VillageAccessGateTestSupport.delegateToRealGate(
                accessGate, villageRepository, membershipRepository);
    }

    @InjectMocks
    private VillageRecruitCategoryService service;

    private static final UUID VILLAGE_ID = UUID.randomUUID();
    private static final UUID OTHER_VILLAGE_ID = UUID.randomUUID();
    private static final UUID CATEGORY_ID = UUID.randomUUID();
    private static final Long HEADMAN_USER_ID = 100L;
    private static final Long ELDER_USER_ID = 200L;
    private static final Long VILLAGER_USER_ID = 300L;

    private VillageEntity activeVillage() {
        return VillageEntity.builder()
                .slug("recruit-cat-village")
                .name("募集カテゴリ検証村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(3L)
                .createdByUserId(HEADMAN_USER_ID)
                .build();
    }

    private VillageEntity archivedVillage() {
        VillageEntity v = activeVillage();
        v.setArchivedAt(LocalDateTime.now().minusDays(1));
        return v;
    }

    private VillageMembershipEntity membership(VillageRole role, Long userId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        return m;
    }

    private VillageRecruitCategoryEntity category(UUID villageId, String name, boolean isPreset) {
        VillageRecruitCategoryEntity c = VillageRecruitCategoryEntity.builder()
                .villageId(villageId)
                .name(name)
                .displayOrder(10)
                .isPreset(isPreset)
                .presetKey(isPreset ? "PARTICIPANT" : null)
                .createdBy(HEADMAN_USER_ID)
                .build();
        ReflectionTestUtils.setField(c, "id", CATEGORY_ID);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }

    private VillageRecruitCategoryCreateRequest createRequest(String name) {
        return new VillageRecruitCategoryCreateRequest(name, null, null, null);
    }

    // ========================================================================
    // AC-04 / AC-05 — 一覧（村人であること）
    // ========================================================================

    @Test
    @DisplayName("AC-04: 村人は一覧を display_order 昇順で取得できる")
    void list_byVillager_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, VILLAGER_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.VILLAGER, VILLAGER_USER_ID)));
        VillageRecruitCategoryEntity cat = category(VILLAGE_ID, "参加者募集", true);
        given(categoryRepository.findByVillageIdAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtAsc(VILLAGE_ID))
                .willReturn(List.of(cat));
        given(recruitRepository.countActiveGroupedByCategory(VILLAGE_ID))
                .willReturn(List.<Object[]>of(new Object[]{CATEGORY_ID, 3L}));

        List<VillageRecruitCategoryResponse> result = service.list(VILLAGE_ID, VILLAGER_USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("参加者募集");
        assertThat(result.get(0).recruitCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("AC-05: 非村人が一覧を取得すると VILLAGE_007（404）で拒否される")
    void list_byNonMember_forbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, VILLAGER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(VILLAGE_ID, VILLAGER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    @Test
    @DisplayName("AC-15: 凍結村でも一覧は 200 で取得できる（読み取りは許可）")
    void list_archivedVillage_stillAllowed() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(archivedVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, VILLAGER_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.VILLAGER, VILLAGER_USER_ID)));
        given(categoryRepository.findByVillageIdAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtAsc(VILLAGE_ID))
                .willReturn(List.of());
        given(recruitRepository.countActiveGroupedByCategory(VILLAGE_ID)).willReturn(List.of());

        List<VillageRecruitCategoryResponse> result = service.list(VILLAGE_ID, VILLAGER_USER_ID);

        assertThat(result).isEmpty();
    }

    // ========================================================================
    // AC-01/02/02b/03/03b — 作成の認可（7ケース網羅）
    // ========================================================================

    @Test
    @DisplayName("AC-01: HEADMAN が作成すると成功する")
    void create_byHeadman_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.HEADMAN, HEADMAN_USER_ID)));
        given(categoryRepository.existsActiveByVillageIdAndName(VILLAGE_ID, "引っ越し手伝い")).willReturn(false);
        given(categoryRepository.countByVillageIdAndDeletedAtIsNull(VILLAGE_ID)).willReturn(0L);
        given(categoryRepository.save(any(VillageRecruitCategoryEntity.class))).willAnswer(inv -> {
            VillageRecruitCategoryEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            return e;
        });

        VillageRecruitCategoryResponse res = service.create(
                VILLAGE_ID, HEADMAN_USER_ID, createRequest("引っ越し手伝い"));

        assertThat(res.name()).isEqualTo("引っ越し手伝い");
        assertThat(res.villageId()).isEqualTo(VILLAGE_ID);
        verify(categoryRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("AC-02 🔷: ELDER が作成すると 201 相当（成功）— 御裁可により反転")
    void create_byElder_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, ELDER_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.ELDER, ELDER_USER_ID)));
        given(categoryRepository.existsActiveByVillageIdAndName(VILLAGE_ID, "引っ越し手伝い")).willReturn(false);
        given(categoryRepository.countByVillageIdAndDeletedAtIsNull(VILLAGE_ID)).willReturn(0L);
        given(categoryRepository.save(any(VillageRecruitCategoryEntity.class))).willAnswer(inv -> {
            VillageRecruitCategoryEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            return e;
        });

        VillageRecruitCategoryResponse res = service.create(
                VILLAGE_ID, ELDER_USER_ID, createRequest("引っ越し手伝い"));

        assertThat(res.name()).isEqualTo("引っ越し手伝い");
    }

    @Test
    @DisplayName("AC-02b 🔷: BAN された ELDER の作成は 403 で拒否される（必須要件・格上げ）")
    void create_byBannedElder_forbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        // BAN 済みは findActiveByVillageIdAndSubject が空を返す（#2284 §12 の述語）
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, ELDER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(VILLAGE_ID, ELDER_USER_ID, createRequest("引っ越し手伝い")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);

        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-03: VILLAGER が作成すると VILLAGE_024 で拒否される")
    void create_byVillager_forbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, VILLAGER_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.VILLAGER, VILLAGER_USER_ID)));

        assertThatThrownBy(() -> service.create(VILLAGE_ID, VILLAGER_USER_ID, createRequest("引っ越し手伝い")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    @Test
    @DisplayName("AC-03: 非村人が作成すると VILLAGE_024 で拒否される")
    void create_byNonMember_forbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, 999L))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(VILLAGE_ID, 999L, createRequest("引っ越し手伝い")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    @Test
    @DisplayName("AC-03b 🔷: 退村済みの元 ELDER の作成は 403 で拒否される")
    void create_byLeftElder_forbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        // 退村済みは findActiveByVillageIdAndSubject が空を返す（leftAt IS NULL の絞り込み）
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, ELDER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(VILLAGE_ID, ELDER_USER_ID, createRequest("引っ越し手伝い")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ========================================================================
    // AC-06〜09 — 重複・上限
    // ========================================================================

    @Test
    @DisplayName("AC-06: 同一村で既存と同名のカテゴリ作成は VILLAGE_084（409）")
    void create_duplicateName_conflict() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.HEADMAN, HEADMAN_USER_ID)));
        given(categoryRepository.existsActiveByVillageIdAndName(VILLAGE_ID, "その他")).willReturn(true);

        assertThatThrownBy(() -> service.create(VILLAGE_ID, HEADMAN_USER_ID, createRequest("その他")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.RECRUIT_CATEGORY_NAME_DUPLICATED);

        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-07: 別の村では同名のカテゴリを作成できる（一意性は村内に閉じる）")
    void create_sameNameInDifferentVillage_success() {
        given(villageRepository.findById(OTHER_VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                OTHER_VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.HEADMAN, HEADMAN_USER_ID)));
        // 別村では重複判定が false（村内に閉じているため existsActiveByVillageIdAndName(OTHER_VILLAGE_ID, ...) をスタブ）
        given(categoryRepository.existsActiveByVillageIdAndName(OTHER_VILLAGE_ID, "その他")).willReturn(false);
        given(categoryRepository.countByVillageIdAndDeletedAtIsNull(OTHER_VILLAGE_ID)).willReturn(0L);
        given(categoryRepository.save(any(VillageRecruitCategoryEntity.class))).willAnswer(inv -> {
            VillageRecruitCategoryEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            return e;
        });

        VillageRecruitCategoryResponse res = service.create(OTHER_VILLAGE_ID, HEADMAN_USER_ID, createRequest("その他"));

        assertThat(res.name()).isEqualTo("その他");
    }

    @Test
    @DisplayName("AC-08: 論理削除済みカテゴリと同名は作成できる（existsActive は deleted_at IS NULL 限定）")
    void create_sameNameAsSoftDeleted_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.HEADMAN, HEADMAN_USER_ID)));
        // existsActiveByVillageIdAndName は実装上 deleted_at IS NULL のみを見るため、
        // 論理削除済みの同名行があっても false を返す前提（Repository JPQL の契約）。
        given(categoryRepository.existsActiveByVillageIdAndName(VILLAGE_ID, "審判")).willReturn(false);
        given(categoryRepository.countByVillageIdAndDeletedAtIsNull(VILLAGE_ID)).willReturn(0L);
        given(categoryRepository.save(any(VillageRecruitCategoryEntity.class))).willAnswer(inv -> {
            VillageRecruitCategoryEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            return e;
        });

        VillageRecruitCategoryResponse res = service.create(VILLAGE_ID, HEADMAN_USER_ID, createRequest("審判"));

        assertThat(res.name()).isEqualTo("審判");
    }

    @Test
    @DisplayName("AC-09: 21件目のカテゴリ作成は VILLAGE_085（422）")
    void create_limitExceeded_rejected() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.HEADMAN, HEADMAN_USER_ID)));
        given(categoryRepository.existsActiveByVillageIdAndName(VILLAGE_ID, "21件目")).willReturn(false);
        given(categoryRepository.countByVillageIdAndDeletedAtIsNull(VILLAGE_ID)).willReturn(20L);

        assertThatThrownBy(() -> service.create(VILLAGE_ID, HEADMAN_USER_ID, createRequest("21件目")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.RECRUIT_CATEGORY_LIMIT_EXCEEDED);

        verify(categoryRepository, never()).save(any());
    }

    // ========================================================================
    // AC-15 — 凍結村での書き込み拒否
    // ========================================================================

    @Test
    @DisplayName("AC-15: 凍結村でのカテゴリ作成は VILLAGE_027（409）")
    void create_archivedVillage_conflict() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(archivedVillage()));

        assertThatThrownBy(() -> service.create(VILLAGE_ID, HEADMAN_USER_ID, createRequest("その他")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);

        verify(categoryRepository, never()).save(any());
    }

    // ========================================================================
    // AC-12 — IDOR（他村のカテゴリを操作できない）
    // ========================================================================

    @Test
    @DisplayName("AC-12: 村Aのカテゴリを村Bのpathで更新すると VILLAGE_083（404・IDOR秘匿）")
    void update_crossVillageIdor_notFound() {
        given(villageRepository.findById(OTHER_VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                OTHER_VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.HEADMAN, HEADMAN_USER_ID)));
        // カテゴリ自体は VILLAGE_ID 所属だが、path は OTHER_VILLAGE_ID
        VillageRecruitCategoryEntity foreignCategory = category(VILLAGE_ID, "その他", false);
        given(categoryRepository.findByIdAndDeletedAtIsNull(CATEGORY_ID)).willReturn(Optional.of(foreignCategory));

        VillageRecruitCategoryUpdateRequest req = new VillageRecruitCategoryUpdateRequest("改名", null, null, null);

        assertThatThrownBy(() -> service.update(OTHER_VILLAGE_ID, CATEGORY_ID, HEADMAN_USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.RECRUIT_CATEGORY_NOT_FOUND);

        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-12: 存在しないカテゴリIDへの削除は VILLAGE_083（404）")
    void delete_notFound() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.HEADMAN, HEADMAN_USER_ID)));
        given(categoryRepository.findByIdAndDeletedAtIsNull(CATEGORY_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(VILLAGE_ID, CATEGORY_ID, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.RECRUIT_CATEGORY_NOT_FOUND);
    }

    // ========================================================================
    // AC-10/11 — 削除ガード
    // ========================================================================

    @Test
    @DisplayName("AC-10: 使用中カテゴリの削除は VILLAGE_086（409）、論理削除されない")
    void delete_inUse_conflict() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.HEADMAN, HEADMAN_USER_ID)));
        VillageRecruitCategoryEntity cat = category(VILLAGE_ID, "その他", false);
        given(categoryRepository.findByIdAndDeletedAtIsNull(CATEGORY_ID)).willReturn(Optional.of(cat));
        given(recruitRepository.countByVillageIdAndCategoryIdAndDeletedAtIsNull(VILLAGE_ID, CATEGORY_ID))
                .willReturn(2L);

        assertThatThrownBy(() -> service.delete(VILLAGE_ID, CATEGORY_ID, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.RECRUIT_CATEGORY_IN_USE);

        assertThat(cat.getDeletedAt()).isNull();
        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-11: 参照ゼロのカテゴリ削除は成功し deleted_at が入る（物理削除ではない）")
    void delete_zeroUsage_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.HEADMAN, HEADMAN_USER_ID)));
        VillageRecruitCategoryEntity cat = category(VILLAGE_ID, "その他", false);
        given(categoryRepository.findByIdAndDeletedAtIsNull(CATEGORY_ID)).willReturn(Optional.of(cat));
        given(recruitRepository.countByVillageIdAndCategoryIdAndDeletedAtIsNull(VILLAGE_ID, CATEGORY_ID))
                .willReturn(0L);
        given(categoryRepository.save(any(VillageRecruitCategoryEntity.class))).willAnswer(inv -> inv.getArgument(0));

        service.delete(VILLAGE_ID, CATEGORY_ID, HEADMAN_USER_ID);

        assertThat(cat.getDeletedAt()).isNotNull();
        verify(categoryRepository, times(1)).save(cat);
    }

    // ========================================================================
    // AC-13 — プリセットも可変（不変ではない）
    // ========================================================================

    @Test
    @DisplayName("AC-13: is_preset=TRUE のカテゴリも改名・削除できる（不変ではない）")
    void update_presetCategory_mutable() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, ELDER_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.ELDER, ELDER_USER_ID)));
        VillageRecruitCategoryEntity preset = category(VILLAGE_ID, "参加者募集", true);
        given(categoryRepository.findByIdAndDeletedAtIsNull(CATEGORY_ID)).willReturn(Optional.of(preset));
        given(categoryRepository.existsActiveByVillageIdAndNameExcludingId(
                VILLAGE_ID, "お手伝い募集", CATEGORY_ID)).willReturn(false);
        given(categoryRepository.save(any(VillageRecruitCategoryEntity.class))).willAnswer(inv -> inv.getArgument(0));
        given(recruitRepository.countByVillageIdAndCategoryIdAndDeletedAtIsNull(VILLAGE_ID, CATEGORY_ID))
                .willReturn(0L);

        VillageRecruitCategoryUpdateRequest req =
                new VillageRecruitCategoryUpdateRequest("お手伝い募集", null, null, null);
        VillageRecruitCategoryResponse res = service.update(VILLAGE_ID, CATEGORY_ID, ELDER_USER_ID, req);

        assertThat(res.name()).isEqualTo("お手伝い募集");
        assertThat(res.isPreset()).isTrue();
    }

    // ========================================================================
    // AC-14 — 並び替え
    // ========================================================================

    @Test
    @DisplayName("AC-14: 並び替えで display_order が指定順（10刻み）に更新される")
    void reorder_updatesDisplayOrder() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        VillageRecruitCategoryEntity cat1 = category(VILLAGE_ID, "A", false);
        ReflectionTestUtils.setField(cat1, "id", id1);
        VillageRecruitCategoryEntity cat2 = category(VILLAGE_ID, "B", false);
        ReflectionTestUtils.setField(cat2, "id", id2);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(membership(VillageRole.HEADMAN, HEADMAN_USER_ID)));
        given(categoryRepository.findByIdAndDeletedAtIsNull(id2)).willReturn(Optional.of(cat2));
        given(categoryRepository.findByIdAndDeletedAtIsNull(id1)).willReturn(Optional.of(cat1));
        given(categoryRepository.save(any(VillageRecruitCategoryEntity.class))).willAnswer(inv -> inv.getArgument(0));
        given(recruitRepository.countActiveGroupedByCategory(VILLAGE_ID)).willReturn(List.of());

        VillageRecruitCategoryOrderRequest req = new VillageRecruitCategoryOrderRequest(List.of(id2, id1));
        List<VillageRecruitCategoryResponse> result = service.reorder(VILLAGE_ID, HEADMAN_USER_ID, req);

        assertThat(cat2.getDisplayOrder()).isEqualTo(10);
        assertThat(cat1.getDisplayOrder()).isEqualTo(20);
        assertThat(result).hasSize(2);
    }
}
