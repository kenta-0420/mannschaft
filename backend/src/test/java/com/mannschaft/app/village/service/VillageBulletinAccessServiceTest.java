package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageBulletinAccessService} の単体テスト（F17.1 村掲示板グローバル方式 閲覧認可）。
 *
 * <p>PUBLIC 村はログイン済なら誰でも閲覧可、MEMBERS_ONLY 村は村メンバー or SYSTEM_ADMIN のみ、
 * 村不在は 404 相当（VILLAGE_NOT_FOUND）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageBulletinAccessService 単体テスト")
class VillageBulletinAccessServiceTest {

    @Mock
    private VillageRepository villageRepository;

    @Mock
    private VillageMembershipRepository membershipRepository;

    @Mock
    private PostingIdentityService postingIdentityService;

    @Mock
    private AccessControlService accessControlService;

    /**
     * 村の可視性ゲートは<b>実物</b>を使う（モックにすると「UNLISTED 非村人が 404 になる」という
     * 本テストの主眼が、モックの stub をそのまま読み上げるだけの同語反復になってしまうため）。
     * ゲートは Repository だけに依存する葉ノードなので、同じ mock Repository から素直に組める。
     */
    private VillageAccessGate gate;

    private VillageBulletinAccessService service;

    @BeforeEach
    void setUp() {
        gate = new VillageAccessGate(villageRepository, membershipRepository, accessControlService);
        service = new VillageBulletinAccessService(
                villageRepository, membershipRepository, postingIdentityService, accessControlService);
    }

    private static final UUID VILLAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID MISSING_VILLAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final Long USER_ID = 10L;

    /** 既定は PUBLIC 村（＝村の存在自体は公開情報）。掲示板可視性のみを引数で振る。 */
    private VillageEntity village(VillageBulletinVisibility visibility) {
        return village(visibility, VillageVisibility.PUBLIC);
    }

    private VillageEntity village(VillageBulletinVisibility bulletinVisibility, VillageVisibility visibility) {
        VillageEntity v = VillageEntity.builder()
                .slug("test-village")
                .name("テスト村")
                .visibility(visibility)
                .bulletinVisibility(bulletinVisibility)
                .build();
        v.setId(VILLAGE_ID);
        return v;
    }

    /**
     * 村を存在させる。新旧どちらの取得経路（ゲートの {@code findById} / 旧実装の
     * {@code findByIdAndDeletedAtIsNullAndArchivedAtIsNull}）でも同じ村が返るようにし、
     * 既定では「誰も現役村人でない・誰も SYSTEM_ADMIN でない」状態にする。
     */
    private void givenVillage(VillageEntity v) {
        lenient().when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                .thenReturn(Optional.of(v));
        lenient().when(villageRepository.findById(VILLAGE_ID)).thenReturn(Optional.of(v));
        lenient().when(membershipRepository.findActiveByVillageIdAndSubject(any(), any(), anyLong()))
                .thenReturn(Optional.empty());
        lenient().when(accessControlService.isSystemAdmin(anyLong())).thenReturn(false);
    }

