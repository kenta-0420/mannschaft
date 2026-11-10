package com.mannschaft.app.config.websocket;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * PROTOCOL_ERRORを進行中送信のdrain後まで保留し、既存schedulerの期限でもcloseを試みる。
 * fatal属性をclose入口で確定し、受信側gateと同一sessionの終端状態を共有する。
 * 実delegate、既存buffer/time limit、message callbackを維持する。
 */
public class DeferredProtocolCloseWebSocketSession extends ConcurrentWebSocketSessionDecorator {
    private static final Log LOG = LogFactory.getLog(DeferredProtocolCloseWebSocketSession.class);
    private final Object monitor = new Object();
    @Nullable private final TaskScheduler scheduler;
    private int activeSends;
    private boolean closeRequested;
    @Nullable private CloseStatus priorityClose;
    private boolean deadlineReserved;
    private boolean deadlineFired;
    private boolean sendsDrained;
    @Nullable private ScheduledFuture<?> deadlineFuture;

    public DeferredProtocolCloseWebSocketSession(WebSocketSession delegate, int sendTimeLimit,
                                                int bufferSizeLimit, @Nullable TaskScheduler scheduler) {
        super(delegate, sendTimeLimit, bufferSizeLimit);
        this.scheduler = scheduler;
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
        synchronized (monitor) {
            // terminal要求後の新規送信はbase closeInProgressと同じくdropする。
            if (closeRequested) return;
            activeSends++;
        }
        Throwable primaryFailure = null;
        try {
            super.sendMessage(message);
        } catch (IOException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                finishSend();
            } catch (IOException | RuntimeException | Error closeFailure) {
                if (primaryFailure == null) throw closeFailure;
                if (primaryFailure != closeFailure) primaryFailure.addSuppressed(closeFailure);
            }
        }
    }

    @Override
    public void close(CloseStatus status) throws IOException {
        if (CloseStatus.PROTOCOL_ERROR.equals(status) && getAttributes() != null) {
            getAttributes().put(FatalStompSessionGate.FATAL_SESSION_ATTRIBUTE, Boolean.TRUE);
        }
        boolean reserve = false;
        boolean immediate;
        ScheduledFuture<?> cancel = null;
        synchronized (monitor) {
            closeRequested = true;
            if (!CloseStatus.PROTOCOL_ERROR.equals(status) && priorityClose == null) priorityClose = status;
            immediate = priorityClose != null || activeSends == 0 || getSendTimeLimit() <= 0;
            if (immediate) {
                sendsDrained = activeSends == 0;
                cancel = deadlineFuture;
                deadlineFuture = null;
            } else if (!deadlineReserved) {
                deadlineReserved = true;
                reserve = true;
            }
        }
        cancelOutsideMonitor(cancel);
        if (immediate) attemptCurrentClose();
        else if (reserve) scheduleDeadline();
    }

    private void finishSend() throws IOException {
        boolean attempt;
        ScheduledFuture<?> cancel = null;
        synchronized (monitor) {
            activeSends--;
            attempt = closeRequested && activeSends == 0;
            if (attempt) {
                sendsDrained = true;
                cancel = deadlineFuture;
                deadlineFuture = null;
            }
        }
        cancelOutsideMonitor(cancel);
        // deadlineがsuper.closeを試した後でも必ず再試行する。復帰だけでphysical完了を認定しない。
        if (attempt) attemptCurrentClose();
    }

    private void scheduleDeadline() throws IOException {
        if (scheduler == null) {
            attemptCurrentClose();
            return;
        }
        ScheduledFuture<?> future;
        try {
            future = scheduler.schedule(this::onDeadline, Instant.now().plusMillis(getSendTimeLimit()));
        } catch (RuntimeException rejectedOrStopped) {
            LOG.warn("PROTOCOL_ERROR close期限の予約拒否 type=" + rejectedOrStopped.getClass().getSimpleName());
            attemptCurrentClose();
            return;
        }
        if (future == null) {
            LOG.warn("PROTOCOL_ERROR close期限の予約結果がnullのため即時closeを試みる");
            attemptCurrentClose();
            return;
        }
        boolean late;
        synchronized (monitor) {
            late = sendsDrained || priorityClose != null || deadlineFired;
            if (!late) deadlineFuture = future;
        }
        if (late) cancelOutsideMonitor(future);
    }

    private void onDeadline() {
        synchronized (monitor) {
            deadlineFired = true;
            deadlineFuture = null;
        }
        try {
            attemptCurrentClose();
        } catch (IOException failure) {
            LOG.warn("PROTOCOL_ERROR close期限でのclose失敗 type=" + failure.getClass().getSimpleName());
        }
    }

    private void attemptCurrentClose() throws IOException {
        CloseStatus desired;
        synchronized (monitor) {
            desired = priorityClose == null ? CloseStatus.PROTOCOL_ERROR : priorityClose;
        }
        // base closeLock.tryLockが空振りしても永久done flagを立てない。
        // deadlineとlast active senderの両方が試行でき、一回性はbase closeInProgressに委譲する。
        super.close(desired);
    }

    private static void cancelOutsideMonitor(@Nullable ScheduledFuture<?> future) {
        if (future == null) return;
        try {
            future.cancel(false);
        } catch (RuntimeException failure) {
            // cancellation異常で必要なclose試行を飛ばさない。異常は可観測に残す。
            LOG.warn("PROTOCOL_ERROR close期限のcancel失敗 type=" + failure.getClass().getSimpleName());
        }
    }
}
