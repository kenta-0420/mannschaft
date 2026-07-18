package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.NewsletterSettingResponse;
import com.mannschaft.app.village.dto.NewsletterSettingUpdateRequest;
import com.mannschaft.app.village.dto.NewsletterSettingsResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageNewsletterEntity;
import com.mannschaft.app.village.entity.VillageNewsletterOptOutEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.repository.VillageNewsletterOptOutRepository;
import com.mannschaft.app.village.repository.VillageNewsletterRepository;
import com.mannschaft.app.village.repository.VillageNewsletterSendLogRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageNewsletterService} 単体テスト（F17.1 Phase 3-β-E）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>設定取得: opt-out 状態の正確な反映</li>
 *   <li>設定更新: HEADMAN/ELDER のみ許可</li>
 *   <li>opt-out: 新規登録 / 二重登録拒否</li>
 *   <li>opt-in: 復帰 / opt-out レコードがない場合の拒否</li>
 *   <li>削除済み村への操作は VILLAGE_NOT_FOUND</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageNewsletterService 単体テスト")
class VillageNewsletterServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000801");
    private static final Long HEADMAN_USER_ID = 901L;
    private static final Long VILLAGER_USER_ID = 902L;
    private static final Long OPT_OUT_USER_ID = 903L;

    @Mock
    private VillageNewsletterRepository newsletterRepository;
    @Mock
    private VillageNewsletterOptOutRepository optOutRepository;
    @Mock
    private VillageNewsletterSendLogRepository sendLogRepository;
    @Mock
    private VillageRepository villageRepository;
    @Mock
    private AuditLogService auditLogService;
    /** ②-4 堅牢性（AC-15/16）: HEADMAN/ELDER 認可述語は掲示板認可サービスへ集約されたためモック化する。 */
    @Mock
    private VillageBulletinAccessService bulletinAccessService;

    @InjectMocks
    private VillageNewsletterService service;

    // ====================================================================
    // 設定取得
    // ====================================================================

    @Test
    @DisplayName("設定取得: opt-out 済みユーザーには optedOut=true を返す")
    void getNewsletterSettings_returnsOptedOutTrue() {
        givenAliveVillage();
        given(newsletterRepository.findByVillageIdAndDeletedAtIsNull(VILLAGE_ID))
                .willReturn(List.of(buildSetting(VillageNewsletterFrequency.WEEKLY, true)));
        given(optOutRepository.existsByVillageIdAndUserId(VILLAGE_ID, OPT_OUT_USER_ID))
                .willReturn(true);

        NewsletterSettingsResponse res = service.getNewsletterSettings(VILLAGE_ID, OPT_OUT_USER_ID);

        assertThat(res.optedOut()).isTrue();
        assertThat(res.settings()).hasSize(1);
        assertThat(res.settings().get(0).frequency()).isEqualTo(VillageNewsletterFrequency.WEEKLY);
    }

    @Test
    @DisplayName("設定取得: opt-out していないユーザーには optedOut=false を返す")
    void getNewsletterSettings_returnsOptedOutFalse() {
        givenAliveVillage();
        given(newsletterRepository.findByVillageIdAndDeletedAtIsNull(VILLAGE_ID))
                .willReturn(List.of());
        given(optOutRepository.existsByVillageIdAndUserId(VILLAGE_ID, VILLAGER_USER_ID))
                .willReturn(false);

        NewsletterSettingsResponse res = service.getNewsletterSettings(VILLAGE_ID, VILLAGER_USER_ID);

        assertThat(res.optedOut()).isFalse();
        assertThat(res.settings()).isEmpty();
    }

    @Test
    @DisplayName("設定取得: 削除済み村は VILLAGE_NOT_FOUND")
    void getNewsletterSettings_deletedVillage_throws() {
        VillageEntity deleted = VillageEntity.builder().build();
        deleted.setId(VILLAGE_ID);
        deleted.setDeletedAt(LocalDateTime.now());
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.getNewsletterSettings(VILLAGE_ID, VILLAGER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    // ====================================================================
    // 設定更新
    // ====================================================================

    @Test
    @DisplayName("設定更新: HEADMAN が WEEKLY を新規 upsert できる")
    void updateNewsletterSettings_newWeekly_byHeadman_succeeds() {
        givenAliveVillage();
        givenActorWithRole(HEADMAN_USER_ID, VillageRole.HEADMAN);
        given(newsletterRepository.findByVillageIdAndFrequencyAndDeletedAtIsNull(
                VILLAGE_ID, VillageNewsletterFrequency.WEEKLY))
                .willReturn(Optional.empty());
        given(newsletterRepository.save(any(VillageNewsletterEntity.class)))
                .willAnswer(inv -> {
                    VillageNewsletterEntity arg = inv.getArgument(0);
                    arg.setId(UUID.randomUUID());
                    arg.setCreatedAt(LocalDateTime.now());
                    arg.setUpdatedAt(LocalDateTime.now());
                    arg.setVersion(0L);
                    return arg;
                });

        NewsletterSettingResponse res = service.updateNewsletterSettings(
                VILLAGE_ID,
                new NewsletterSettingUpdateRequest(VillageNewsletterFrequency.WEEKLY, true),
                HEADMAN_USER_ID);

        assertThat(res.frequency()).isEqualTo(VillageNewsletterFrequency.WEEKLY);
        assertThat(res.isEnabled()).isTrue();
        verify(newsletterRepository).save(any(VillageNewsletterEntity.class));
    }

    @Test
    @DisplayName("設定更新: VILLAGER は MODERATION_FORBIDDEN")
    void updateNewsletterSettings_byVillager_forbidden() {
        givenAliveVillage();
        givenActorWithRole(VILLAGER_USER_ID, VillageRole.VILLAGER);

        assertThatThrownBy(() -> service.updateNewsletterSettings(
                VILLAGE_ID,
                new NewsletterSettingUpdateRequest(VillageNewsletterFrequency.MONTHLY, true),
                VILLAGER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
        verify(newsletterRepository, never()).save(any());
    }

    // ====================================================================
    // opt-out
    // ====================================================================

    @Test
    @DisplayName("opt-out: 新規登録できる")
    void optOut_new_succeeds() {
        givenAliveVillage();
        given(optOutRepository.existsByVillageIdAndUserId(VILLAGE_ID, VILLAGER_USER_ID))
                .willReturn(false);

        service.optOut(VILLAGE_ID, VILLAGER_USER_ID);

        verify(optOutRepository, times(1)).save(any(VillageNewsletterOptOutEntity.class));
        verify(auditLogService, times(1)).record(
                eq("VILLAGE_NEWSLETTER_OPT_OUT"), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("opt-out: 既に opt-out 済みなら NEWSLETTER_ALREADY_OPTED_OUT")
    void optOut_duplicate_throws() {
        givenAliveVillage();
        given(optOutRepository.existsByVillageIdAndUserId(VILLAGE_ID, OPT_OUT_USER_ID))
                .willReturn(true);

        assertThatThrownBy(() -> service.optOut(VILLAGE_ID, OPT_OUT_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.NEWSLETTER_ALREADY_OPTED_OUT);
        verify(optOutRepository, never()).save(any());
    }

    // ====================================================================
    // opt-in
    // ====================================================================

    @Test
    @DisplayName("opt-in: 既存 opt-out レコードを削除できる")
    void optIn_existing_succeeds() {
        givenAliveVillage();
        VillageNewsletterOptOutEntity rec = VillageNewsletterOptOutEntity.builder()
                .villageId(VILLAGE_ID)
                .userId(OPT_OUT_USER_ID)
                .optedOutAt(LocalDateTime.now())
                .build();
        given(optOutRepository.findByVillageIdAndUserId(VILLAGE_ID, OPT_OUT_USER_ID))
                .willReturn(Optional.of(rec));

        service.optIn(VILLAGE_ID, OPT_OUT_USER_ID);

        verify(optOutRepository, times(1)).delete(rec);
    }

    @Test
    @DisplayName("opt-in: opt-out していないなら NEWSLETTER_NOT_OPTED_OUT")
    void optIn_notOptedOut_throws() {
        givenAliveVillage();
        given(optOutRepository.findByVillageIdAndUserId(VILLAGE_ID, VILLAGER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.optIn(VILLAGE_ID, VILLAGER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.NEWSLETTER_NOT_OPTED_OUT);
        verify(optOutRepository, never()).delete(any(VillageNewsletterOptOutEntity.class));
    }

    // ====================================================================
    // ヘルパ
    // ====================================================================

    private void givenAliveVillage() {
        VillageEntity v = VillageEntity.builder().build();
        v.setId(VILLAGE_ID);
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(v));
    }

    /**
     * 認可述語（{@code bulletinAccessService.requireHeadmanOrElder}）の挙動をロールで模す。
     * HEADMAN / ELDER は通過（void モックの no-op）、それ以外は MODERATION_FORBIDDEN を投げる。
     */
    private void givenActorWithRole(Long userId, VillageRole role) {
        if (role == VillageRole.HEADMAN || role == VillageRole.ELDER) {
            return;
        }
        willThrow(new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN))
                .given(bulletinAccessService).requireHeadmanOrElder(VILLAGE_ID, userId);
    }

    private VillageNewsletterEntity buildSetting(VillageNewsletterFrequency freq, boolean enabled) {
        VillageNewsletterEntity e = VillageNewsletterEntity.builder()
                .villageId(VILLAGE_ID)
                .frequency(freq)
                .isEnabled(enabled)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .version(0L)
                .build();
        e.setId(UUID.randomUUID());
        return e;
    }

}
