package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @InjectMocks
    private VillageBulletinAccessService service;

    private static final UUID VILLAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Long USER_ID = 10L;

    private VillageEntity village(VillageBulletinVisibility visibility) {
        return VillageEntity.builder()
                .slug("test-village")
                .name("テスト村")
                .bulletinVisibility(visibility)
                .build();
    }

    @Nested
    @DisplayName("PUBLIC 村")
    class PublicVillage {

        @Test
        @DisplayName("PUBLIC村_非メンバーでも閲覧可_例外なし")
        void public_非メンバーOK() {
            given(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .willReturn(Optional.of(village(VillageBulletinVisibility.PUBLIC)));

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
            given(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .willReturn(Optional.of(village(VillageBulletinVisibility.MEMBERS_ONLY)));
            given(postingIdentityService.isUserVillageMember(VILLAGE_ID, USER_ID)).willReturn(true);

            assertThatCode(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MEMBERS_ONLY村_SYSTEM_ADMIN_閲覧可")
        void membersOnly_SYSADMIN_OK() {
            given(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .willReturn(Optional.of(village(VillageBulletinVisibility.MEMBERS_ONLY)));
            given(postingIdentityService.isUserVillageMember(VILLAGE_ID, USER_ID)).willReturn(false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> service.checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MEMBERS_ONLY村_非メンバーかつ非ADMIN_403")
        void membersOnly_非メンバー_403() {
            given(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .willReturn(Optional.of(village(VillageBulletinVisibility.MEMBERS_ONLY)));
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
            given(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .willReturn(Optional.of(village(null)));
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
            given(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .willReturn(Optional.empty());

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
