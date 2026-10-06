package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.repository.DiagnosisResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 本人条件付きの時刻・ID順カーソル読取。ranch参加や表示状態は参照しない。 */
@Service
@RequiredArgsConstructor
public class DiagnosisResultListReader {
    private final DiagnosisResultRepository results;
    private final ObjectMapper mapper;
    private final DiagnosisResultCursorCodec cursors;

    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
    public CursorPagedResponse<DiagnosisResultSummary> list(Long userId, DiagnosisMethod method, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new BusinessException(DiagnosisErrorCode.INVALID_INPUT);
        Instant before = null; UUID id = null;
        if (cursor != null) {
            var position = cursors.decode(userId, method, cursor);
            before = position.completedAt(); id = position.id();
        }
        var page = results.findOwnedPage(userId, method, before, id, PageRequest.of(0, limit + 1));
        boolean hasNext = page.size() > limit;
        var selected = page.stream().limit(limit).toList();
        List<DiagnosisResultSummary> summaries = selected.stream().map(result -> {
            try { return mapper.readValue(result.getSummarySnapshot(), DiagnosisResultSummary.class); }
            catch (JsonProcessingException error) { throw new BusinessException(DiagnosisErrorCode.UNAVAILABLE); }
        }).toList();
        String next = null;
        if (hasNext) {
            var last = selected.getLast();
            next = cursors.encode(userId, method, last.getCompletedAt(), last.getId());
        }
        return CursorPagedResponse.of(summaries, new CursorPagedResponse.CursorMeta(next, hasNext, limit));
    }
}
