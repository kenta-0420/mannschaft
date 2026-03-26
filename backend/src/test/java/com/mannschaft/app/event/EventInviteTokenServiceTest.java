package com.mannschaft.app.event;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.dto.CreateInviteTokenRequest;
import com.mannschaft.app.event.dto.InviteTokenResponse;
import com.mannschaft.app.event.entity.EventGuestInviteTokenEntity;
import com.mannschaft.app.event.repository.EventGuestInviteTokenRepository;
import com.mannschaft.app.event.service.EventInviteTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link EventInviteTokenService} の単体テスト。
 * ゲスト招待トークンの作成・照会・無効化を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventInviteTokenService 単体テスト")
class EventInviteTokenServiceTest {

    @Mock
    private EventGuestInviteTokenRepository tokenRepository;

    @Mock
    private EventMapper eventMapper;

    @InjectMocks
    private EventInviteTokenService eventInviteTokenService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long EVENT_ID = 1L;
    private static final Long TOKEN_ID = 10L;
    private static final Long USER_ID = 100L;

    private EventGuestInviteTokenEntity createTokenEntity() {
        return EventGuestInviteTokenEntity.builder()
                .eventId(EVENT_ID)
                .token("uuid-token-value")
                .label("VIPゲスト用")
                .maxUses(10)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .createdBy(USER_ID)
                .build();
    }

    private InviteTokenResponse createTokenResponse() {
        return new InviteTokenResponse(
                TOKEN_ID, EVENT_ID, "uuid-token-value", "VIPゲスト用",
                10, 0, LocalDateTime.now().plusDays(7), true, USER_ID,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ========================================
    // listTokens
    // ========================================

    @Nested
    @DisplayName("listTokens")
    class ListTokens {

        @Test
        @DisplayName("トークン一覧取得_正常_リスト返却")
        void トークン一覧取得_正常_リスト返却() {
            // Given
            EventGuestInviteTokenEntity entity = createTokenEntity();
            InviteTokenResponse response = createTokenResponse();

            given(tokenRepository.findByEventIdOrderByCreatedAtDesc(EVENT_ID))
                    .willReturn(List.of(entity));
            given(eventMapper.toInviteTokenResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<InviteTokenResponse> result = eventInviteTokenService.listTokens(EVENT_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getLabel()).isEqualTo("VIPゲスト用");
        }

        @Test
        @DisplayName("トークン一覧取得_該当なし_空リスト返却")
        void トークン一覧取得_該当なし_空リスト返却() {
            // Given
            given(tokenRepository.findByEventIdOrderByCreatedAtDesc(EVENT_ID))
                    .willReturn(List.of());
            given(eventMapper.toInviteTokenResponseList(List.of()))
                    .willReturn(List.of());

            // When
            List<InviteTokenResponse> result = eventInviteTokenService.listTokens(EVENT_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // createToken
    // ========================================

    @Nested
    @DisplayName("createToken")
    class CreateToken {

        @Test
        @DisplayName("トークン作成_正常_レスポンス返却")
        void トークン作成_正常_レスポンス返却() {
            // Given
            CreateInviteTokenRequest request = new CreateInviteTokenRequest(
                    "VIPゲスト用", 10, LocalDateTime.now().plusDays(7)
            );
            EventGuestInviteTokenEntity savedEntity = createTokenEntity();
            InviteTokenResponse response = createTokenResponse();

            given(tokenRepository.save(any(EventGuestInviteTokenEntity.class))).willReturn(savedEntity);
            given(eventMapper.toInviteTokenResponse(savedEntity)).willReturn(response);

            // When
            InviteTokenResponse result = eventInviteTokenService.createToken(EVENT_ID, USER_ID, request);

            // Then
            assertThat(result.getLabel()).isEqualTo("VIPゲスト用");
            assertThat(result.getMaxUses()).isEqualTo(10);
            verify(tokenRepository).save(any(EventGuestInviteTokenEntity.class));
        }

        @Test
        @DisplayName("トークン作成_上限未指定_正常作成")
        void トークン作成_上限未指定_正常作成() {
            // Given
            CreateInviteTokenRequest request = new CreateInviteTokenRequest("一般ゲスト用", null, null);
            EventGuestInviteTokenEntity savedEntity = EventGuestInviteTokenEntity.builder()
                    .eventId(EVENT_ID).token("uuid-2").label("一般ゲスト用").createdBy(USER_ID).build();
            InviteTokenResponse response = new InviteTokenResponse(
                    11L, EVENT_ID, "uuid-2", "一般ゲスト用",
                    null, 0, null, true, USER_ID,
                    LocalDateTime.now(), LocalDateTime.now()
            );

            given(tokenRepository.save(any(EventGuestInviteTokenEntity.class))).willReturn(savedEntity);
            given(eventMapper.toInviteTokenResponse(savedEntity)).willReturn(response);

            // When
            InviteTokenResponse result = eventInviteTokenService.createToken(EVENT_ID, USER_ID, request);

            // Then
            assertThat(result.getMaxUses()).isNull();
        }
    }

    // ========================================
    // deactivateToken
    // ========================================

    @Nested
    @DisplayName("deactivateToken")
    class DeactivateToken {

        @Test
        @DisplayName("トークン無効化_正常_無効化実行")
        void トークン無効化_正常_無効化実行() {
            // Given
            EventGuestInviteTokenEntity entity = createTokenEntity();
            InviteTokenResponse response = new InviteTokenResponse(
                    TOKEN_ID, EVENT_ID, "uuid-token-value", "VIPゲスト用",
                    10, 0, LocalDateTime.now().plusDays(7), false, USER_ID,
                    LocalDateTime.now(), LocalDateTime.now()
            );

            given(tokenRepository.findById(TOKEN_ID)).willReturn(Optional.of(entity));
            given(tokenRepository.save(any(EventGuestInviteTokenEntity.class))).willReturn(entity);
            given(eventMapper.toInviteTokenResponse(entity)).willReturn(response);

            // When
            InviteTokenResponse result = eventInviteTokenService.deactivateToken(TOKEN_ID);

            // Then
            assertThat(result.getIsActive()).isFalse();
            verify(tokenRepository).save(any(EventGuestInviteTokenEntity.class));
        }

        @Test
        @DisplayName("トークン無効化_存在しない_例外スロー")
        void トークン無効化_存在しない_例外スロー() {
            // Given
            given(tokenRepository.findById(TOKEN_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventInviteTokenService.deactivateToken(TOKEN_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
