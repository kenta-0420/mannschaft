package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Function;

/** rollback 後に管理更新全体を再試行する非 TX 入口。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ErrorReportManagementService {
    private final ErrorReportManagementTx managementTx;
    private final ErrorReportWriteRetry retry;

    <T> T update(Long id, Function<ErrorReportEntity, T> action) {
        return retry.management(attempt -> managementTx.update(List.of(id), true, attempt,
                reports -> action.apply(reports.getFirst())));
    }

    <T> T updateMany(List<Long> ids, Function<List<ErrorReportEntity>, T> action) {
        return retry.management(attempt -> managementTx.update(ids, false, attempt, action));
    }
}
