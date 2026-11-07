package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchPolicyPublicationRequest;
import com.mannschaft.app.ranch.dto.RanchPolicySummary;
import com.mannschaft.app.ranch.entity.RanchRewardPolicyEntity;
import com.mannschaft.app.ranch.repository.RanchRewardPolicyRepository;
import com.mannschaft.app.ranch.reward.RanchRewardPolicyCodec;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HexFormat;

/** 四源と配送値は保存snapshotから取得し、管理履歴を100+1件に制限する。 */
@Service
@RequiredArgsConstructor
public class RanchPolicyAdminReader {
    private final RanchRewardPolicyRepository policies;
    private final RanchAdminCursorCodec cursors;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CursorPagedResponse<RanchPolicySummary> read(Long actorId, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new BusinessException(RanchErrorCode.RANCH_006);
        Long before = cursors.decode(actorId, RanchAdminCursorCodec.Kind.POLICY, cursor);
        var rows = policies.history(before, PageRequest.of(0, limit + 1));
        boolean hasNext = rows.size() > limit;
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        String next = hasNext ? cursors.encode(actorId, RanchAdminCursorCodec.Kind.POLICY,
                selected.get(selected.size() - 1).getVersionNumber()) : null;
        return CursorPagedResponse.of(selected.stream().map(this::summary).toList(),
                new CursorPagedResponse.CursorMeta(next, hasNext, limit));
    }

    private RanchPolicySummary summary(RanchRewardPolicyEntity row) {
        if (row.getSchemaVersion() != 1) throw new BusinessException(RanchErrorCode.RANCH_008);
        try {
            var saved = RanchRewardPolicyCodec.decode(row.getId(), row.getVersionNumber(), row.getEffectiveAt(),
                    row.getSettingsJson(), row.getContentHash(), json);
            var sources = Arrays.stream(RanchRewardSourceType.values()).map(type -> {
                var rule = saved.sources().get(type);
                return new RanchPolicyPublicationRequest.SourceRule(type, rule.enabled(), Long.toString(rule.amountPoints()),
                        Math.toIntExact(rule.countLimit()));
            }).toList();
            var value = saved.delivery();
            var settings = new RanchPolicyPublicationRequest(saved.effectiveAt(), saved.enabled(), Long.toString(saved.globalCap()),
                    sources, new RanchPolicyPublicationRequest.Delivery(value.batchSize(), value.leaseSeconds(), value.maxAttempts(),
                    value.initialBackoffSeconds(), value.maxBackoffSeconds()), saved.reasonCode());
            return new RanchPolicySummary(row.getId(), Long.toString(row.getVersionNumber()), row.getEffectiveAt(), settings,
                    HexFormat.of().formatHex(row.getContentHash()), row.getPublishedAt(), row.getPublishedBy().toString());
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new BusinessException(RanchErrorCode.RANCH_008, invalid);
        }
    }
}
