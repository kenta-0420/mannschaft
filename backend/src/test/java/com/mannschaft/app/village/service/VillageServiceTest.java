package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MonshoUploadUrlResponse;
import com.mannschaft.app.village.dto.VillageCreateRequest;
import com.mannschaft.app.village.dto.VillageResponse;
import com.mannschaft.app.village.dto.VillageSearchResponse;
import com.mannschaft.app.village.dto.VillageUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.repository.VillageSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VillageService} のユニットテスト（F17.1 Phase 1）。
 *
 * <p>カバレッジ目標: 90% 以上。CRUD・検索・凍結・権限・レートリミット・楽観ロックを網羅。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageService ユニットテスト")
class VillageServiceTest {

    @Mock private VillageRepository villageRepository;
    @Mock private VillageSearchRepository villageSearchRepository;
    @Mock private VillageMembershipRepository membershipRepository;
    @Mock private UserVillagePinRepository pinRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private R2StorageService r2StorageService;

    @InjectMocks private VillageService service;

    private static final Long ADMIN_USER_ID = 1L;
    private static final Long REGULAR_USER_ID = 100L;
    private static final Long HEADMAN_USER_ID = 50L;
    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");

    @BeforeEach
    void setUp() {
        // メンバーシップ・ピンは未参加・未ピンをデフォルト
        lenient().when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                any(UUID.class), eq(VillageSubjectType.USER), anyLong())).thenReturn(Optional.empty());
        lenient().when(pinRepository.findByUserIdAndVillageId(anyLong(), any(UUID.class)))
                .thenReturn(Optional.empty());
    }

    // ─────────────────────────────────────────────
    // create
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("SYSTEM_ADMIN は公式村を作成できる")
        void create_systemAdminCreatesOfficial() {
            when(accessControlService.isSystemAdmin(ADMIN_USER_ID)).thenReturn(true);
            when(villageRepository.existsBySlug("integral-clinic")).thenReturn(false);
            when(villageRepository.existsByName("整骨院村")).thenReturn(false);
            when(villageSearchRepository.count(any(Specification.class))).thenReturn(0L);
            when(villageRepository.save(any(VillageEntity.class))).thenAnswer(inv -> {
                VillageEntity e = inv.getArgument(0);
                return e.toBuilder().version(0L).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            });

            VillageCreateRequest req = new VillageCreateRequest(
                    "integral-clinic", "整骨院村", "整骨院に関わる人が集う場",
                    VillageType.OFFICIAL, VillageJoinPolicy.FREE, VillageVisibility.PUBLIC,
                    "業種", null, null);

            VillageResponse res = service.create(req, ADMIN_USER_ID);

            assertThat(res.slug()).isEqualTo("integral-clinic");
            assertThat(res.name()).isEqualTo("整骨院村");
            assertThat(res.type()).isEqualTo(VillageType.OFFICIAL);
            assertThat(res.isOfficial()).isTrue();
            verify(villageRepository).save(any(VillageEntity.class));
        }

        @Test
        @DisplayName("一般ユーザーは VILLAGE_CREATE_FORBIDDEN")
        void create_regularUserForbidden() {
            when(accessControlService.isSystemAdmin(REGULAR_USER_ID)).thenReturn(false);

            VillageCreateRequest req = new VillageCreateRequest(
                    "user-village", "ユーザー村", null,
                    VillageType.COMMUNITY, VillageJoinPolicy.FREE, VillageVisibility.PUBLIC,
                    null, null, null);

            assertThatThrownBy(() -> service.create(req, REGULAR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_CREATE_FORBIDDEN);
            verify(villageRepository, never()).save(any());
        }

        @Test
        @DisplayName("スラッグ形式不正は VILLAGE_SLUG_INVALID")
        void create_invalidSlug() {
            when(accessControlService.isSystemAdmin(ADMIN_USER_ID)).thenReturn(true);

            VillageCreateRequest req = new VillageCreateRequest(
                    "Bad_Slug!", "村", null,
                    VillageType.OFFICIAL, VillageJoinPolicy.FREE, VillageVisibility.PUBLIC,
                    null, null, null);

            assertThatThrownBy(() -> service.create(req, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_SLUG_INVALID);
        }

        @Test
        @DisplayName("スラッグ重複は VILLAGE_SLUG_TAKEN")
        void create_slugTaken() {
            when(accessControlService.isSystemAdmin(ADMIN_USER_ID)).thenReturn(true);
            when(villageRepository.existsBySlug("integral-clinic")).thenReturn(true);

            VillageCreateRequest req = new VillageCreateRequest(
                    "integral-clinic", "別の名前", null,
                    VillageType.OFFICIAL, VillageJoinPolicy.FREE, VillageVisibility.PUBLIC,
                    null, null, null);

            assertThatThrownBy(() -> service.create(req, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_SLUG_TAKEN);
        }

        @Test
        @DisplayName("名前重複は VILLAGE_NAME_TAKEN")
        void create_nameTaken() {
            when(accessControlService.isSystemAdmin(ADMIN_USER_ID)).thenReturn(true);
            when(villageRepository.existsBySlug("integral-clinic")).thenReturn(false);
            when(villageRepository.existsByName("整骨院村")).thenReturn(true);

            VillageCreateRequest req = new VillageCreateRequest(
                    "integral-clinic", "整骨院村", null,
                    VillageType.OFFICIAL, VillageJoinPolicy.FREE, VillageVisibility.PUBLIC,
                    null, null, null);

            assertThatThrownBy(() -> service.create(req, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_NAME_TAKEN);
        }

        @Test
        @DisplayName("当日 3 件目以降の作成は CREATION_REQUEST_THROTTLED")
        void create_rateLimited() {
            when(accessControlService.isSystemAdmin(ADMIN_USER_ID)).thenReturn(true);
            when(villageRepository.existsBySlug(any())).thenReturn(false);
            when(villageRepository.existsByName(any())).thenReturn(false);
            when(villageSearchRepository.count(any(Specification.class))).thenReturn(3L);

            VillageCreateRequest req = new VillageCreateRequest(
                    "fourth-village", "4つ目の村", null,
                    VillageType.OFFICIAL, VillageJoinPolicy.FREE, VillageVisibility.PUBLIC,
                    null, null, null);

            assertThatThrownBy(() -> service.create(req, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.CREATION_REQUEST_THROTTLED);
        }
    }

    // ─────────────────────────────────────────────
    // get
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("get()")
    class GetTests {

        @Test
        @DisplayName("PUBLIC 村は誰でも取得できる")
        void get_publicVisibleToAll() {
            VillageEntity entity = sampleVillage(VillageVisibility.PUBLIC, null);
            when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.of(entity));
            when(accessControlService.isSystemAdmin(REGULAR_USER_ID)).thenReturn(false);

            VillageResponse res = service.get(VILLAGE_ID, REGULAR_USER_ID);

            assertThat(res.id()).isEqualTo(VILLAGE_ID);
            assertThat(res.visibility()).isEqualTo(VillageVisibility.PUBLIC);
            assertThat(res.isMember()).isFalse();
        }

        @Test
        @DisplayName("UNLISTED 村は非村人だと VILLAGE_UNLISTED")
        void get_unlistedHiddenFromNonMember() {
            VillageEntity entity = sampleVillage(VillageVisibility.UNLISTED, null);
            when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.of(entity));
            when(accessControlService.isSystemAdmin(REGULAR_USER_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.get(VILLAGE_ID, REGULAR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_UNLISTED);
        }

        @Test
        @DisplayName("UNLISTED 村でも村人なら取得できる")
        void get_unlistedVisibleToMember() {
            VillageEntity entity = sampleVillage(VillageVisibility.UNLISTED, null);
            when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.of(entity));
            when(accessControlService.isSystemAdmin(REGULAR_USER_ID)).thenReturn(false);
            when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                    VILLAGE_ID, VillageSubjectType.USER, REGULAR_USER_ID))
                    .thenReturn(Optional.of(membership(VillageRole.VILLAGER)));

            VillageResponse res = service.get(VILLAGE_ID, REGULAR_USER_ID);

            assertThat(res.isMember()).isTrue();
            assertThat(res.myRole()).isEqualTo(VillageRole.VILLAGER);
        }

        @Test
        @DisplayName("UNLISTED 村でも SYSTEM_ADMIN なら取得できる")
        void get_unlistedVisibleToSystemAdmin() {
            VillageEntity entity = sampleVillage(VillageVisibility.UNLISTED, null);
            when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.of(entity));
            when(accessControlService.isSystemAdmin(ADMIN_USER_ID)).thenReturn(true);

            VillageResponse res = service.get(VILLAGE_ID, ADMIN_USER_ID);

            assertThat(res.id()).isEqualTo(VILLAGE_ID);
        }

        @Test
        @DisplayName("削除済み村は 404 (VILLAGE_NOT_FOUND)")
        void get_deletedNotFound() {
            when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(VILLAGE_ID, REGULAR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
    }

    // ─────────────────────────────────────────────
    // update
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        @DisplayName("HEADMAN は名前を更新できる")
        void update_headmanCanUpdateName() {
            VillageEntity entity = sampleVillage(VillageVisibility.PUBLIC, 0L);
            when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.of(entity));
            when(accessControlService.isSystemAdmin(HEADMAN_USER_ID)).thenReturn(false);
            when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                    VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                    .thenReturn(Optional.of(membership(VillageRole.HEADMAN)));
            when(villageRepository.existsByName("新しい名前")).thenReturn(false);
            when(villageRepository.save(any(VillageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            VillageUpdateRequest req = new VillageUpdateRequest(
                    "新しい名前", null, null, null, null, null, null, null, null);

            VillageResponse res = service.update(VILLAGE_ID, req, HEADMAN_USER_ID, 0L);

            assertThat(res.name()).isEqualTo("新しい名前");
        }

        @Test
        @DisplayName("VILLAGER（HEADMAN ではない）はモデレーション権限なしで 403")
        void update_villagerForbidden() {
            VillageEntity entity = sampleVillage(VillageVisibility.PUBLIC, 0L);
            when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.of(entity));
            when(accessControlService.isSystemAdmin(REGULAR_USER_ID)).thenReturn(false);
            when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                    VILLAGE_ID, VillageSubjectType.USER, REGULAR_USER_ID))
                    .thenReturn(Optional.of(membership(VillageRole.VILLAGER)));

            VillageUpdateRequest req = new VillageUpdateRequest(
                    "新しい名前", null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.update(VILLAGE_ID, req, REGULAR_USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
        }

        @Test
        @DisplayName("If-Match の version 不一致は楽観ロック例外")
        void update_versionConflict() {
            VillageEntity entity = sampleVillage(VillageVisibility.PUBLIC, 5L);
            when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.of(entity));
            when(accessControlService.isSystemAdmin(HEADMAN_USER_ID)).thenReturn(false);
            when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                    VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                    .thenReturn(Optional.of(membership(VillageRole.HEADMAN)));

            VillageUpdateRequest req = new VillageUpdateRequest(
                    null, "説明変更", null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.update(VILLAGE_ID, req, HEADMAN_USER_ID, 99L))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        }

        @Test
        @DisplayName("更新後の名前重複は VILLAGE_NAME_TAKEN")
        void update_newNameConflict() {
            VillageEntity entity = sampleVillage(VillageVisibility.PUBLIC, 0L);
            when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.of(entity));
            when(accessControlService.isSystemAdmin(HEADMAN_USER_ID)).thenReturn(false);
            when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                    VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                    .thenReturn(Optional.of(membership(VillageRole.HEADMAN)));
            when(villageRepository.existsByName("既存名前")).thenReturn(true);

            VillageUpdateRequest req = new VillageUpdateRequest(
                    "既存名前", null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.update(VILLAGE_ID, req, HEADMAN_USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_NAME_TAKEN);
        }
    }

    // ─────────────────────────────────────────────
    // softDelete
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("softDelete()")
    class SoftDeleteTests {

        @Test
        @DisplayName("HEADMAN は村を論理削除できる")
        void softDelete_headmanCanDelete() {
            VillageEntity entity = sampleVillage(VillageVisibility.PUBLIC, 0L);
            when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.of(entity));
            when(accessControlService.isSystemAdmin(HEADMAN_USER_ID)).thenReturn(false);
            when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                    VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                    .thenReturn(Optional.of(membership(VillageRole.HEADMAN)));

            service.softDelete(VILLAGE_ID, HEADMAN_USER_ID);

            ArgumentCaptor<VillageEntity> captor = ArgumentCaptor.forClass(VillageEntity.class);
            verify(villageRepository).save(captor.capture());
            assertThat(captor.getValue().getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("非 HEADMAN は MODERATION_FORBIDDEN")
        void softDelete_nonHeadmanForbidden() {
            VillageEntity entity = sampleVillage(VillageVisibility.PUBLIC, 0L);
            when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.of(entity));
            when(accessControlService.isSystemAdmin(REGULAR_USER_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.softDelete(VILLAGE_ID, REGULAR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
        }
    }

    // ─────────────────────────────────────────────
    // archive
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("archive()")
    class ArchiveTests {

        @Test
        @DisplayName("SYSTEM_ADMIN は村を凍結できる")
        void archive_systemAdminCanArchive() {
            VillageEntity entity = sampleVillage(VillageVisibility.PUBLIC, 0L);
            when(accessControlService.isSystemAdmin(ADMIN_USER_ID)).thenReturn(true);
            when(villageRepository.findById(VILLAGE_ID)).thenReturn(Optional.of(entity));

            service.archive(VILLAGE_ID, ADMIN_USER_ID, "ガイドライン違反");

            ArgumentCaptor<VillageEntity> captor = ArgumentCaptor.forClass(VillageEntity.class);
            verify(villageRepository).save(captor.capture());
            assertThat(captor.getValue().getArchivedAt()).isNotNull();
        }

        @Test
        @DisplayName("一般ユーザーは凍結 NG（MODERATION_FORBIDDEN）")
        void archive_regularUserForbidden() {
            when(accessControlService.isSystemAdmin(REGULAR_USER_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.archive(VILLAGE_ID, REGULAR_USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
            verify(villageRepository, never()).save(any());
        }

        @Test
        @DisplayName("既に凍結済みなら VILLAGE_ALREADY_ARCHIVED")
        void archive_alreadyArchived() {
            VillageEntity entity = sampleVillage(VillageVisibility.PUBLIC, 0L);
            entity.setArchivedAt(LocalDateTime.now().minusDays(1));
            when(accessControlService.isSystemAdmin(ADMIN_USER_ID)).thenReturn(true);
            when(villageRepository.findById(VILLAGE_ID)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.archive(VILLAGE_ID, ADMIN_USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }

        @Test
        @DisplayName("削除済み村は VILLAGE_NOT_FOUND")
        void archive_deletedVillageNotFound() {
            VillageEntity entity = sampleVillage(VillageVisibility.PUBLIC, 0L);
            entity.setDeletedAt(LocalDateTime.now().minusDays(1));
            when(accessControlService.isSystemAdmin(ADMIN_USER_ID)).thenReturn(true);
            when(villageRepository.findById(VILLAGE_ID)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.archive(VILLAGE_ID, ADMIN_USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
    }

    // ─────────────────────────────────────────────
    // search
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("search()")
    class SearchTests {

        @Test
        @DisplayName("検索結果は PUBLIC 村のみ含む")
        void search_returnsPublicVillages() {
            VillageEntity hit = sampleVillage(VillageVisibility.PUBLIC, 0L);
            Page<VillageEntity> page = new PageImpl<>(List.of(hit));
            when(villageSearchRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(page);

            VillageSearchResponse res = service.search("整骨", null, null, 0, 20, REGULAR_USER_ID);

            assertThat(res.content()).hasSize(1);
            assertThat(res.content().get(0).visibility()).isEqualTo(VillageVisibility.PUBLIC);
            assertThat(res.totalElements()).isEqualTo(1);
            verify(villageSearchRepository, times(1)).findAll(any(Specification.class), any(Pageable.class));
        }

        @Test
        @DisplayName("page < 0 / size > 100 はクランプされる")
        void search_pageSizeClamp() {
            when(villageSearchRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            VillageSearchResponse res = service.search(null, null, null, -5, 999, REGULAR_USER_ID);

            assertThat(res.page()).isEqualTo(0);
            assertThat(res.size()).isEqualTo(100);
        }

        @Test
        @DisplayName("検索結果が空の場合 totalElements=0")
        void search_emptyResult() {
            when(villageSearchRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            VillageSearchResponse res = service.search("該当なし", "業種", VillageType.OFFICIAL, 0, 20, REGULAR_USER_ID);

            assertThat(res.content()).isEmpty();
            assertThat(res.totalElements()).isEqualTo(0);
        }
    }

    // ─────────────────────────────────────────────
    // generateMonshoUploadUrl （#2355 村紋 presign 発行 EP）
    // ─────────────────────────────────────────────

    /**
     * 村紋 presign 発行の Service ロジック検証（#2355）。
     *
     * <p>{@link VillageService#generateMonshoUploadUrl} は本実装済み（green）。
     * 認可（村存在確認→HEADMAN/SYSTEM_ADMIN）を先行し、その後 MIME（jpeg/png/webp）と
     * サイズ（0 超〜{@code MONSHO_MAX_BYTES}=5MB）を検証し、キー規約
     * {@code village/{villageId}/monsho/{uuid}.{ext}} でキーを組んで presign（TTL=600 秒）を払い出す。
     * ここでは認可（AC-2/AC-3）・MIME/サイズ境界（AC-5）・キー規約/TTL（AC-1）を網羅する。</p>
     *
     * <p>設定用スタブは {@code lenient()} で置く（分岐により未到達となる mock があっても
     * UnnecessaryStubbing で落とさないため）。R2StorageService は外部境界のため mock、
     * 認可判定は実物（{@code requireHeadmanOrSystemAdmin} / {@code findActiveOrThrow}）を通す。</p>
     */
    @Nested
    @DisplayName("generateMonshoUploadUrl() — 村紋 presign 発行（#2355）")
    class GenerateMonshoUploadUrlTests {

        private static final String VALID_MIME = "image/png";
        // 村紋サイズ上限（実装 MONSHO_MAX_BYTES=5MB）
        private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
        // 上限を確実に超えるサイズ
        private static final long HUGE_FILE_SIZE = 50L * 1024 * 1024;
        private static final long OK_FILE_SIZE = 100L * 1024;

        private void givenVillageExists() {
            lenient().when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.of(sampleVillage(VillageVisibility.PUBLIC, 0L)));
        }

        private void givenHeadman() {
            lenient().when(accessControlService.isSystemAdmin(HEADMAN_USER_ID)).thenReturn(false);
            lenient().when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                            VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                    .thenReturn(Optional.of(membership(VillageRole.HEADMAN)));
        }

        @Test
        @DisplayName("AC-1: HEADMAN は 200 相当で uploadUrl / 村スコープ r2Key / TTL=600 を得る")
        void generateUploadUrl_headmanSucceeds() {
            givenVillageExists();
            givenHeadman();
            // 実 R2StorageService は渡された s3Key をそのまま echo する。mock も同挙動にし、
            // Service が村スコープキーを生成して presign に渡していることを検証する。
            lenient().when(r2StorageService.generateUploadUrl(
                            any(String.class), eq(VALID_MIME), any(Duration.class)))
                    .thenAnswer(inv -> new PresignedUploadResult(
                            "https://r2.example.com/put?sig=xyz", inv.getArgument(0), 600L));

            MonshoUploadUrlResponse res = service.generateMonshoUploadUrl(
                    VILLAGE_ID, VALID_MIME, OK_FILE_SIZE, HEADMAN_USER_ID);

            assertThat(res.uploadUrl()).isEqualTo("https://r2.example.com/put?sig=xyz");
            assertThat(res.expiresInSeconds()).isEqualTo(600L);
            assertThat(res.r2Key()).startsWith("village/" + VILLAGE_ID + "/monsho/");
            assertThat(res.r2Key()).endsWith(".png");
        }

        @Test
        @DisplayName("AC-5境界: サイズちょうど 5MB(MONSHO_MAX_BYTES) は成功（> でのみ弾くため通る）")
        void generateUploadUrl_fileSizeExactlyMaxSucceeds() {
            givenVillageExists();
            givenHeadman();
            lenient().when(r2StorageService.generateUploadUrl(
                            any(String.class), eq(VALID_MIME), any(Duration.class)))
                    .thenAnswer(inv -> new PresignedUploadResult(
                            "https://r2.example.com/put?sig=boundary", inv.getArgument(0), 600L));

            MonshoUploadUrlResponse res = service.generateMonshoUploadUrl(
                    VILLAGE_ID, VALID_MIME, MAX_FILE_SIZE, HEADMAN_USER_ID);

            assertThat(res.uploadUrl()).isEqualTo("https://r2.example.com/put?sig=boundary");
            assertThat(res.r2Key()).startsWith("village/" + VILLAGE_ID + "/monsho/");
            assertThat(res.r2Key()).endsWith(".png");
        }

        @Test
        @DisplayName("AC-5境界: サイズ 0 は 400（VILLAGE_FIELD_INVALID）— fileSize <= 0 分岐")
        void generateUploadUrl_fileSizeZero() {
            givenVillageExists();
            givenHeadman();

            assertThatThrownBy(() -> service.generateMonshoUploadUrl(
                    VILLAGE_ID, VALID_MIME, 0L, HEADMAN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_FIELD_INVALID);

            verify(r2StorageService, never()).generateUploadUrl(any(), any(), any());
        }

        @Test
        @DisplayName("AC-5境界: サイズ負値(-1) は 400（VILLAGE_FIELD_INVALID）")
        void generateUploadUrl_fileSizeNegative() {
            givenVillageExists();
            givenHeadman();

            assertThatThrownBy(() -> service.generateMonshoUploadUrl(
                    VILLAGE_ID, VALID_MIME, -1L, HEADMAN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_FIELD_INVALID);

            verify(r2StorageService, never()).generateUploadUrl(any(), any(), any());
        }

        @Test
        @DisplayName("AC-2: 非 HEADMAN・非 SYSTEM_ADMIN は MODERATION_FORBIDDEN（403）")
        void generateUploadUrl_nonHeadmanForbidden() {
            givenVillageExists();
            lenient().when(accessControlService.isSystemAdmin(REGULAR_USER_ID)).thenReturn(false);
            // membership は @BeforeEach の既定で空（非メンバー）

            assertThatThrownBy(() -> service.generateMonshoUploadUrl(
                    VILLAGE_ID, VALID_MIME, OK_FILE_SIZE, REGULAR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);

            verify(r2StorageService, never()).generateUploadUrl(any(), any(), any());
        }

        @Test
        @DisplayName("AC-3: 存在しない村（UUID）は VILLAGE_NOT_FOUND（404）")
        void generateUploadUrl_villageNotFound() {
            lenient().when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.generateMonshoUploadUrl(
                    VILLAGE_ID, VALID_MIME, OK_FILE_SIZE, HEADMAN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);

            verify(r2StorageService, never()).generateUploadUrl(any(), any(), any());
        }

        @Test
        @DisplayName("AC-5: 不正 MIME（image/svg+xml）は 400（VILLAGE_FIELD_INVALID）")
        void generateUploadUrl_invalidMimeSvg() {
            givenVillageExists();
            givenHeadman();

            assertThatThrownBy(() -> service.generateMonshoUploadUrl(
                    VILLAGE_ID, "image/svg+xml", OK_FILE_SIZE, HEADMAN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_FIELD_INVALID);

            verify(r2StorageService, never()).generateUploadUrl(any(), any(), any());
        }

        @Test
        @DisplayName("AC-5: 不正 MIME（application/pdf）は 400（VILLAGE_FIELD_INVALID）")
        void generateUploadUrl_invalidMimePdf() {
            givenVillageExists();
            givenHeadman();

            assertThatThrownBy(() -> service.generateMonshoUploadUrl(
                    VILLAGE_ID, "application/pdf", OK_FILE_SIZE, HEADMAN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_FIELD_INVALID);

            verify(r2StorageService, never()).generateUploadUrl(any(), any(), any());
        }

        @Test
        @DisplayName("AC-5: サイズ超過は 400（VILLAGE_FIELD_INVALID）")
        void generateUploadUrl_fileSizeExceeded() {
            givenVillageExists();
            givenHeadman();

            assertThatThrownBy(() -> service.generateMonshoUploadUrl(
                    VILLAGE_ID, VALID_MIME, HUGE_FILE_SIZE, HEADMAN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_FIELD_INVALID);

            verify(r2StorageService, never()).generateUploadUrl(any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private static VillageEntity sampleVillage(VillageVisibility visibility, Long version) {
        VillageEntity e = VillageEntity.builder()
                .slug("integral-clinic")
                .name("整骨院村")
                .description("整骨院村の説明")
                .type(VillageType.OFFICIAL)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(visibility)
                .category("業種")
                .memberCountCache(10L)
                .createdByUserId(ADMIN_USER_ID)
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .version(version != null ? version : 0L)
                .build();
        // UuidV7Entity の id を reflection で設定（@GeneratedValue は JPA 永続化時にのみ採番されるためテストでは手動）
        try {
            java.lang.reflect.Field idField =
                    com.mannschaft.app.common.entity.UuidV7Entity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(e, VILLAGE_ID);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    private static VillageMembershipEntity membership(VillageRole role) {
        return VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(HEADMAN_USER_ID)
                .role(role)
                .joinedAt(LocalDateTime.now().minusDays(7))
                .createdAt(LocalDateTime.now().minusDays(7))
                .updatedAt(LocalDateTime.now().minusDays(7))
                .version(0L)
                .build();
    }
}
