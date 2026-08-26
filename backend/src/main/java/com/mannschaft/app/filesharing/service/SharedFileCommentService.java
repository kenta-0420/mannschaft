package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileSharingMapper;
import com.mannschaft.app.filesharing.dto.CommentResponse;
import com.mannschaft.app.filesharing.dto.CreateCommentRequest;
import com.mannschaft.app.filesharing.dto.UpdateCommentRequest;
import com.mannschaft.app.filesharing.entity.SharedFileCommentEntity;
import com.mannschaft.app.filesharing.repository.SharedFileCommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ファイルコメントサービス。ファイルに対するコメントのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharedFileCommentService {

    private final SharedFileCommentRepository commentRepository;
    private final FileSharingMapper fileSharingMapper;
    /** F08.7.1 / 04: 大会フォルダ配下のコメント操作に対する横断認可ゲート（大会以外は no-op）。 */
    private final FolderScopeAccessGuard folderScopeAccessGuard;

    /**
     * ファイルのコメント一覧を取得する。
     *
     * @param fileId ファイルID
     * @return コメントレスポンスリスト
     */
    public List<CommentResponse> listComments(Long fileId) {
        // F08.7.1 / 04 §3: 大会フォルダ配下のファイルコメント一覧は閲覧認可を通す。
        folderScopeAccessGuard.checkFolderViewByFileId(fileId, SecurityUtils.getCurrentUserIdOrNull());
        List<SharedFileCommentEntity> comments = commentRepository.findByFileIdOrderByCreatedAtAsc(fileId);
        return fileSharingMapper.toCommentResponseList(comments);
    }

    /**
     * コメントを作成する。
     *
     * @param fileId  ファイルID
     * @param userId  ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたコメントレスポンス
     */
    @Transactional
    public CommentResponse createComment(Long fileId, Long userId, CreateCommentRequest request) {
        // F08.7.1 / 04 §3: 大会フォルダ配下の非公開ファイルにコメントできるのは閲覧可能な者のみ。
        folderScopeAccessGuard.checkFolderViewByFileId(fileId, userId);
        SharedFileCommentEntity entity = SharedFileCommentEntity.builder()
                .fileId(fileId)
                .userId(userId)
                .body(request.getBody())
                .build();

        SharedFileCommentEntity saved = commentRepository.save(entity);
        log.info("コメント作成: fileId={}, commentId={}", fileId, saved.getId());
        return fileSharingMapper.toCommentResponse(saved);
    }

    /**
     * コメントを更新する。
     *
     * @param commentId コメントID
     * @param request   更新リクエスト
     * @return 更新されたコメントレスポンス
     */
    @Transactional
    public CommentResponse updateComment(Long commentId, UpdateCommentRequest request) {
        SharedFileCommentEntity entity = findCommentOrThrow(commentId);
        // F08.7.1 / 04 §3: 大会フォルダ配下のコメント更新は当該ファイルの閲覧認可を通す。
        folderScopeAccessGuard.checkFolderViewByFileId(entity.getFileId(), SecurityUtils.getCurrentUserIdOrNull());
        entity.updateBody(request.getBody());
        SharedFileCommentEntity saved = commentRepository.save(entity);
        log.info("コメント更新: commentId={}", commentId);
        return fileSharingMapper.toCommentResponse(saved);
    }

    /**
     * コメントを論理削除する。
     *
     * @param commentId コメントID
     */
    @Transactional
    public void deleteComment(Long commentId) {
        SharedFileCommentEntity entity = findCommentOrThrow(commentId);
        // F08.7.1 / 04 §3: 大会フォルダ配下のコメント削除は当該ファイルの閲覧認可を通す。
        folderScopeAccessGuard.checkFolderViewByFileId(entity.getFileId(), SecurityUtils.getCurrentUserIdOrNull());
        entity.softDelete();
        commentRepository.save(entity);
        log.info("コメント削除: commentId={}", commentId);
    }

    /**
     * コメントを取得する。存在しない場合は例外をスローする。
     */
    private SharedFileCommentEntity findCommentOrThrow(Long commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.COMMENT_NOT_FOUND));
    }
}
