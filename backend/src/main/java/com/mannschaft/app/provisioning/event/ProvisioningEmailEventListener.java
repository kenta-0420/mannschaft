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
            // 検分 P0 根治: トークンを URL フラグメント（#token=...）に格納する。
            // フラグメントはブラウザからサーバーへ送信されないため、アクセスログ・Referer
            // ヘッダーには一切載らない（クエリパラメータ ?token=... は両方に載り得る）。
            // FE はフラグメントを JS で読み取り、確定設計どおり accept API へ POST ボディで渡す。
            // 同様に平文トークンをメール本文へ載せる必要がある role.InviteService#inviteUrl /
            // auth.AuthEmailEventListener#verifyUrl 等の既存招待メールはパス/クエリ形式のままだが、
            // それらはメール内リンクという配送経路自体が本質的に平文を含む点で本 PR の対象と同型であり、
            // 本 PR ではプロビジョニング招待のみ根治対象とする（要件どおり URL 形式の確定のみ）。
            //
            // 検分第2巡 P1-1 裁定: 受け側 FE 画面（frontend/app/pages/provisioning/accept.vue）は
            // 柱②-4 の別 PR で実装する（段階着地・許容済み）。"/provisioning/accept" というパスは
            // BE/FE 間の確定契約であり、②-4 の実装時にこの文字列と齟齬なく一致させること
            // （変更する場合は BE 側のこの1箇所も同時に更新する）。
            String acceptUrl = baseUrl + "/provisioning/accept#token=" + event.plaintextToken();
            String scopeName = event.scopeName() != null ? event.scopeName() : "";

            // 検分第2巡 P1-2 根治: EmailOutboxServiceImpl#renderTemplate のスルー方式
            // （PROVISIONING_ADMIN_INVITE を通す case を新設）に合わせ、件名・本文はここで
            // 組み立てて payloadVars の subject/body として渡す（金型: DataExportService
            // #sendCompletionEmail）。renderTemplate 側に case を追加しただけでは、そこが
            // vars から subject/body を取り出すだけなので、呼び出し元での組み立てが必須。
            String subject = "【" + scopeName + "】管理者招待のお知らせ";
            String htmlBody = "<p>" + scopeName + " の管理者としてご招待いたします。</p>"
                    + "<p>以下のリンクから招待を承諾してください（有効期限は発行から7日間です）。</p>"
                    + "<p><a href=\"" + acceptUrl + "\">" + acceptUrl + "</a></p>";

            emailOutboxService.enqueue(new EmailOutboxRequest(
                    EmailTemplateKind.PROVISIONING_ADMIN_INVITE.name(),
                    "ja",
                    event.inviteEmail(),
                    Map.of("subject", subject, "body", htmlBody),
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
