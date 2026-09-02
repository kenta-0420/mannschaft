package com.mannschaft.app.provisioning.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.mail.outbox.EmailTemplateKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * 柱②-2 販促プロビジョニング: ADMIN 招待メールの配送リスナー（{@code AFTER_COMMIT}）。
 *
 * <p>金型: {@code AdminSuccessionNotificationListener} / {@code AuthEmailEventListener}。
 * {@code ProvisioningService} の業務トランザクションが commit された後に非同期
 * （{@code event-pool}）で発火し、{@link EmailOutboxService} へ enqueue する
 * （通知のトランザクション境界番人 対応。業務 TX 内では直接送信しない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProvisioningEmailEventListener {

    @Value("${app.base-url}")
    private String baseUrl;

    private final EmailOutboxService emailOutboxService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "柱②-2 販促プロビジョニングのADMIN招待メール送信。止めると招待相手がトークンを"
                    + "受け取れず承諾フロー自体が開始できないため常時実行する。イベントは再生されないため"
                    + "commit直後に送る必要がある")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvitationIssued(ProvisioningInvitationIssuedEvent event) {
        try {
            String acceptUrl = baseUrl + "/provisioning/accept?token=" + event.plaintextToken();
            emailOutboxService.enqueue(new EmailOutboxRequest(
                    EmailTemplateKind.PROVISIONING_ADMIN_INVITE.name(),
                    "ja",
                    event.inviteEmail(),
                    Map.of(
                            "scopeName", event.scopeName() != null ? event.scopeName() : "",
                            "acceptUrl", acceptUrl
                    ),
                    "provisioning",
                    "provisioning-invite:" + event.plaintextToken().hashCode() + ":" + System.nanoTime(),
                    null,
                    null,
                    null
            ));
            log.info("販促プロビジョニング招待メール enqueue 完了: to={}", event.inviteEmail());
        } catch (Exception e) {
            log.error("販促プロビジョニング招待メールの enqueue に失敗しました: to={}", event.inviteEmail(), e);
        }
    }
}
