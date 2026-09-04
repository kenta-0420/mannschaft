package com.mannschaft.app.provisioning.event;

import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * {@link ProvisioningEmailEventListener} のユニットテスト。
 *
 * <p>P2後始末: 組織名/チーム名（ユーザー入力）をメール本文（HTML）へ埋め込む際に
 * HTMLエスケープが行われることを固定する（XSS防止・根治修正の回帰防止）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProvisioningEmailEventListener 単体テスト")
class ProvisioningEmailEventListenerTest {

    @Mock
    private EmailOutboxService emailOutboxService;

    private ProvisioningEmailEventListener listener;

    private ProvisioningEmailEventListener newListener() {
        ProvisioningEmailEventListener l = new ProvisioningEmailEventListener(emailOutboxService);
        ReflectionTestUtils.setField(l, "baseUrl", "https://example.test");
        return l;
    }

    @Test
    @DisplayName("組織名にHTMLタグを含む場合、メール本文ではエスケープされる")
    void 組織名のHTMLタグがエスケープされる() {
        listener = newListener();
        String malicious = "<script>alert(1)</script>";
        ProvisioningInvitationIssuedEvent event =
                new ProvisioningInvitationIssuedEvent("invitee@example.com", "token-abc", malicious, 1L);

        listener.onInvitationIssued(event);

        ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
        verify(emailOutboxService).enqueue(captor.capture());
        String body = captor.getValue().payloadVars().get("body");

        assertThat(body).doesNotContain("<script>alert(1)</script>");
        assertThat(body).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    @DisplayName("承諾URLはURLフラグメントにトークンを含み、通常の組織名では本文がそのまま表示される")
    void 通常の組織名は正しくメール本文に反映される() {
        listener = newListener();
        ProvisioningInvitationIssuedEvent event =
                new ProvisioningInvitationIssuedEvent("invitee@example.com", "token-abc", "サンプル組織", 1L);

        listener.onInvitationIssued(event);

        ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
        verify(emailOutboxService).enqueue(captor.capture());
        String body = captor.getValue().payloadVars().get("body");
        String subject = captor.getValue().payloadVars().get("subject");

        assertThat(subject).isEqualTo("【サンプル組織】管理者招待のお知らせ");
        assertThat(body).contains("サンプル組織 の管理者としてご招待いたします。");
        assertThat(body).contains("https://example.test/provisioning/accept#token=token-abc");
    }
}
