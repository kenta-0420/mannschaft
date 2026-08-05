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

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F01.9 保護者同意ゲート AC-16: {@link ParentalConsentReleaseBatchService#execute()} は
 * 18 歳到達子ユーザーの APPROVED リンクを REVOKED に更新するが、
 * <b>ユーザーのステータスは一切変更しない</b>（ACTIVE のまま）ことを検証する。
 *
 * <p>将来バッチにステータス変更が混入したら本テストで検知する回帰ガード。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("18歳到達解放バッチ ステータス不変 (F01.9 AC-16)")
class ParentalConsentReleaseBatchStatusInvariantTest {

    @Mock
    private ParentalConsentLinkRepository parentalConsentLinkRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private EmailOutboxService emailOutboxService;

    @InjectMocks
    private ParentalConsentReleaseBatchService service;

    @Test
    @DisplayName("成人到達子: リンクは REVOKED に更新されるが user.status は変更されない")
    void adult_child_link_revoked_status_unchanged() {
        Long childUserId = 200L;

        ParentalConsentLinkEntity link = mock(ParentalConsentLinkEntity.class);
        when(link.getChildUserId()).thenReturn(childUserId);
        when(link.getStatus()).thenReturn(ParentalConsentLinkStatus.APPROVED);
        when(parentalConsentLinkRepository.findAdultApprovedLinks(
                eq(ParentalConsentLinkStatus.APPROVED), anyString(), any()))
                .thenReturn(List.of(link))
                .thenReturn(List.of());

        UserEntity child = mock(UserEntity.class);
        // 成人判定は取得クエリ側で済んでいるため、バッチは生年月日を再判定しない
        when(child.getEmail()).thenReturn("child@example.com");
        when(child.getDisplayName()).thenReturn("子ユーザー");
        when(child.getId()).thenReturn(childUserId);
        when(userRepository.findById(childUserId)).thenReturn(Optional.of(child));

        service.execute();

        // リンクは REVOKED へ（SYSTEM 自動解放 = revokedBy null）
        verify(link).revoke(null);
        // ユーザーの status を変える経路は一切踏まれないこと（ACTIVE のまま）
        verify(child, never()).activate();
        verify(child, never()).pendingParentalConsent();
        verify(userRepository, never()).save(any());
    }
}
