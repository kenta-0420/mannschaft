package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchCareRuleSummary;
import com.mannschaft.app.ranch.entity.RanchCareRuleEntity;
import com.mannschaft.app.ranch.repository.RanchCareRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HexFormat;

/** version keysetと100+1 sentinelで管理一覧を有限に読む。 */
@Service
@RequiredArgsConstructor
public class RanchCareRuleAdminReader {
    private final RanchCareRuleRepository rules;
    private final RanchAdminCursorCodec cursors;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CursorPagedResponse<RanchCareRuleSummary> read(Long actorId, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new BusinessException(RanchErrorCode.RANCH_006);
        Long before = cursors.decode(actorId, RanchAdminCursorCodec.Kind.CARE, cursor);
        var rows = rules.history(before, PageRequest.of(0, limit + 1));
        boolean hasNext = rows.size() > limit;
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        var data = selected.stream().map(this::summary).toList();
        String next = hasNext ? cursors.encode(actorId, RanchAdminCursorCodec.Kind.CARE,
                selected.get(selected.size() - 1).getVersionNumber()) : null;
        return CursorPagedResponse.of(data, new CursorPagedResponse.CursorMeta(next, hasNext, limit));
    }

    private RanchCareRuleSummary summary(RanchCareRuleEntity row) {
        return new RanchCareRuleSummary(row.getId(), Long.toString(row.getVersionNumber()), row.getEffectiveAt(),
                Long.toString(row.getAmountXp()), Long.toString(row.getWeeklyCapXp()), Long.toString(row.getJuvenileXp()),
                Long.toString(row.getAdultXp()), HexFormat.of().formatHex(row.getContentHash()), row.getPublishedAt(),
                row.getPublishedBy().toString());
    }
}