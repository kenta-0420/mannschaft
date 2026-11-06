package com.mannschaft.app.schedule.service;

import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.dto.AttendanceResponse;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** gameの対応範囲外・開始前故障は従来回答一回へ戻し、既存本体資格を狭めない。 */
@Service
@RequiredArgsConstructor
public class ScheduleRanchNativeOperationFacade {
    private final ScheduleRanchNativeWriter writer;
    private final UserOperationGuard users;
    private final ScheduleRanchCaptureQueue queue;
    private final ScheduleRanchCaptureTelemetry telemetry;
    public Optional<AttendanceResponse> respond(Long scheduleId,Long actor,AttendanceRequest request,
            boolean impersonated,boolean proxy) {
        if(actor==null || request==null || impersonated || proxy
                || TransactionSynchronizationManager.isActualTransactionActive()) return Optional.empty();
        var started=new AtomicBoolean();var committed=new AtomicReference<ScheduleRanchNativeWriter.Outcome>();
        ScheduleRanchNativeWriter.Outcome saved;
        try {
            saved=users.withActiveUser(actor,() -> {
                started.set(true);var outcome=writer.respond(scheduleId,actor,request);committed.set(outcome);return outcome;
            });
        } catch(RuntimeException failure) {
            if(committed.get()!=null) {
                telemetry.lost(ScheduleRanchCaptureTelemetry.Reason.POST_NATIVE_FAILURE);
                return Optional.of(committed.get().response());
            }
            if(started.get()) throw failure;
            telemetry.lost(ScheduleRanchCaptureTelemetry.Reason.UNSUPPORTED_CAPTURE);return Optional.empty();
        }
        queue.offer(saved.capture());return Optional.of(saved.response());
    }
}
