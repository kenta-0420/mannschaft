package com.mannschaft.app.moduleevent.admin;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.moduleevent.ModuleEventAttemptStatus;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventQuarantineResponse;
import jakarta.persistence.EntityManager;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 隔離一覧と理由付き再開を、現在の認証主体に限定する内部サービス。
 * 公式JPA本表型はpackage-privateのため、Entityを公開せず固定列のnative SQLを使う。
 * 再開のCASと専用監査は同じドメイン内のTXで確定する。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModuleEventAdminService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_REASON_LENGTH = 500;
    private static final String QUARANTINED = ModuleEventAttemptStatus.QUARANTINED.name();
    private static final String QUARANTINE_FROM = """
            FROM event_publication_attempts a
            JOIN event_publication p ON p.id = a.publication_id
            WHERE a.status = ?
            """;
    private static final String QUARANTINE_QUERY = """
            SELECT p.id, p.event_type, p.listener_id, p.publication_date,
                   a.last_attempt_at, a.quarantined_at, a.retry_count, a.failure_code
            """ + QUARANTINE_FROM + """
            ORDER BY a.quarantined_at DESC, p.id ASC LIMIT ? OFFSET ?
            """;
    private static final String RESUME_QUERY = """
            UPDATE event_publication_attempts a
            JOIN event_publication p ON p.id = a.publication_id
            SET a.status = ?, a.retry_count = 0, a.claim_token = ?,
                a.last_attempt_at = NULL, a.lease_until = NULL,
                a.quarantined_at = NULL, a.failure_code = NULL,
                a.version = a.version + 1, a.updated_at = ?
            WHERE a.publication_id = ? AND a.status = ? AND a.version = ?
              AND p.completion_date IS NULL
            """;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public PagedResponse<ModuleEventQuarantineResponse> list(String status, int page, int size) {
        if ((status != null && !QUARANTINED.equals(status)) || page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) " + QUARANTINE_FROM, Long.class, QUARANTINED);
        long offset = (long) page * size;
        List<ModuleEventQuarantineResponse> data = jdbc.query(QUARANTINE_QUERY, (row, index) -> {
            Calendar utc = utcCalendar();
            return new ModuleEventQuarantineResponse(uuid(row.getBytes("id")), row.getString("event_type"),
                    row.getString("listener_id"), appDateTime(row, "publication_date", utc),
                    appDateTime(row, "last_attempt_at", utc), appDateTime(row, "quarantined_at", utc),
                    row.getInt("retry_count"), row.getString("failure_code"));
        }, QUARANTINED, size, offset);
        int totalPages = Math.toIntExact(total / size + (total % size == 0 ? 0 : 1));
        return PagedResponse.of(data, new PagedResponse.PageMeta(total, page, size, totalPages));
    }

    /** CASの勝者だけが監査を追記する。監査保存・flushの障害も再開と一緒にrollbackする。 */
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Transactional
    public void resume(UUID publicationId, String reason) {
        String normalizedReason = reason == null ? null : reason.trim();
        if (publicationId == null || normalizedReason == null || normalizedReason.isBlank()
                || normalizedReason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        Long actorUserId = SecurityUtils.getCurrentUserId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.completion_date, a.id AS attempt_id, a.status, a.version AS attempt_version
                FROM event_publication p
                LEFT JOIN event_publication_attempts a ON a.publication_id = p.id
                WHERE p.id = ?
                """, bytes(publicationId));
        if (rows.isEmpty()) {
            throw new BusinessException(CommonErrorCode.COMMON_005);
        }
        Map<String, Object> current = rows.getFirst();
        if (current.get("attempt_id") == null || current.get("completion_date") != null
                || !QUARANTINED.equals(current.get("status"))) {
            throw new BusinessException(CommonErrorCode.COMMON_003);
        }
        long version = ((Number) current.get("attempt_version")).longValue();
        Instant now = Instant.now();
        UUID claimToken = UuidV7.generate();
        int changed = jdbc.update(connection -> {
            var statement = connection.prepareStatement(RESUME_QUERY);
            statement.setString(1, ModuleEventAttemptStatus.PENDING.name());
            statement.setBytes(2, bytes(claimToken));
            // 直接JDBCはUTC壁時計、JPAのLocalDateTimeはSERVER_ZONE壁時計へ同じ瞬間を変換する。
            statement.setTimestamp(3, Timestamp.from(now), utcCalendar());
            statement.setBytes(4, bytes(publicationId));
            statement.setString(5, QUARANTINED);
            statement.setLong(6, version);
            return statement;
        });
        if (changed != 1) {
            throw new BusinessException(CommonErrorCode.COMMON_003);
        }
        entityManager.persist(ModuleEventResumeAuditEntity.builder().publicationId(publicationId)
                .actorUserId(actorUserId).reason(normalizedReason)
                .createdAt(LocalDateTime.ofInstant(now, UserZoneLocalDateTimeParser.SERVER_ZONE)).build());
        entityManager.flush();
    }

    private static Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone(ZoneOffset.UTC));
    }

    private static LocalDateTime appDateTime(ResultSet row, String column, Calendar utc) throws SQLException {
        Timestamp value = row.getTimestamp(column, utc);
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), UserZoneLocalDateTimeParser.SERVER_ZONE);
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
