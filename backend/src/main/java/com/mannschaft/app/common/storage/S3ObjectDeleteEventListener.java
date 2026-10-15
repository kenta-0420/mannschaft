package com.mannschaft.app.common.storage;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * S3オブジェクト削除イベントリスナー。
 * トランザクションコミット後に非同期でS3オブジェクトを削除する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3ObjectDeleteEventListener {

    private final StorageService storageService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると DB から消したファイルの実体が R2 に残り続ける。削除対象のキーはイベントにしか無く、後から辿って消す経路が存在しない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleS3Delete(S3ObjectDeleteEvent event) {
        try {
            storageService.deleteAll(event.s3Keys());
            log.debug("S3オブジェクト削除完了: keys={}", event.s3Keys());
        } catch (Exception e) {
            log.warn("S3オブジェクト削除失敗（リトライなし）: keys={}", event.s3Keys(), e);
        }
    }
}
