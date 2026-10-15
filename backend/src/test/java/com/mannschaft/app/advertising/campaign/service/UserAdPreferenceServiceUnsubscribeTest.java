package com.mannschaft.app.advertising.campaign.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.dto.UnsubscribeResultResponse;
import com.mannschaft.app.advertising.campaign.entity.UserAdPreference;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.repository.UserAdPreferenceRepository;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link UserAdPreferenceService#unsubscribe} と
 * {@link UserAdPreferenceService#rotateUnsubscribeTokenVersion} の単体テスト
 * （F09.17 Phase 11-b 拡張分）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserAdPreferenceService unsubscribe / rotate 拡張テスト")
class UserAdPreferenceServiceUnsubscribeTest {

    @Mock
    private UserAdPreferenceRepository preferenceRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private UserAdPreferenceService service;

    private static final Long USER_ID = 1001L;

    private UserAdPreference baseEntity(int tokenVersion) {
        return UserAdPreference.builder()
                .userId(USER_ID)
                .acceptAnnouncementAds(Boolean.TRUE)
                .acceptEmailAds(Boolean.TRUE)
                .acceptPushAds(Boolean.TRUE)
                .acceptBannerAds(Boolean.TRUE)
                .blockedAdvertiserAccountIds("[]")
                .unsubscribeTokenVersion(tokenVersion)
                .build();
    }

    @Test
    @DisplayName("EMAIL channel を unsubscribe すると accept_email_ads=false に切替・他は維持")
    void unsubscribeEmailChannelSwitchesOnlyEmail() {
        UserAdPreference entity = baseEntity(0);
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));
        given(preferenceRepository.save(any(UserAdPreference.class))).willAnswer(inv -> inv.getArgument(0));

        service.unsubscribe(USER_ID, "EMAIL", 0);

        ArgumentCaptor<UserAdPreference> captor = ArgumentCaptor.forClass(UserAdPreference.class);
        verify(preferenceRepository).save(captor.capture());
        UserAdPreference saved = captor.getValue();
        assertThat(saved.getAcceptEmailAds()).isFalse();
        assertThat(saved.getAcceptAnnouncementAds()).isTrue();
        assertThat(saved.getAcceptPushAds()).isTrue();
        assertThat(saved.getAcceptBannerAds()).isTrue();
    }

    @Test
    @DisplayName("ANNOUNCEMENT / PUSH / BANNER も同様にそれぞれを OFF")
    void unsubscribeOtherChannels() {
        for (String channel : new String[]{"ANNOUNCEMENT", "PUSH", "BANNER"}) {
            UserAdPreference entity = baseEntity(0);
            given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));
            given(preferenceRepository.save(any(UserAdPreference.class))).willAnswer(inv -> inv.getArgument(0));

            service.unsubscribe(USER_ID, channel, 0);

            switch (channel) {
                case "ANNOUNCEMENT" -> assertThat(entity.getAcceptAnnouncementAds()).isFalse();
                case "PUSH"         -> assertThat(entity.getAcceptPushAds()).isFalse();
                case "BANNER"       -> assertThat(entity.getAcceptBannerAds()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("token_version 不一致 → AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH")
    void versionMismatchRejected() {
        UserAdPreference entity = baseEntity(5);
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.unsubscribe(USER_ID, "EMAIL", 3))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH);
    }

    @Test
    @DisplayName("ユーザー設定行が無ければデフォルト行を作成して unsubscribe を続行する")
    void createsDefaultRowWhenMissing() {
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        given(preferenceRepository.save(any(UserAdPreference.class))).willAnswer(inv -> inv.getArgument(0));

        // デフォルト行の token_version はサービス内で 0 として保存する想定
        service.unsubscribe(USER_ID, "EMAIL", 0);

        // save が 2 回呼ばれる（createDefault と unsubscribe 後の更新）
        verify(preferenceRepository, org.mockito.Mockito.atLeastOnce())
                .save(any(UserAdPreference.class));
    }

    @Test
    @DisplayName("不正な channel を渡すと AD_UNSUBSCRIBE_TOKEN_INVALID")
    void invalidChannelRejected() {
        UserAdPreference entity = baseEntity(0);
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.unsubscribe(USER_ID, "BOGUS", 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_INVALID);
    }

    @Test
    @DisplayName("null 引数は AD_UNSUBSCRIBE_TOKEN_INVALID")
    void nullArgsRejected() {
        assertThatThrownBy(() -> service.unsubscribe(null, "EMAIL", 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.unsubscribe(USER_ID, null, 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.unsubscribe(USER_ID, "EMAIL", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("rotateUnsubscribeTokenVersion で token_version が +1 される")
    void rotateIncrementsVersion() {
        UserAdPreference entity = baseEntity(7);
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));
        given(preferenceRepository.save(any(UserAdPreference.class))).willAnswer(inv -> inv.getArgument(0));

        service.rotateUnsubscribeTokenVersion(USER_ID);

        assertThat(entity.getUnsubscribeTokenVersion()).isEqualTo(8);
    }

    @Test
    @DisplayName("unsubscribe は冪等 — 既に false でも例外なく再度 false を書き戻す")
    void idempotentUnsubscribe() {
        UserAdPreference entity = baseEntity(0);
        entity.setAcceptEmailAds(Boolean.FALSE);
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));
        given(preferenceRepository.save(any(UserAdPreference.class))).willAnswer(inv -> inv.getArgument(0));

        service.unsubscribe(USER_ID, "EMAIL", 0);

        assertThat(entity.getAcceptEmailAds()).isFalse();
    }

    // ───────────────────────────────────────
    // applyChannelUnsubscribe (F09.17 残課題 4 SPA 経路)
    // ───────────────────────────────────────

    @Test
    @DisplayName("applyChannelUnsubscribe: 複数チャネル指定で対象のみ OFF、残りは remaining に含まれる")
    void applyChannelUnsubscribe_multiChannel() {
        UserAdPreference entity = baseEntity(2);
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));
        given(preferenceRepository.save(any(UserAdPreference.class))).willAnswer(inv -> inv.getArgument(0));

        UnsubscribeResultResponse result = service.applyChannelUnsubscribe(
                USER_ID, List.of(AdChannelType.EMAIL, AdChannelType.PUSH), 2);

        assertThat(entity.getAcceptEmailAds()).isFalse();
        assertThat(entity.getAcceptPushAds()).isFalse();
        assertThat(entity.getAcceptAnnouncementAds()).isTrue();
        assertThat(entity.getAcceptBannerAds()).isTrue();

        assertThat(result.disabledChannels()).containsExactly(AdChannelType.EMAIL, AdChannelType.PUSH);
        assertThat(result.remainingActiveChannels())
                .containsExactly(AdChannelType.ANNOUNCEMENT, AdChannelType.BANNER);
        assertThat(result.messageKey()).isEqualTo("advertising.unsubscribe_spa.success_message");
    }

    @Test
    @DisplayName("applyChannelUnsubscribe: 4 チャネル全指定で全 OFF、remaining は空")
    void applyChannelUnsubscribe_allChannels() {
        UserAdPreference entity = baseEntity(0);
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));
        given(preferenceRepository.save(any(UserAdPreference.class))).willAnswer(inv -> inv.getArgument(0));

        UnsubscribeResultResponse result = service.applyChannelUnsubscribe(
                USER_ID,
                List.of(AdChannelType.ANNOUNCEMENT, AdChannelType.EMAIL,
                        AdChannelType.PUSH, AdChannelType.BANNER),
                0);

        assertThat(entity.getAcceptAnnouncementAds()).isFalse();
        assertThat(entity.getAcceptEmailAds()).isFalse();
        assertThat(entity.getAcceptPushAds()).isFalse();
        assertThat(entity.getAcceptBannerAds()).isFalse();
        assertThat(result.remainingActiveChannels()).isEmpty();
        assertThat(result.disabledChannels()).hasSize(4);
    }

    @Test
    @DisplayName("applyChannelUnsubscribe: token_version 不一致は AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH")
    void applyChannelUnsubscribe_versionMismatch() {
        UserAdPreference entity = baseEntity(5);
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.applyChannelUnsubscribe(
                USER_ID, List.of(AdChannelType.EMAIL), 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH);
    }

    @Test
    @DisplayName("applyChannelUnsubscribe: null/empty 引数は AD_UNSUBSCRIBE_TOKEN_INVALID")
    void applyChannelUnsubscribe_invalidArgs() {
        assertThatThrownBy(() -> service.applyChannelUnsubscribe(null, List.of(AdChannelType.EMAIL), 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.applyChannelUnsubscribe(USER_ID, null, 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.applyChannelUnsubscribe(USER_ID, Collections.emptyList(), 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.applyChannelUnsubscribe(USER_ID, List.of(AdChannelType.EMAIL), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("applyChannelUnsubscribe: 重複 channel は LinkedHashSet で排除し冪等")
    void applyChannelUnsubscribe_duplicateChannelsAreIdempotent() {
        UserAdPreference entity = baseEntity(0);
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));
        given(preferenceRepository.save(any(UserAdPreference.class))).willAnswer(inv -> inv.getArgument(0));

        UnsubscribeResultResponse result = service.applyChannelUnsubscribe(
                USER_ID, List.of(AdChannelType.EMAIL, AdChannelType.EMAIL, AdChannelType.EMAIL), 0);

        assertThat(result.disabledChannels()).containsExactly(AdChannelType.EMAIL);
        assertThat(entity.getAcceptEmailAds()).isFalse();
    }

    @Test
    @DisplayName("applyChannelUnsubscribe: 行が無ければデフォルト作成して続行")
    void applyChannelUnsubscribe_createsDefaultRowWhenMissing() {
        given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        given(preferenceRepository.save(any(UserAdPreference.class))).willAnswer(inv -> inv.getArgument(0));

        UnsubscribeResultResponse result = service.applyChannelUnsubscribe(
                USER_ID, List.of(AdChannelType.EMAIL), 0);

        // createDefault + applyChannelUnsubscribe の更新で少なくとも 1 回以上 save が呼ばれる
        ArgumentCaptor<UserAdPreference> captor = ArgumentCaptor.forClass(UserAdPreference.class);
        verify(preferenceRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(result.disabledChannels()).containsExactly(AdChannelType.EMAIL);
    }
}
