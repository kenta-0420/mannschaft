package com.mannschaft.app.circulation.service;

import com.mannschaft.app.circulation.CirculationErrorCode;
import com.mannschaft.app.circulation.entity.CirculationCommentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.common.BusinessException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 回覧板ドメインのうち「本人性」に基づく認可判定を一元化するガード。
 *
 * <p>回覧板の認可は<b>受信者 ACL</b> に従う（設計書 {@code docs/features/F05.2_circular.md}、
 * {@code docs/features/F00_content_visibility_resolver.md} §12.3.1）。文書へ到達できるのは
 * 作成者または受信者であり、押印系の操作は<b>当該文書に登録された受信者本人</b>のみ、
 * コメントの編集・削除は<b>当該文書に属するコメントの投稿者本人</b>のみが行える。</p>
 *
 * <p>判定はすべて<b>対象エンティティを取得したうえで、そのエンティティが属する文書</b>で行う。
 * パス変数の文書 ID は照合の対象であって判定の根拠にはしない。これにより、別文書に属する
 * 子リソースの識別子を差し込む経路を構造的に塞ぐ。</p>
 *
 * <p>スコープ管理者権限を要する操作（文書のライフサイクル管理・あて先の増減・添付の管理・
 * 押印済み証跡 PDF）は、文書エンティティ由来のスコープに対する
 * {@link com.mannschaft.app.common.AccessControlService} の per-scope 判定で保証しており、
 * 各サービスがその判定を直接呼び出す。</p>
 */
@Service
public class CirculationAccessGuard {

    /**
     * 押印・スキップ・拒否・押印訂正・押印委任について、操作対象の受信者行が
     * <b>当該文書のものであり、かつ操作者本人のものである</b>ことを保証する。
     *
     * @param document    対象文書エンティティ
     * @param recipient   操作対象の受信者行
     * @param actorUserId 操作者ユーザー ID
     * @throws BusinessException 受信者行が当該文書・操作者本人のものでない場合
     *                           （{@link CirculationErrorCode#RECIPIENT_NOT_FOUND}）
     */
    public void requireRecipientSelf(CirculationDocumentEntity document,
                                     CirculationRecipientEntity recipient,
                                     Long actorUserId) {
        if (recipient == null
                || document == null
                || !Objects.equals(recipient.getDocumentId(), document.getId())
                || !Objects.equals(recipient.getUserId(), actorUserId)) {
            throw new BusinessException(CirculationErrorCode.RECIPIENT_NOT_FOUND);
        }
    }

    /**
     * コメントの更新・削除について、対象コメントが<b>当該文書に属し、かつ操作者が投稿者本人</b>
     * であることを保証する。
     *
     * @param comment     対象コメント
     * @param documentId  パスで指定された文書 ID
     * @param actorUserId 操作者ユーザー ID
     * @throws BusinessException コメントが当該文書のものでない
     *                           （{@link CirculationErrorCode#COMMENT_NOT_FOUND}）／
     *                           投稿者本人でない（{@link CirculationErrorCode#COMMENT_NOT_OWNED}）場合
     */
    public void requireCommentAuthor(CirculationCommentEntity comment, Long documentId, Long actorUserId) {
        if (comment == null || !Objects.equals(comment.getDocumentId(), documentId)) {
            throw new BusinessException(CirculationErrorCode.COMMENT_NOT_FOUND);
        }
        if (!comment.isOwnedBy(actorUserId)) {
            throw new BusinessException(CirculationErrorCode.COMMENT_NOT_OWNED);
        }
    }
}
