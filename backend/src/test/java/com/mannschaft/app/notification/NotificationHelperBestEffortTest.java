package com.mannschaft.app.notification;

import com.mannschaft.app.notification.service.NotificationBulkFanoutService;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.doNothing;
import static org.mockito.BDDMockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationHelper#notifyAllPreAuthorized} の <b>best-effort（非 throw）契約</b>の回帰ガード。
 *
 * <p>一括通知の同期呼び出し元（予定リマインド・アンケート督促/締切延長 等）は、一部受信者の配信失敗が
 * 呼び出し元の業務トランザクションを巻き添えロールバックしないことを前提としている。チャンク単位
 * バルク化後も「チャンク失敗を握って残りのチャンクへ継続し、例外を外へ伝播しない」契約を守ることを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationHelper 一括通知の best-effort 契約")
class NotificationHelperBestEffortTest {

    @Mock
    private NotificationBulkFanoutService bulkFanoutService;

    @InjectMocks
    private NotificationHelper notificationHelper;

    @Test
    @DisplayName("チャンク配信が失敗しても例外を投げず、残りのチャンク配信は継続する")
    void chunkFailureDoesNotThrowAndRemainingChunksContinue() {
        // 600 件 → 500 + 100 の 2 チャンク。1 チャンク目を失敗させ、2 チャンク目が継続することを固定する。
        List<Long> recipients = LongStream.rangeClosed(1, 600).boxed().toList();

        doThrow(new RuntimeException("simulated chunk insert failure"))
                .doNothing()
                .when(bulkFanoutService).insertAndDispatchChunk(
                        anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThatCode(() -> notificationHelper.notifyAllPreAuthorized(
                recipients, "EVENT_CREATED", NotificationPriority.NORMAL,
                "タイトル", "本文", "VILLAGE_EVENT", null,
                NotificationScopeType.SYSTEM, null, "/villages/x", null))
                .as("チャンク失敗は握られ、呼び出し元へ例外は伝播しない（best-effort・業務txを巻き込まない）")
                .doesNotThrowAnyException();

        // 1 チャンク目が失敗しても 2 チャンク目まで試行される（残りの配信は継続する）。
        verify(bulkFanoutService, times(2)).insertAndDispatchChunk(
                anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
