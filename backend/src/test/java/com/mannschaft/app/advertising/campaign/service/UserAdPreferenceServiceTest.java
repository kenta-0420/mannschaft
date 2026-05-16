package com.mannschaft.app.advertising.campaign.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.dto.UpdateUserAdPreferencesRequest;
import com.mannschaft.app.advertising.campaign.dto.UserAdPreferenceResponse;
import com.mannschaft.app.advertising.campaign.entity.UserAdPreference;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.repository.UserAdPreferenceRepository;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link UserAdPreferenceService} 単体テスト（F09.17 Phase 11-a Preferences 域）。
 *
 * <p>設計書「Preferences 域」§3〜§5 に従い以下を検証:</p>
 * <ul>
 *   <li>初回アクセス時のデフォルト行自動作成（GET / PUT 両方）</li>
 *   <li>受信フラグ更新成功（部分更新）</li>
 *   <li>blocked_advertiser_account_ids 上限 100 件超過の拒否</li>
 *   <li>consented_at 初回設定（null → now）と 2 回目以降の維持</li>
 *   <li>rotateUnsubscribeTokens=true による version インクリメント</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserAdPreferenceService 単体テスト")
class UserAdPreferenceServiceTest {

    @Mock
    private UserAdPreferenceRepository preferenceRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private UserAdPreferenceService preferenceService;

    private static final Long USER_ID = 1001L;

    private UserAdPreference baseEntity(LocalDateTime consentedAt, Integer tokenVersion, String blockedJson) {
        return UserAdPreference.builder()
                .userId(USER_ID)
                .acceptAnnouncementAds(Boolean.TRUE)
                .acceptEmailAds(Boolean.TRUE)
                .acceptPushAds(Boolean.TRUE)
                .acceptBannerAds(Boolean.TRUE)
                .blockedAdvertiserAccountIds(blockedJson == null ? "[]" : blockedJson)
                .unsubscribeTokenVersion(tokenVersion)
                .consentedAt(consentedAt)
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
    }

    @BeforeEach
    void setUp() {
        // no-op
    }

    @Test
    @DisplayName("GET 初回: 既存行なし → デフォルト行を自動作成して返す（全 ON / consented_at=null / version=0）")
    void getOrCreateForUser_createsDefaultWhenAbsent() {
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        given(preferenceRepository.save(any(UserAdPreference.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UserAdPreferenceResponse res = preferenceService.getOrCreateForUser(USER_ID);

        ArgumentCaptor<UserAdPreference> captor = ArgumentCaptor.forClass(UserAdPreference.class);
        verify(preferenceRepository).save(captor.capture());
        UserAdPreference saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getAcceptAnnouncementAds()).isTrue();
        assertThat(saved.getAcceptEmailAds()).isTrue();
        assertThat(saved.getAcceptPushAds()).isTrue();
        assertThat(saved.getAcceptBannerAds()).isTrue();
        assertThat(saved.getBlockedAdvertiserAccountIds()).isEqualTo("[]");
        assertThat(saved.getUnsubscribeTokenVersion()).isZero();
        assertThat(saved.getConsentedAt()).isNull();

        assertThat(res.acceptAnnouncementAds()).isTrue();
        assertThat(res.blockedAdvertiserAccountIds()).isEmpty();
        assertThat(res.unsubscribeTokenVersion()).isZero();
        assertThat(res.consentedAt()).isNull();
    }

    @Test
    @DisplayName("GET 既存行あり: そのまま返す（自動作成しない）")
    void getOrCreateForUser_returnsExisting() {
        UserAdPreference existing = baseEntity(LocalDateTime.now().minusHours(1), 3, "[42,43]");
        existing.setAcceptEmailAds(false);
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));

        UserAdPreferenceResponse res = preferenceService.getOrCreateForUser(USER_ID);

        assertThat(res.acceptEmailAds()).isFalse();
        assertThat(res.unsubscribeTokenVersion()).isEqualTo(3);
        assertThat(res.blockedAdvertiserAccountIds()).containsExactly(42L, 43L);
        verify(preferenceRepository, org.mockito.Mockito.never()).save(any(UserAdPreference.class));
    }

