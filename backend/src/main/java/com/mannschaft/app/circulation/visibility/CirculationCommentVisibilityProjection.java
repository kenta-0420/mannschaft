package com.mannschaft.app.circulation.visibility;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityProjection;

/**
 * F00 Phase C — 回覧コメント ({@link com.mannschaft.app.circulation.entity.CirculationCommentEntity}) 用 Projection。
 *
 * <p>コメントは機能側に visibility 概念を持たず、親文書 ({@link com.mannschaft.app.circulation.entity.CirculationDocumentEntity})
 * の可視性に従属する。{@link #visibility()} は常に {@link StandardVisibility#CUSTOM} を返し、
 * Resolver の {@code evaluateCustom} で親文書への委譲判定を行う（§D-16）。</p>
 *
 * <p>{@code scopeType} / {@code scopeId} は JOIN で親文書から取得し、
 * 親 ORG 非アクティブガード（§11.6）に利用する。</p>
 *
 * @param id           circulation_comments.id
 * @param scopeType    親文書の scope_type（{@code "TEAM"} または {@code "ORGANIZATION"}）
 * @param scopeId      親文書の scope_id（team_id または organization_id）
 * @param authorUserId circulation_comments.user_id（コメント投稿者）
 * @param documentId   circulation_comments.document_id（親文書 ID。委譲判定に利用）
 */
public record CirculationCommentVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId,
        Long documentId) implements VisibilityProjection {

    @Override
    public Long visibilityTemplateId() {
        return null;
    }

    /**
     * コメントは配信先 ACL 判定を親文書に委譲するため、常に {@link StandardVisibility#CUSTOM} を返す。
     */
    @Override
    public Object visibility() {
        return StandardVisibility.CUSTOM;
    }
}
