package com.mannschaft.app.social.announcement;

import com.mannschaft.app.cms.service.BlogPostPreviewService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 組織告知の一覧に出してよい行を、<b>本文プレビューが 404 にならない行だけ</b>に絞る（一覧 ⊆ 本文。CMP-261011-0035）。
 * <b>トランザクションを持たず、{@code @Transactional} のクラス・メソッドからは呼ばない</b>
 * （{@link OrgAnnouncementAudienceAccess} と同じ。ArchUnit F04）。
 *
 * <p>組織の一覧は<b>直属範囲（直属資格 Qd）だけ</b>で判定する（マスター裁可 ALIC-1 D08）。宛先チームの委任資格 Qa で
 * 読める告知は組織の一覧に加えない（チーム側の一覧に出る）。プレビューが読める告知のうち、Qd で読めるものは一覧に出て、
 * 一覧に出たものはプレビューで必ず読める。</p>
 *
 * <p>プレビュー（{@link AnnouncementPreviewService}）の直属経路と同じ判定を、取得行をまとめて行う。</p>
 * <ol>
 *   <li><b>所有スコープ</b> — 準備中・論理削除された組織は、管理者も含めて全員不可
 *       （{@link AnnouncementPreviewSourceService#isScopeDenied}。プレビューと共有。組織単位で 1 回）。</li>
 *   <li><b>告知の直属資格</b> — {@link OrgAnnouncementAudienceAccess#evaluateAll} の {@code directAllowed}
 *       （宛先条件と可視性）。</li>
 *   <li><b>元コンテンツの可視性</b> — 元がブログ・掲示板の告知は、閲覧者本人の役割で F00 を一括評価する。ブログはプレビューと
 *       同じ strict 課金の一括結果（{@link BlogPostPreviewService#filterPreviewReadable}。課金 HIDDEN は管理者も除外）、
 *       掲示板は {@link ContentVisibilityChecker#filterAccessible}。SQL 本数は告知の件数に比例しない。</li>
 * </ol>
 *
 * <p>元がブログ・掲示板以外の告知は、プレビューが 404 ではなく別のエラーになる種別であり、元コンテンツの可視性による絞り込みは
 * 行わない（直属資格だけで判定する）。</p>
 */
@Component
@RequiredArgsConstructor
public class OrgAnnouncementFeedReadability {

    private final OrgAnnouncementAudienceAccess audienceAccess;
    private final ContentVisibilityChecker visibilityChecker;
    private final BlogPostPreviewService blogPreviewService;
    private final AnnouncementPreviewSourceService previewSourceService;

    /**
     * 閲覧者が本文プレビューを開ける行のうち、直属範囲で読めるものだけを、入力の順序のまま返す。
     *
     * @param feeds    同じ組織の組織告知
     * @param viewerId 閲覧者
     */
    public List<AnnouncementFeedEntity> filterReadable(List<AnnouncementFeedEntity> feeds, Long viewerId) {
        if (feeds == null || feeds.isEmpty()) {
            return List.of();
        }
        // 準備中・論理削除された組織は、プレビューが管理者も含めて 404 にする。一覧も同じ判定で空にする（組織単位で 1 回）
        if (previewSourceService.isScopeDenied(feeds.get(0))) {
            return List.of();
        }
        Map<Long, OrgAnnouncementAudienceAccess.Access> access = audienceAccess.evaluateAll(feeds, viewerId);
        // 同じ組織の告知（一覧は 1 組織ずつ）。組織が複数混じる入力は想定しない
        Long orgId = feeds.get(0).getScopeId();

        Map<ReferenceType, Set<Long>> sourceIds = new HashMap<>();
        for (AnnouncementFeedEntity feed : feeds) {
            OrgAnnouncementAudienceAccess.Access a = access.get(feed.getId());
            ReferenceType type = referenceTypeOf(feed);
            if (a != null && a.directAllowed() && type != null && feed.getSourceId() != null) {
                sourceIds.computeIfAbsent(type, k -> new HashSet<>()).add(feed.getSourceId());
            }
        }
        // 元がブログの記事はプレビューと同じ strict 課金（HIDDEN は管理者でも除外）で、掲示板は F00 で一括評価する
        Map<ReferenceType, Set<Long>> sourceReadable = new HashMap<>();
        sourceIds.forEach((type, ids) -> sourceReadable.put(type, type == ReferenceType.BLOG_POST
                ? blogPreviewService.filterPreviewReadable(ids, viewerId, orgId)
                : visibilityChecker.filterAccessible(type, ids, viewerId)));

        List<AnnouncementFeedEntity> readable = new ArrayList<>();
        for (AnnouncementFeedEntity feed : feeds) {
            OrgAnnouncementAudienceAccess.Access a = access.get(feed.getId());
            // 結果に含まれない ID は不許可（fail-closed）
            if (a == null || !a.directAllowed()) {
                continue;
            }
            ReferenceType type = referenceTypeOf(feed);
            if (type == null || sourceReadable.getOrDefault(type, Set.of()).contains(feed.getSourceId())) {
                readable.add(feed);
            }
        }
        return readable;
    }

    /** 元コンテンツの F00 種別。プレビューが本文を返す種別（ブログ・掲示板）以外は null。 */
    private static ReferenceType referenceTypeOf(AnnouncementFeedEntity feed) {
        if (feed.getSourceType() == AnnouncementSourceType.BLOG_POST) {
            return ReferenceType.BLOG_POST;
        }
        if (feed.getSourceType() == AnnouncementSourceType.BULLETIN_THREAD) {
            return ReferenceType.BULLETIN_THREAD;
        }
        return null;
    }
}
