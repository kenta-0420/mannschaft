package com.mannschaft.app.mail.outbox;

import com.mannschaft.app.admin.dto.EmailOutboxDetailResponse;
import com.mannschaft.app.admin.dto.EmailOutboxMetricsResponse;
import com.mannschaft.app.admin.dto.EmailOutboxSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.UUID;

/** F09.18 Phase 18-d: SYSTEM_ADMIN 向け outbox 管理 API の Service インターフェース。 */
public interface EmailOutboxAdminService {

    Page<EmailOutboxSummaryResponse> listOutbox(String status, String sourceDomain,
            LocalDateTime fromDate, LocalDateTime toDate, Pageable pageable);

    EmailOutboxDetailResponse getOutboxDetail(UUID id, Long operatorUserId,
            String ipAddress, String userAgent);

    void retryDeadLetter(UUID id, Long operatorUserId, String ipAddress, String userAgent);

    void cancelPending(UUID id, Long operatorUserId, String ipAddress, String userAgent);

    EmailOutboxMetricsResponse getMetrics();
}