    @Test
    @DisplayName("PUT 更新成功: 受信フラグの部分更新 + blocked 配列更新 + consented_at が初回 now にセットされる")
    void updateForUser_partialUpdate_andSetsConsentedAtOnFirstPut() {
        UserAdPreference existing = baseEntity(null, 0, "[]");
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));
        given(preferenceRepository.save(any(UserAdPreference.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateUserAdPreferencesRequest req = new UpdateUserAdPreferencesRequest(
                null,           // acceptAnnouncementAds 不更新 → true 維持
                Boolean.FALSE,  // acceptEmailAds → false
                null,
                null,
                List.of(10L, 20L, 30L),
                null);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        UserAdPreferenceResponse res = preferenceService.updateForUser(USER_ID, req);

        assertThat(res.acceptAnnouncementAds()).isTrue();
        assertThat(res.acceptEmailAds()).isFalse();
        assertThat(res.blockedAdvertiserAccountIds()).containsExactly(10L, 20L, 30L);
        assertThat(res.consentedAt()).isNotNull().isAfterOrEqualTo(before);
        assertThat(res.unsubscribeTokenVersion()).isZero();
    }

    @Test
    @DisplayName("PUT 2 回目: consented_at は既存値が維持され上書きされない")
    void updateForUser_preservesConsentedAtOnSubsequentPut() {
        LocalDateTime originalConsent = LocalDateTime.of(2026, 1, 1, 12, 0);
        UserAdPreference existing = baseEntity(originalConsent, 2, "[]");
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));
        given(preferenceRepository.save(any(UserAdPreference.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateUserAdPreferencesRequest req = new UpdateUserAdPreferencesRequest(
                Boolean.FALSE, null, null, null, null, null);

        UserAdPreferenceResponse res = preferenceService.updateForUser(USER_ID, req);

        assertThat(res.consentedAt()).isEqualTo(originalConsent);
        assertThat(res.acceptAnnouncementAds()).isFalse();
        assertThat(res.unsubscribeTokenVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("PUT 上限超過: blocked が 101 件 → BusinessException(AD_PREFERENCES_BLOCKED_LIMIT)")
    void updateForUser_rejectsBlockedOverLimit() {
        UserAdPreference existing = baseEntity(LocalDateTime.now(), 0, "[]");
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));

        List<Long> tooMany = LongStream.rangeClosed(1, 101).boxed().collect(Collectors.toList());
        UpdateUserAdPreferencesRequest req = new UpdateUserAdPreferencesRequest(
                null, null, null, null, tooMany, null);

        assertThatThrownBy(() -> preferenceService.updateForUser(USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_PREFERENCES_BLOCKED_LIMIT);

        verify(preferenceRepository, org.mockito.Mockito.never()).save(any(UserAdPreference.class));
    }

    @Test
    @DisplayName("PUT 上限ちょうど 100 件: 受け入れる")
    void updateForUser_acceptsBlockedAtLimit() {
        UserAdPreference existing = baseEntity(LocalDateTime.now(), 0, "[]");
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));
        given(preferenceRepository.save(any(UserAdPreference.class)))
                .willAnswer(inv -> inv.getArgument(0));

        List<Long> exact = LongStream.rangeClosed(1, 100).boxed().collect(Collectors.toList());
        UpdateUserAdPreferencesRequest req = new UpdateUserAdPreferencesRequest(
                null, null, null, null, exact, null);

        UserAdPreferenceResponse res = preferenceService.updateForUser(USER_ID, req);

        assertThat(res.blockedAdvertiserAccountIds()).hasSize(100);
    }

    @Test
    @DisplayName("PUT rotateUnsubscribeTokens=true: token version が +1 される")
    void updateForUser_rotatesUnsubscribeTokenVersion() {
        UserAdPreference existing = baseEntity(LocalDateTime.now(), 5, "[]");
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));
        given(preferenceRepository.save(any(UserAdPreference.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateUserAdPreferencesRequest req = new UpdateUserAdPreferencesRequest(
                null, null, null, null, null, Boolean.TRUE);

        UserAdPreferenceResponse res = preferenceService.updateForUser(USER_ID, req);

        assertThat(res.unsubscribeTokenVersion()).isEqualTo(6);
    }

    @Test
    @DisplayName("PUT rotateUnsubscribeTokens=false / null: token version は据え置き")
    void updateForUser_doesNotRotateWhenFlagOff() {
        UserAdPreference existing = baseEntity(LocalDateTime.now(), 5, "[]");
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));
        given(preferenceRepository.save(any(UserAdPreference.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateUserAdPreferencesRequest req = new UpdateUserAdPreferencesRequest(
                null, null, null, null, null, Boolean.FALSE);

        UserAdPreferenceResponse res = preferenceService.updateForUser(USER_ID, req);

        assertThat(res.unsubscribeTokenVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("PUT 初回（既存行なし）: デフォルト行作成 → 更新 → consented_at が now にセットされる")
    void updateForUser_createsDefaultThenUpdates() {
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        // createDefault → save、その後の更新後 save の 2 回呼ばれる。両方とも引数を返す。
        given(preferenceRepository.save(any(UserAdPreference.class)))
                .willAnswer(inv -> {
                    UserAdPreference arg = inv.getArgument(0);
                    // JPA 永続化を模擬: 新規作成時は id を付与
                    if (arg.getId() == null) {
                        arg.setId(UUID.randomUUID());
                    }
                    return arg;
                });

        UpdateUserAdPreferencesRequest req = new UpdateUserAdPreferencesRequest(
                Boolean.FALSE, null, null, null, null, null);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        UserAdPreferenceResponse res = preferenceService.updateForUser(USER_ID, req);

        assertThat(res.acceptAnnouncementAds()).isFalse();
        assertThat(res.consentedAt()).isNotNull().isAfterOrEqualTo(before);
        assertThat(res.unsubscribeTokenVersion()).isZero();
        verify(preferenceRepository, org.mockito.Mockito.times(2)).save(any(UserAdPreference.class));
    }
}
