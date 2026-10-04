package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchRecord;
import com.mannschaft.app.ranch.entity.RanchPointLedgerEntity;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** 本人台帳のみを上限101行のkeysetで読み、署名cursorに次の境界を固定する。 */
@Service
@RequiredArgsConstructor
public class RanchRecordQueryReader {
    private final RanchPointLedgerRepository ledger;
    private final RanchRecordCursorCodec cursors;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public CursorPagedResponse<RanchRecord> page(Long userId, String cursor, int limit) {
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
        List<RanchRecord> data = page.stream().map(this::safeRecord).toList();
        String next = null;
        if (hasNext) {
            RanchPointLedgerEntity last = page.get(page.size() - 1);
            next = cursors.encode(userId, last.getOccurredAt(), last.getId());
        }
        return CursorPagedResponse.of(data,
                new CursorPagedResponse.CursorMeta(next, hasNext, limit));
    }

    private RanchRecord safeRecord(RanchPointLedgerEntity row) {
        // source transport統合時も、認可済みfacadeなしで資料URLや本文を推測しない。
        return new RanchRecord(row.getId(), row.getEntryKind(), null,
                Long.toString(row.getDeltaPoints()), Long.toString(row.getDeltaXp()),
                row.getOccurredAt(), null);
    }
}
