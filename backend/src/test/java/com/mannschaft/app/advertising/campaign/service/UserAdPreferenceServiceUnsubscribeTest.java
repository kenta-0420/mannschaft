package com.mannschaft.app.advertising.campaign.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.entity.UserAdPreference;
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
}
