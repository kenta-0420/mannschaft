package com.mannschaft.app.moduleevent.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 再開監査の本人データ取得と強匿名化の窓口。 */
@Component
public class ModuleEventResumeAuditPrivacyApi {
    public record PersonalData(UUID id, UUID publicationId, String reason, Instant createdAt) { }

    public List<PersonalData> collectPersonalData(Long actorUserId) {
        throw new UnsupportedOperationException("未実装");
    }

    public void redactReasonsForPurgedUser(Long actorUserId) {
        throw new UnsupportedOperationException("未実装");
    }
}
