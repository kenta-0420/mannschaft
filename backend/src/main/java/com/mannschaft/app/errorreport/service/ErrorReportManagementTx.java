package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** 管理更新のロック取得と業務変更を同じ REQUIRED TX に閉じる。 */
@Service
@RequiredArgsConstructor
public class ErrorReportManagementTx {
    private final ErrorReportRepository repository;
    private final ErrorReportIngestHook hook;

    @Transactional
    <T> T update(List<Long> ids, boolean requireAll, ErrorReportWriteAttempt attempt,
                        Function<List<ErrorReportEntity>, T> action) {
        attempt.begin();
        List<Target> targets = new ArrayList<>();
        for (Long id : ids.stream().distinct().toList()) {
            var hash = repository.findErrorHashById(id);
            if (hash.isPresent()) targets.add(new Target(hash.get(), id));
            else if (requireAll) throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND);
        }
        targets.sort(Comparator.comparing(Target::hash).thenComparing(Target::id));
        for (String hash : targets.stream().map(Target::hash).distinct().toList()) {
            repository.lockHashRows(hash);
            hook.afterManagementLock(hash);
        }
        List<ErrorReportEntity> reports = new ArrayList<>();
        for (Target target : targets) {
            var report = repository.findByIdForUpdate(target.id());
            if (report.isPresent()) {
                hook.afterManagementRead(target.hash());
                reports.add(report.get());
            } else if (requireAll) throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND);
        }
        return action.apply(reports);
    }

    private record Target(String hash, Long id) { }
}
