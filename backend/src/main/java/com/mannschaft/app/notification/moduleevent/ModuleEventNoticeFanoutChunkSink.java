package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.api.ModuleEventNoticeOutboxApi;
import com.mannschaft.app.notification.fanout.FanoutChunkSink;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

/** 通知 job ロックと本体・archive の current read で重複を防ぐ。先行試練用の未実装骨格。 */
@Component
@RequiredArgsConstructor
public class ModuleEventNoticeFanoutChunkSink implements FanoutChunkSink {
    private final ModuleEventNoticeOutboxApi api;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final NotificationDispatchService dispatch;
    @Override public String notificationType() { return "MODULE_EVENT_NOTICE"; }
    @Override public ChunkResult processChunk(UUID jobId, Long noticeRef, List<Long> userIds) {
        return new ChunkResult(0, false);
    }
    @Override public void finish(UUID jobId, Long noticeRef) { }
}
