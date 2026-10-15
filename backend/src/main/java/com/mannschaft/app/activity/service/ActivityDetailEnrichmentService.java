package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.dto.ActivityParticipantResponse;
import com.mannschaft.app.activity.dto.ActivityRecordResponse;
import com.mannschaft.app.activity.dto.ActivityTemplateResponse;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.common.NameResolverService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/** actual詳細の表示用関連情報。metadata経路はこの取得を呼ばない。 */
@Service
@RequiredArgsConstructor
public class ActivityDetailEnrichmentService {
    private final ActivityParticipantRepository participants;
    private final ActivityTemplateService templates;
    private final NameResolverService names;

    public record Enrichment(String scopePublicId, List<ActivityParticipantResponse> participants,
                             List<ActivityTemplateResponse.TemplateFieldResponse> templateFields) {}

    public String scopePublicId(String type, Long id) {
        return names.resolveScopeSlug(type, id);
    }

    public Enrichment enrich(ActivityRecordResponse record) {
        var rows = participants.findByActivityResultIdOrderByCreatedAtAsc(record.getId());
        var displayNames = names.resolveUserDisplayNames(rows.stream().map(p -> p.getUserId()).toList());
        var values = rows.stream().map(p -> new ActivityParticipantResponse(p.getId(), p.getUserId(),
                displayNames.get(p.getUserId()), null, p.getRoleLabel(), p.getCreatedAt())).toList();
        var fields = record.getTemplateId() == null ? List.<ActivityTemplateResponse.TemplateFieldResponse>of()
                : templates.fieldsIfPresent(record.getTemplateId(),
                com.mannschaft.app.activity.ActivityScopeType.valueOf(record.getScopeType()), record.getScopeId());
        return new Enrichment(scopePublicId(record.getScopeType(), record.getScopeId()), values, fields);
    }
}
