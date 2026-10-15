package com.mannschaft.app.line.repository;

import com.mannschaft.app.line.entity.LineMessageLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * LINEメッセージログリポジトリ。
 */
public interface LineMessageLogRepository extends JpaRepository<LineMessageLogEntity, Long> {

    /**
     * BOT設定IDでメッセージ履歴を取得する（作成日時降順）。
     */
    Page<LineMessageLogEntity> findByLineBotConfigIdOrderByCreatedAtDesc(
            Long lineBotConfigId, Pageable pageable);

    /**
     * LINEユーザーIDでメッセージ履歴を取得する（作成日時降順）。
     */
    Page<LineMessageLogEntity> findByLineUserIdOrderByCreatedAtDesc(
            String lineUserId, Pageable pageable);
}