    private void givenVillageMissing(UUID id) {
        lenient().when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(id))
                .thenReturn(Optional.empty());
        lenient().when(villageRepository.findById(id)).thenReturn(Optional.empty());
    }

    private VillageErrorCode codeOf(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        return (VillageErrorCode) ((BusinessException) t).getErrorCode();
    }

    @Nested
    @DisplayName("PUBLIC 村")
    class PublicVillage {

        @Test
        @DisplayName("PUBLIC村_非メンバーでも閲覧可_例外なし")
        void public_非メンバーOK() {
            givenVillage(village(VillageBulletinVisibility.PUBLIC));

            assertThatCode(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID))
                    .doesNotThrowAnyException();

            // PUBLIC ではメンバー判定を行わない
            verify(postingIdentityService, never()).isUserVillageMember(VILLAGE_ID, USER_ID);
        }
    }

    @Nested
    @DisplayName("MEMBERS_ONLY 村")
    class MembersOnlyVillage {

        @Test
        @DisplayName("MEMBERS_ONLY村_村メンバー_閲覧可")
        void membersOnly_メンバーOK() {
            givenVillage(village(VillageBulletinVisibility.MEMBERS_ONLY));
            given(postingIdentityService.isUserVillageMember(VILLAGE_ID, USER_ID)).willReturn(true);

            assertThatCode(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MEMBERS_ONLY村_SYSTEM_ADMIN_閲覧可")
        void membersOnly_SYSADMIN_OK() {
            givenVillage(village(VillageBulletinVisibility.MEMBERS_ONLY));
            given(postingIdentityService.isUserVillageMember(VILLAGE_ID, USER_ID)).willReturn(false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MEMBERS_ONLY村_非メンバーかつ非ADMIN_403")
        void membersOnly_非メンバー_403() {
            givenVillage(village(VillageBulletinVisibility.MEMBERS_ONLY));
            given(postingIdentityService.isUserVillageMember(VILLAGE_ID, USER_ID)).willReturn(false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);

            assertThatThrownBy(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN));
        }

        @Test
        @DisplayName("bulletin_visibility が NULL_安全側でMEMBERS_ONLY扱い_非メンバー403")
        void membersOnly_NULLフォールバック_403() {
            givenVillage(village(null));
            lenient().when(postingIdentityService.isUserVillageMember(VILLAGE_ID, USER_ID)).thenReturn(false);
            lenient().when(accessControlService.isSystemAdmin(USER_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN));
        }
    }

    @Nested
    @DisplayName("村不在 / 不正引数")
    class NotFound {

        @Test
        @DisplayName("村が存在しない_VILLAGE_NOT_FOUND（404相当）")
        void 村なし_404() {
            givenVillageMissing(VILLAGE_ID);

            assertThatThrownBy(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND));
        }

        @Test
        @DisplayName("villageId が null_VILLAGE_NOT_FOUND（防御的）")
        void villageId_null_404() {
            assertThatThrownBy(() -> service.checkVillageBulletinViewAccess(null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND));
        }
    }

    /**
     * 村そのものの可視性ゲート（存在オラクルの遮断）。
     *
     * <p>掲示板可視性 {@code bulletinVisibility}（PUBLIC / MEMBERS_ONLY）とは<b>別軸</b>で、
     * 村自体が UNLISTED なら非村人には村の存在ごと秘匿する。逆に PUBLIC 村は検索で誰でも
     * 見つけられ存在が公開情報なので、403 のまま（404 に倒すと正しい案内を奪うだけの改悪）。</p>
     */
    @Nested
    @DisplayName("村可視性ゲート（UNLISTED 村の存在秘匿）")
    class VillageVisibilityGate {

        @Test
        @DisplayName("AC-1: UNLISTED 村の非村人の閲覧は、不在村 ID と ErrorCode が完全一致する")
        void ac1_view_unlistedNonMemberMatchesMissing() {
            givenVillageMissing(MISSING_VILLAGE_ID);
            VillageErrorCode missingCode =
                    codeOf(catchThrowable(() -> service.checkVillageBulletinViewAccess(MISSING_VILLAGE_ID, USER_ID)));

            givenVillage(village(VillageBulletinVisibility.MEMBERS_ONLY, VillageVisibility.UNLISTED));
            VillageErrorCode unlistedCode =
                    codeOf(catchThrowable(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID)));

            assertThat(unlistedCode)
                    .as("403 と 404 で割れると応答の差そのものが村の実在を漏らす")
                    .isEqualTo(missingCode)
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("AC-1: UNLISTED 村の非村人のモデレーションも、不在村 ID と ErrorCode が完全一致する")
        void ac1_moderate_unlistedNonMemberMatchesMissing() {
            givenVillageMissing(MISSING_VILLAGE_ID);
            VillageErrorCode missingCode =
                    codeOf(catchThrowable(() -> service.checkVillageBulletinModerator(MISSING_VILLAGE_ID, USER_ID)));

            givenVillage(village(VillageBulletinVisibility.MEMBERS_ONLY, VillageVisibility.UNLISTED));
            VillageErrorCode unlistedCode =
                    codeOf(catchThrowable(() -> service.checkVillageBulletinModerator(VILLAGE_ID, USER_ID)));

            assertThat(unlistedCode)
                    .isEqualTo(missingCode)
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("AC-2: PUBLIC 村の非村人の閲覧は従来どおり 403（404 に倒さない）")
        void ac2_view_publicVillageStays403() {
            givenVillage(village(VillageBulletinVisibility.MEMBERS_ONLY, VillageVisibility.PUBLIC));
            given(postingIdentityService.isUserVillageMember(VILLAGE_ID, USER_ID)).willReturn(false);

            assertThat(codeOf(catchThrowable(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN);
        }

        @Test
        @DisplayName("AC-2: PUBLIC 村の非モデレーターは従来どおり 403（404 に倒さない）")
        void ac2_moderate_publicVillageStays403() {
            givenVillage(village(VillageBulletinVisibility.PUBLIC, VillageVisibility.PUBLIC));
            given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                    VILLAGE_ID, VillageSubjectType.USER, USER_ID)).willReturn(Optional.empty());

            assertThat(codeOf(catchThrowable(() -> service.checkVillageBulletinModerator(VILLAGE_ID, USER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN);
        }

        @Test
        @DisplayName("AC-3: UNLISTED 村の現役村人は従来どおり閲覧できる")
        void ac3_view_unlistedActiveMemberOk() {
            givenVillage(village(VillageBulletinVisibility.MEMBERS_ONLY, VillageVisibility.UNLISTED));
            given(membershipRepository.findActiveByVillageIdAndSubject(
                    eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(USER_ID)))
                    .willReturn(Optional.of(new VillageMembershipEntity()));
            given(postingIdentityService.isUserVillageMember(VILLAGE_ID, USER_ID)).willReturn(true);

            assertThatCode(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-3: UNLISTED 村の SYSTEM_ADMIN は従来どおり閲覧できる")
        void ac3_view_unlistedSystemAdminOk() {
            givenVillage(village(VillageBulletinVisibility.MEMBERS_ONLY, VillageVisibility.UNLISTED));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            lenient().when(postingIdentityService.isUserVillageMember(VILLAGE_ID, USER_ID)).thenReturn(false);

            assertThatCode(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-3: UNLISTED 村の現役 HEADMAN は従来どおりモデレーションできる")
        void ac3_moderate_unlistedHeadmanOk() {
            givenVillage(village(VillageBulletinVisibility.MEMBERS_ONLY, VillageVisibility.UNLISTED));
            given(membershipRepository.findActiveByVillageIdAndSubject(
                    eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(USER_ID)))
                    .willReturn(Optional.of(new VillageMembershipEntity()));
            given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                    VILLAGE_ID, VillageSubjectType.USER, USER_ID))
                    .willReturn(Optional.of(VillageMembershipEntity.builder()
                            .villageId(VILLAGE_ID)
                            .subjectType(VillageSubjectType.USER)
                            .subjectId(USER_ID)
                            .role(VillageRole.HEADMAN)
                            .build()));

            assertThatCode(() -> service.checkVillageBulletinModerator(VILLAGE_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-4: UNLISTED 村でも bulletin_visibility が NULL なら村人以外は通さない（MEMBERS_ONLY 扱いを維持）")
        void ac4_nullBulletinVisibilityStaysMembersOnly() {
            givenVillage(village(null, VillageVisibility.UNLISTED));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(postingIdentityService.isUserVillageMember(VILLAGE_ID, USER_ID)).willReturn(false);

            // SYSTEM_ADMIN は村可視性ゲートも掲示板 MEMBERS_ONLY も通過する（PUBLIC 扱いに緩んでいない証拠は
            // 「非メンバー判定が実際に評価されている」ことで見る）。
            assertThatCode(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID))
                    .doesNotThrowAnyException();
            verify(postingIdentityService).isUserVillageMember(VILLAGE_ID, USER_ID);
        }
    }

    /**
     * requireHeadmanOrElder（②-4 堅牢性 AC-15/16）: ニュースレター編集の主体検証を集約した述語。
     * 現役 HEADMAN / ELDER のみ通過し、平メンバー・退村/BAN・非メンバーは MODERATION_FORBIDDEN。
     * 「現役」の判定は正準クエリ findActiveByVillageIdAndSubject（leftAt/bannedAt 除外）に委譲する。
     */
    @Nested
    @DisplayName("requireHeadmanOrElder（お便り編集認可・AC-16）")
    class RequireHeadmanOrElder {

        private VillageMembershipEntity membership(VillageRole role) {
            return VillageMembershipEntity.builder()
                    .villageId(VILLAGE_ID)
                    .subjectType(VillageSubjectType.USER)
                    .subjectId(USER_ID)
                    .role(role)
                    .build();
        }

        @Test
        @DisplayName("現役 HEADMAN は通過（例外なし）")
        void headman_ok() {
            given(membershipRepository.findActiveByVillageIdAndSubject(
                    VILLAGE_ID, VillageSubjectType.USER, USER_ID))
                    .willReturn(Optional.of(membership(VillageRole.HEADMAN)));

            assertThatCode(() -> service.requireHeadmanOrElder(VILLAGE_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("現役 ELDER は通過（例外なし）")
        void elder_ok() {
            given(membershipRepository.findActiveByVillageIdAndSubject(
                    VILLAGE_ID, VillageSubjectType.USER, USER_ID))
                    .willReturn(Optional.of(membership(VillageRole.ELDER)));

            assertThatCode(() -> service.requireHeadmanOrElder(VILLAGE_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("平メンバー（VILLAGER）は MODERATION_FORBIDDEN（403）")
        void villager_forbidden() {
            given(membershipRepository.findActiveByVillageIdAndSubject(
                    VILLAGE_ID, VillageSubjectType.USER, USER_ID))
                    .willReturn(Optional.of(membership(VillageRole.VILLAGER)));

            assertThatThrownBy(() -> service.requireHeadmanOrElder(VILLAGE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN));
        }

        @Test
        @DisplayName("退村/BAN/非メンバー（現役メンバーシップ無し）は MODERATION_FORBIDDEN（403）")
        void notActiveMember_forbidden() {
            given(membershipRepository.findActiveByVillageIdAndSubject(
                    VILLAGE_ID, VillageSubjectType.USER, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.requireHeadmanOrElder(VILLAGE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN));
        }
    }
}
