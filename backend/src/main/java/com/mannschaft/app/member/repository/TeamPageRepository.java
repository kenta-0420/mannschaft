package com.mannschaft.app.member.repository;

import com.mannschaft.app.member.PageStatus;
import com.mannschaft.app.member.PageType;
import com.mannschaft.app.member.PageVisibility;
import com.mannschaft.app.member.entity.TeamPageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * メンバー紹介ページリポジトリ。
 */
public interface TeamPageRepository extends JpaRepository<TeamPageEntity, Long> {

    /**
     * チーム別ページ一覧をページング取得する。
     */
    Page<TeamPageEntity> findByTeamIdOrderBySortOrder(Long teamId, Pageable pageable);

    /**
     * 組織別ページ一覧をページング取得する。
     */
    Page<TeamPageEntity> findByOrganizationIdOrderBySortOrder(Long organizationId, Pageable pageable);

    /**
     * チーム内でステータス指定のページ一覧を取得する。
     */
    Page<TeamPageEntity> findByTeamIdAndStatusOrderBySortOrder(Long teamId, PageStatus status, Pageable pageable);

    /**
     * 組織内でステータス指定のページ一覧を取得する。
     */
    Page<TeamPageEntity> findByOrganizationIdAndStatusOrderBySortOrder(Long organizationId, PageStatus status, Pageable pageable);

    /**
     * 組織内でステータス＋可視性指定のページ一覧を取得する。
     *
     * <p>CMP-260919-1140 Phase 1 検分修正: 紹介サブタブを PUBLIC 設定にした非会員向け。
     * サブタブの外側の門（min_role）が PUBLIC でも、ページ固有の {@code visibility} が
     * {@code MEMBERS_ONLY} のページは列挙させない（AND 条件）。</p>
     */
    Page<TeamPageEntity> findByOrganizationIdAndStatusAndVisibilityOrderBySortOrder(
            Long organizationId, PageStatus status, PageVisibility visibility, Pageable pageable);

    /**
     * チーム内のメインページを取得する。
     */
    Optional<TeamPageEntity> findByTeamIdAndPageType(Long teamId, PageType pageType);

    /**
     * 組織内のメインページを取得する。
     */
    Optional<TeamPageEntity> findByOrganizationIdAndPageType(Long organizationId, PageType pageType);

    /**
     * チーム内の年度重複チェック。
     */
    boolean existsByTeamIdAndYear(Long teamId, Short year);

    /**
     * 組織内の年度重複チェック。
     */
    boolean existsByOrganizationIdAndYear(Long organizationId, Short year);

    /**
     * チーム内のスラッグ重複チェック。
     */
    boolean existsByTeamIdAndSlug(Long teamId, String slug);

    /**
     * 組織内のスラッグ重複チェック。
     */
    boolean existsByOrganizationIdAndSlug(Long organizationId, String slug);
}
