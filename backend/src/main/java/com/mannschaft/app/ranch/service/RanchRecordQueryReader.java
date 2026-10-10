package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchRecord;
import com.mannschaft.app.ranch.entity.RanchPointLedgerEntity;
import com.mannschaft.app.ranch.entity.RanchRewardDecisionEntity;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.repository.RanchRewardDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.HashMap;

/** 本人台帳のみを上限101行のkeysetで読み、署名cursorに次の境界を固定する。 */
@Service
@RequiredArgsConstructor
public class RanchRecordQueryReader {
    private final RanchPointLedgerRepository ledger;
    private final RanchRewardDecisionRepository decisions;
    private final RanchRecordCursorCodec cursors;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public CursorPagedResponse<RanchRecord> page(Long userId, String cursor, int limit) {
        return readPage(userId, cursor, limit).page();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public ReadPage readPage(Long userId, String cursor, int limit) {
        Objects.requireNonNull(userId);
        if (limit < 1 || limit > 100) {
            throw new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
        }
        RanchRecordCursorCodec.Position start = cursor == null
                ? null : cursors.decode(userId, cursor);
        List<RanchPointLedgerEntity> rows = ledger.pageForUser(userId,
                start == null ? null : start.occurredAt(),
                start == null ? null : start.id(), PageRequest.of(0, limit + 1));
        boolean hasNext = rows.size() > limit;
        List<RanchPointLedgerEntity> page = hasNext ? rows.subList(0, limit) : rows;
        List<UUID> decisionIds = page.stream().map(RanchPointLedgerEntity::getDecisionId)
                .filter(Objects::nonNull).distinct().toList();
        Map<UUID, RanchRewardDecisionEntity> byId = new HashMap<>();
        decisions.findAllById(decisionIds).forEach(row -> byId.put(row.getId(), row));
        Map<UUID, RanchRecordSourceRef> refs = new HashMap<>();
        List<RanchRecord> data = page.stream().map(row -> {
            RanchRewardDecisionEntity decision = byId.get(row.getDecisionId());
            if (decision == null || !"REWARD".equals(row.getEntryKind())
                    || !userId.equals(decision.getUserId())
                    || !row.getOwnerId().equals(decision.getOwnerId())) {
                return safeRecord(row, null);
            }
            RanchRecordSourceRef.from(decision).ifPresent(ref -> refs.put(row.getId(), ref));
            return safeRecord(row, decision);
        }).toList();
        String next = null;
        if (hasNext) {
            RanchPointLedgerEntity last = page.get(page.size() - 1);
            next = cursors.encode(userId, last.getOccurredAt(), last.getId());
        }
        return new ReadPage(CursorPagedResponse.of(data,
                new CursorPagedResponse.CursorMeta(next, hasNext, limit)), Map.copyOf(refs));
    }

    private RanchRecord safeRecord(RanchPointLedgerEntity row, RanchRewardDecisionEntity decision) {
        // 源の認可はこの Ranch TX の外で再判定する。ここでは技術的な源種別だけを表示する。
        return new RanchRecord(row.getId(), row.getEntryKind(),
                decision == null ? null : decision.getSourceType().name(),
                Long.toString(row.getDeltaPoints()), Long.toString(row.getDeltaXp()),
                row.getOccurredAt(), null);
    }

    public record ReadPage(CursorPagedResponse<RanchRecord> page,
                           Map<UUID, RanchRecordSourceRef> sourceRefs) { }
}
