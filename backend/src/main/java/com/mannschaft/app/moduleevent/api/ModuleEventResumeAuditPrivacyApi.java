package com.mannschaft.app.moduleevent.api;

import com.mannschaft.app.moduleevent.admin.ModuleEventResumeAuditPrivacyService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 再開監査の本人データ取得と強匿名化の窓口。 */
@Component
public class ModuleEventResumeAuditPrivacyApi {
    private final ModuleEventResumeAuditPrivacyService service;

    public ModuleEventResumeAuditPrivacyApi(ModuleEventResumeAuditPrivacyService service) {
        this.service = service;
    }
    public record PersonalData(UUID id, UUID publicationId, String reason, Instant createdAt) { }

    /** 本人に結び付く理由再生防止状態。認証状態は含めない。 */
    public record ActorStateData(UUID id, boolean purged) { }

    public List<PersonalData> collectPersonalData(Long actorUserId) {
        return service.collectPersonalData(actorUserId);
    }

    public List<ActorStateData> collectActorStates(Long actorUserId) {
        return service.collectActorStates(actorUserId);
    }

    public void redactReasonsForPurgedUser(Long actorUserId) {
        service.redactReasonsForPurgedUser(actorUserId);
    }

    /** UTC の暦年2年を過ぎた履歴を整理する内部処理。 */
    public int deleteExpired(Instant now) {
        return service.deleteExpired(now);
    }
}
