package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link VillageCharterAccessService#loadReadableVillageOrHide} の単体試練。
 *
 * <p>本サービスは村ドメインで<b>唯一</b>、当初から可視性まで見て 404 に畳んでいた実装であり、
 * 共通ゲート {@link VillageAccessGate} はここから抽出された。ゲートへ委譲する改修で
 * <b>外から見える挙動が 1 ミリも変わらない</b>ことを固定するのが本テストの役目である
 * （委譲は内部実装の一本化であって、契約の変更ではない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VillageCharterAccessService — 憲章 read 公開ゲート")
class VillageCharterAccessServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000011");
    private static final UUID MISSING_VILLAGE_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000fe");
    private static final Long STRANGER_ID = 2001L;
    private static final Long MEMBER_ID = 2002L;
    private static final Long ADMIN_ID = 2003L;

    @Mock
    private VillageRepository villageRepository;

    @Mock
    private VillageMembershipRepository membershipRepository;

    @Mock
    private AccessControlService accessControlService;

    private VillageCharterAccessService service;

    @BeforeEach
    void setUp() {
        service = new VillageCharterAccessService(villageRepository, membershipRepository, accessControlService);
    }

    private VillageEntity village(VillageVisibility visibility, LocalDateTime deletedAt, LocalDateTime archivedAt) {
        VillageEntity v = VillageEntity.builder()
                .slug("charter-village")
                .name("憲章村")
                .visibility(visibility)
                .deletedAt(deletedAt)
                .archivedAt(archivedAt)
                .build();
        v.setId(VILLAGE_ID);
        return v;
    }

    /** 新旧どちらの取得経路でも同じ村が返るようにする（委譲前後で本テストがそのまま通ること自体が非退行の証拠）。 */
    private void givenVillage(VillageEntity v) {
        boolean readable = v.getDeletedAt() == null && v.getArchivedAt() == null;
        lenient().when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(VILLAGE_ID))
                .thenReturn(readable ? Optional.of(v) : Optional.empty());
        lenient().when(villageRepository.findById(VILLAGE_ID)).thenReturn(Optional.of(v));
        lenient().when(membershipRepository.findActiveByVillageIdAndSubject(any(), any(), anyLong()))
                .thenReturn(Optional.empty());
        lenient().when(accessControlService.isSystemAdmin(anyLong())).thenReturn(false);
    }

    private VillageErrorCode codeOf(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        return (VillageErrorCode) ((BusinessException) t).getErrorCode();
    }

    @Test
    @DisplayName("PUBLIC 村はログイン済みなら非村人でも憲章を読める")
    void publicVillage_strangerCanRead() {
        givenVillage(village(VillageVisibility.PUBLIC, null, null));

        assertThatCode(() -> service.loadReadableVillageOrHide(VILLAGE_ID, STRANGER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-1: UNLISTED 村の非村人は不在村 ID と同じ VILLAGE_NOT_FOUND")
    void unlistedVillage_strangerMatchesMissing() {
        when(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(MISSING_VILLAGE_ID))
                .thenReturn(Optional.empty());
        when(villageRepository.findById(MISSING_VILLAGE_ID)).thenReturn(Optional.empty());
        VillageErrorCode missingCode =
                codeOf(catchThrowable(() -> service.loadReadableVillageOrHide(MISSING_VILLAGE_ID, STRANGER_ID)));

        givenVillage(village(VillageVisibility.UNLISTED, null, null));
        VillageErrorCode unlistedCode =
                codeOf(catchThrowable(() -> service.loadReadableVillageOrHide(VILLAGE_ID, STRANGER_ID)));

        assertThat(unlistedCode).isEqualTo(missingCode).isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-3: UNLISTED 村の現役村人は憲章を読める")
    void unlistedVillage_activeMemberCanRead() {
        givenVillage(village(VillageVisibility.UNLISTED, null, null));
        when(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(MEMBER_ID)))
                .thenReturn(Optional.of(new VillageMembershipEntity()));

        assertThat(service.loadReadableVillageOrHide(VILLAGE_ID, MEMBER_ID).getId()).isEqualTo(VILLAGE_ID);
    }

    @Test
    @DisplayName("AC-3: UNLISTED 村の SYSTEM_ADMIN は憲章を読める")
    void unlistedVillage_systemAdminCanRead() {
        givenVillage(village(VillageVisibility.UNLISTED, null, null));
        when(accessControlService.isSystemAdmin(ADMIN_ID)).thenReturn(true);

        assertThat(service.loadReadableVillageOrHide(VILLAGE_ID, ADMIN_ID).getId()).isEqualTo(VILLAGE_ID);
    }

    @Test
    @DisplayName("凍結済み村は read では 404 に畳む（PUBLIC でも）")
    void archivedVillage_hidden() {
        givenVillage(village(VillageVisibility.PUBLIC, null, LocalDateTime.now()));

        assertThat(codeOf(catchThrowable(() -> service.loadReadableVillageOrHide(VILLAGE_ID, STRANGER_ID))))
                .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("論理削除済み村は 404")
    void deletedVillage_hidden() {
        givenVillage(village(VillageVisibility.PUBLIC, LocalDateTime.now(), null));

        assertThat(codeOf(catchThrowable(() -> service.loadReadableVillageOrHide(VILLAGE_ID, STRANGER_ID))))
                .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("viewerId が null（未特定閲覧者）でも UNLISTED は 404 で秘匿する")
    void unlistedVillage_nullViewer_hidden() {
        givenVillage(village(VillageVisibility.UNLISTED, null, null));

        assertThat(codeOf(catchThrowable(() -> service.loadReadableVillageOrHide(VILLAGE_ID, null))))
                .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }
}
