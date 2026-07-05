package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F01.9 保護者同意ゲート AC-17: {@link ParentalConsentService#approveParentalConsent(String, Long)} が
 * 承認時に子ユーザーを ACTIVE へ遷移させる（{@code activate()} 呼び出し + save）ことを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("保護者同意承認 子ユーザー ACTIVE 化 (F01.9 AC-17)")
class ParentalConsentApproveActivationTest {

    @Mock
    private ParentalConsentLinkRepository parentalConsentLinkRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthTokenService authTokenService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private EmailOutboxService emailOutboxService;

    @InjectMocks
    private ParentalConsentService service;

    @Test
    @DisplayName("承認時に子ユーザーへ activate() が呼ばれ save される")
    void approve_activates_child() {
        String token = "raw-token";
        Long parentUserId = 100L;
        Long childUserId = 200L;

        when(authTokenService.hashToken(token)).thenReturn("token-hash");

        ParentalConsentLinkEntity link = mock(ParentalConsentLinkEntity.class);
        when(link.getStatus()).thenReturn(ParentalConsentLinkStatus.PENDING);
        when(link.getExpiresAt()).thenReturn(LocalDateTime.now().plusDays(1));
        when(link.getChildUserId()).thenReturn(childUserId);
        when(parentalConsentLinkRepository.findByTokenHash("token-hash"))
                .thenReturn(Optional.of(link));

        UserEntity parent = mock(UserEntity.class);
        when(parent.getBirthDate()).thenReturn(null); // 未成年保護者チェックはスキップ
        when(userRepository.findById(parentUserId)).thenReturn(Optional.of(parent));

        UserEntity child = mock(UserEntity.class);
        when(child.getEmail()).thenReturn("child@example.com");
        when(child.getDisplayName()).thenReturn("子ユーザー");
        when(child.getId()).thenReturn(childUserId);
        when(userRepository.findById(childUserId)).thenReturn(Optional.of(child));

        service.approveParentalConsent(token, parentUserId);

        verify(link).approve(parentUserId);
        verify(child).activate();
        verify(userRepository).save(child);
    }
}
