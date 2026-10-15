package com.mannschaft.app.moderation.service;

import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.dto.InternalNoteResponse;
import com.mannschaft.app.moderation.entity.ReportInternalNoteEntity;
import com.mannschaft.app.moderation.repository.ReportInternalNoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 通報内部メモサービス。管理者間の内部コメントCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportInternalNoteService {

    private final ReportInternalNoteRepository noteRepository;
    private final ModerationExtMapper mapper;

    /**
     * 内部メモを追加する。
     *
     * @param reportId 通報ID
     * @param authorId 作成者ID
     * @param note     メモ内容
     * @return 内部メモレスポンス
     */
    @Transactional
    public InternalNoteResponse addNote(Long reportId, Long authorId, String note) {
        ReportInternalNoteEntity entity = ReportInternalNoteEntity.builder()
                .reportId(reportId)
                .authorId(authorId)
                .note(note)
                .build();

        entity = noteRepository.save(entity);

        log.info("内部メモ追加: id={}, reportId={}, authorId={}", entity.getId(), reportId, authorId);
        return mapper.toInternalNoteResponse(entity);
    }

    /**
     * 通報の内部メモ一覧を取得する。
     *
     * @param reportId 通報ID
     * @return 内部メモレスポンス一覧
     */
    public List<InternalNoteResponse> getNotes(Long reportId) {
        return mapper.toInternalNoteResponseList(
                noteRepository.findByReportIdOrderByCreatedAtAsc(reportId));
    }
}
