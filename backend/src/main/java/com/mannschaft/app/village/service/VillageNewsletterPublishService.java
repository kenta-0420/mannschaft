package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.VillageNewsletterSendLogEntity;
import com.mannschaft.app.village.repository.VillageNewsletterIssueRepository;
import com.mannschaft.app.village.repository.VillageNewsletterSendLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 村ニュースレター号の「配信確定」を村ドメインのトランザクションで行うサービス（F17.1 ②-3・設計書 §7.5）。
 *
 * <p>配信バッチ（{@code VillageNewsletterDispatchBatchService}）は通知（notification 越境・best-effort）を
 * トランザクション外で先に済ませ、その結果得た件数を本メソッドへ渡す。本メソッドの {@code @Transactional} は
 * <b>村ドメイン（号・送信ログ・監査）のみ</b>に閉じ、他ドメインを読み書きしないため越境トランザクション
 * （番人 D-3）にならない。号の {@code markPublished}（FROZEN→PUBLISHED）は村ドメイン内の書き込みである。</p>
 *
 * <h2>なぜ配信バッチと別 Bean なのか（自己呼び出しプロキシ癖の回避）</h2>
 * <p>Spring の {@code @Transactional} は<b>プロキシ経由の呼び出し</b>でしか有効化されない。配信バッチが
 * 自クラス内の {@code @Transactional} メソッドを呼ぶと自己呼び出しとなりプロキシが効かず、トランザクションが
 * 開始されない（既存 {@code dispatchSingleNewsletter} が抱えていた潜在癖）。本サービスを独立 Bean に切り出し、
 * バッチから<b>プロキシ経由で</b>呼ぶことで、号の PUBLISHED 化・送信ログ・監査が確実に 1 トランザクションで
 * コミットされることを保証する。通知失敗（best-effort）は本トランザクションの外なので巻き込まない（§7.5）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageNewsletterPublishService {

    private final VillageNewsletterIssueRepository issueRepository;
    private final VillageNewsletterSendLogRepository sendLogRepository;
    private final AuditLogService auditLogService;

    /**
     * 号を配信完了（PUBLISHED）にし、送信ログと監査ログを村ドメインのトランザクションで確定する。
     *
     * @param issue          配信対象の凍結済み号（呼び出し元がトランザクション外でロード済み）
     * @param recipientCount 配信対象者数（opt-out 除外後）
     * @param successCount   通知に成功した件数
     * @param failureCount   通知に失敗した件数（AC-13: 1 件失敗しても号は PUBLISHED 化する）
     */
    @Transactional
    public void publishIssue(VillageNewsletterIssueEntity issue,
                             int recipientCount, int successCount, int failureCount) {
        LocalDateTime now = LocalDateTime.now();

        // 号を配信完了へ（FROZEN → PUBLISHED）。detached entity のため save で merge して確実に反映する。
        issue.markPublished(now);
        issueRepository.save(issue);

        // 送信ログ（号単位・issue_id を必ずセット。②-1 で追加された号紐づけ）。
        sendLogRepository.save(VillageNewsletterSendLogEntity.builder()
                .newsletterId(issue.getNewsletterId())
                .issueId(issue.getId())
                .sentAt(now)
                .recipientCount(recipientCount)
                .successCount(successCount)
                .failureCount(failureCount)
                .build());

        // 監査ログ（既存 VILLAGE_NEWSLETTER_SENT を流用・8 引数まで null + JSON）。
        auditLogService.record(
                AuditEventType.VILLAGE_NEWSLETTER_SENT.name(),
                null, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + issue.getVillageId()
                        + "\",\"issueId\":\"" + issue.getId()
                        + "\",\"newsletterId\":\"" + issue.getNewsletterId()
                        + "\",\"recipientCount\":" + recipientCount
                        + ",\"successCount\":" + successCount
                        + ",\"failureCount\":" + failureCount + "}"
        );
        log.info("ニュースレター号を配信・公開: villageId={} issueId={} recipients={} success={} failure={}",
                issue.getVillageId(), issue.getId(), recipientCount, successCount, failureCount);
    }
}
