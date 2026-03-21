package com.mannschaft.app.circulation.service;

import com.mannschaft.app.circulation.CirculationErrorCode;
import com.mannschaft.app.circulation.CirculationMapper;
import com.mannschaft.app.circulation.dto.CommentResponse;
import com.mannschaft.app.circulation.dto.CreateCommentRequest;
import com.mannschaft.app.circulation.dto.UpdateCommentRequest;
import com.mannschaft.app.circulation.entity.CirculationCommentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.repository.CirculationCommentRepository;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回覧コメントサービス。回覧文書に対するコメントのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CirculationCommentService {

    private final CirculationDocumentRepository documentRepository;
    private final CirculationCommentRepository commentRepository;
    private final CirculationMapper circulationMapper;

    /**
     * コメント一覧をページング取得する。
     *
     * @param documentId 文書ID
     * @param pageable   ページング情報
     * @return コメントレスポンスのページ
     */
    public Page<CommentResponse> listComments(Long documentId, Pageable pageable) {
        Page<CirculationCommentEntity> page =
                commentRepository.findByDocumentIdOrderByCreatedAtAsc(documentId, pageable);
        return page.map(circulationMapper::toCommentResponse);
    }

    /**
     * コメントを作成する。
     *
     * @param documentId 文書ID
     * @param userId     ユーザーID
     * @param request    作成リクエスト
     * @return 作成されたコメントレスポンス
     */
    @Transactional
    public CommentResponse createComment(Long documentId, Long userId, CreateCommentRequest request) {
        CirculationDocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.DOCUMENT_NOT_FOUND));

        CirculationCommentEntity comment = CirculationCommentEntity.builder()
                .documentId(documentId)
                .userId(userId)
                .body(request.getBody())
                .build();

        CirculationCommentEntity saved = commentRepository.save(comment);
        document.incrementCommentCount();
        documentRepository.save(document);

        log.info("コメント作成: documentId={}, commentId={}", documentId, saved.getId());
        return circulationMapper.toCommentResponse(saved);
    }

    /**
     * コメントを更新する。
     *
     * @param documentId 文書ID
     * @param commentId  コメントID
     * @param userId     ユーザーID
     * @param request    更新リクエスト
     * @return 更新されたコメントレスポンス
     */
    @Transactional
    public CommentResponse updateComment(Long documentId, Long commentId, Long userId,
                                         UpdateCommentRequest request) {
        CirculationCommentEntity comment = findCommentOrThrow(documentId, commentId);

        if (!comment.isOwnedBy(userId)) {
            throw new BusinessException(CirculationErrorCode.COMMENT_NOT_OWNED);
        }

        comment.updateBody(request.getBody());
        CirculationCommentEntity saved = commentRepository.save(comment);

        log.info("コメント更新: documentId={}, commentId={}", documentId, commentId);
        return circulationMapper.toCommentResponse(saved);
    }

    /**
     * コメントを論理削除する。
     *
     * @param documentId 文書ID
     * @param commentId  コメントID
     * @param userId     ユーザーID
     */
    @Transactional
    public void deleteComment(Long documentId, Long commentId, Long userId) {
        CirculationCommentEntity comment = findCommentOrThrow(documentId, commentId);

        if (!comment.isOwnedBy(userId)) {
            throw new BusinessException(CirculationErrorCode.COMMENT_NOT_OWNED);
        }

        comment.softDelete();
        commentRepository.save(comment);

        CirculationDocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.DOCUMENT_NOT_FOUND));
        document.decrementCommentCount();
        documentRepository.save(document);

        log.info("コメント削除: documentId={}, commentId={}", documentId, commentId);
    }

    /**
     * コメントを取得する。存在しない場合は例外をスローする。
     */
    private CirculationCommentEntity findCommentOrThrow(Long documentId, Long commentId) {
        return commentRepository.findByIdAndDocumentId(commentId, documentId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.COMMENT_NOT_FOUND));
    }
}
