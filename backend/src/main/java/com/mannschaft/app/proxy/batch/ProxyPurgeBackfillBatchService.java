package com.mannschaft.app.proxy.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link com.mannschaft.app.proxy.event.ProxyPurgeEventListener} の処理漏れを夜次補正するバッチ
 * （Phase D-6）。
 *
 * <p>毎日 03:00（JST）に実行し、以下の孤児レコードを検出して補正する:</p>
 * <ul>
 *   <li>{@code proxy_input_records}: {@code subject_user_id} が指す {@code users} レコードが
 *       物理削除済みの行を <b>物理削除</b> する</li>
 *   <li>{@code proxy_input_consents}: {@code subject_user_id} が指す {@code users} レコードが
 *       物理削除済みかつ {@code deleted_at IS NULL} の行を <b>論理削除</b> する
 *       （監査証跡保持のため物理削除しない）</li>
 * </ul>
 *
 * <p><b>孤児の発生条件:</b>
 * {@link com.mannschaft.app.gdpr.service.AccountPurgeService} が退会ユーザーを物理削除した後、
 * {@link com.mannschaft.app.proxy.event.ProxyPurgeEventListener} の
 * {@code @TransactionalEventListener(AFTER_COMMIT)} が例外等で処理失敗した場合に発生する。
 * 本バッチはその失敗分を翌日以降に自動補正する。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md}
 * §4 Phase D-6</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyPurgeBackfillBatchService {

    private final ProxyInputRecordRepository proxyInputRecordRepository;
    private final ProxyInputConsentRepository proxyInputConsentRepository;

    /**
     * proxy 孤児補正バッチ。毎日 03:00（JST）に実行する。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>{@link ProxyInputRecordRepository#deleteOrphanBySubjectUserId} で
     *       孤児 {@code proxy_input_records} を一括物理削除する</li>
     *   <li>{@link ProxyInputConsentRepository#logicalDeleteOrphanBySubjectUserId} で
     *       孤児かつ未論理削除の {@code proxy_input_consents} を一括論理削除する</li>
     * </ol>
     *
     * <p>孤児が 0 件の場合も正常終了として扱う（毎日平常運転で 0 件が期待値）。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると論理削除済み代理関連行の物理削除 backfill が進まず、消したはずの代理関係の個人データが残り続ける")
    @BatchEndpoint(
            name = "proxy-purge-backfill-daily",
            description = "AccountPurgedEvent 処理漏れの proxy_input_records / proxy_input_consents を毎日 03:00 に補正する"
    )
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "proxyPurgeBackfillBatch", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void backfill() {
        int deletedRecords = proxyInputRecordRepository.deleteOrphanBySubjectUserId();
        log.info("proxy_input_records 孤児物理削除: {}件", deletedRecords);

        int logicallyDeleted = proxyInputConsentRepository.logicalDeleteOrphanBySubjectUserId();
        log.info("proxy_input_consents 孤児論理削除: {}件", logicallyDeleted);
    }
}
