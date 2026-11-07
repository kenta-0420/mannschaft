package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 閲覧 API の権限・件数上限と分けて、本人を actor とする現行監査ログを全件取得する。 */
@Service
@RequiredArgsConstructor
public class AuditLogPersonalDataQueryService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> collectPersonalData(Long userId) {
        Objects.requireNonNull(userId, "本人の userId が必要");
        return auditLogRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream().map(log -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", log.getId());
            entry.put("eventType", log.getEventType());
            entry.put("ipAddress", log.getIpAddress());
            entry.put("userAgent", log.getUserAgent());
            entry.put("sessionHash", log.getSessionHash());
            entry.put("metadata", log.getMetadata());
            entry.put("createdAt", log.getCreatedAt());
            return entry;
        }).toList();
    }
}
