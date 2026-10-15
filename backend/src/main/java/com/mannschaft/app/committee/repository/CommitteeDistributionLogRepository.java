package com.mannschaft.app.committee.repository;

import com.mannschaft.app.committee.entity.CommitteeDistributionLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F04.10 委員会伝達処理ログリポジトリ。
 */
public interface CommitteeDistributionLogRepository
        extends JpaRepository<CommitteeDistributionLogEntity, Long> {

    /**
     * 委員会の伝達処理履歴一覧を作成日時降順で取得する。
     *
     * @param committeeId 委員会 ID
     * @param pageable    ページング情報
     * @return 伝達処理ログページ
     */
    Page<CommitteeDistributionLogEntity> findByCommitteeIdOrderByCreatedAtDesc(
            Long committeeId, Pageable pageable);

    /**
     * コンテンツタイプ・コンテンツ ID で伝達処理ログを検索する。
     *
     * @param contentType コンテンツ種別
     * @param contentId   コンテンツ ID
     * @return 伝達処理ログリスト
     */
    List<CommitteeDistributionLogEntity> findByContentTypeAndContentId(
            String contentType, Long contentId);
}
