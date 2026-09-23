package com.mannschaft.app.moderation;

import com.mannschaft.app.moderation.entity.ContentReportEntity;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260920-0705: content_reports.content_hidden 列の欠落を検出・根治するための統合テスト。
 *
 * <p><b>背景</b>: {@code ContentReportEntity} は {@code contentHidden}（NOT NULL・既定 false）を
 * 持ち、{@code hideContent()} / {@code unhideContent()} で更新される。しかし
 * {@code content_hidden} 列を追加する Flyway migration が存在しなかったため、
 * この列を SELECT するすべてのクエリが {@code Unknown column 'cre1_0.content_hidden'}
 * （SQLState 42S22）で落ち、運営の通報一覧 {@code GET /api/v1/admin/moderation/reports} が
 * 常時 HTTP 500 になっていた。</p>
 *
 * <p><b>この IT の守備範囲と限界</b>: test プロファイルは {@code ddl-auto: create} /
 * {@code flyway.enabled: false} であり、スキーマは Flyway ではなく Hibernate が
 * Entity から生成する。したがって<b>本 IT は「migration に列があるか」を検証できない</b>
 * （Hibernate が列を作ってしまうため）。本 IT が守るのは Entity 側の往復
 * （{@code contentHidden} の既定値・{@code hideContent()} / {@code unhideContent()} の
 * 永続化と読み戻し）であり、<b>migration 側の列欠落を検出する番人は
 * {@link com.mannschaft.app.common.migration.FlywayFromScratchMigrationTest} の
 * 「全Entityのマッピング列がFlyway実スキーマに存在する」</b>（Flyway を実適用する）である。
 * 今回の欠陥は、その番人の凍結台帳 {@code KNOWN_UNPAID_DRIFT} に
 * {@code content_reports.content_hidden} が登録されていたために沈黙していた。</p>
 */
@DisplayName("content_reports.content_hidden 列 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ContentReportContentHiddenColumnIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ContentReportRepository contentReportRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private ContentReportEntity newReport(long targetId, long reportedBy) {
        return ContentReportEntity.builder()
                .targetType(ReportTargetType.TIMELINE_POST)
                .targetId(targetId)
                .reportedBy(reportedBy)
                .scopeType("TEAM")
                .scopeId(1L)
                .reason(ReportReason.SPAM)
                .build();
    }

    @Test
    @DisplayName("通報一覧のステータス絞り込み取得が 500 にならず成功する（content_hidden 列が実在する）")
    @Transactional
    void ステータス別通報一覧が取得できる() {
        contentReportRepository.saveAndFlush(newReport(9_000_001L, 9_100_001L));
        entityManager.clear();

        List<ContentReportEntity> found = contentReportRepository
                .findByStatusOrderByCreatedAtAsc(ReportStatus.PENDING, PageRequest.of(0, 20));

        assertThat(found).isNotEmpty();
    }

    @Test
    @DisplayName("contentHidden の初期値は false で永続化され、読み戻せる")
    @Transactional
    void contentHiddenの初期値はfalse() {
        ContentReportEntity saved = contentReportRepository.saveAndFlush(newReport(9_000_002L, 9_100_002L));
        Long id = saved.getId();
        entityManager.clear();

        ContentReportEntity reloaded = contentReportRepository.findById(id).orElseThrow();
        assertThat(reloaded.getContentHidden()).isFalse();
    }

    @Test
    @DisplayName("hideContent() / unhideContent() が DB に永続化され読み戻せる")
    @Transactional
    void 非表示の切り替えが永続化される() {
        ContentReportEntity saved = contentReportRepository.saveAndFlush(newReport(9_000_003L, 9_100_003L));
        Long id = saved.getId();

        saved.hideContent();
        contentReportRepository.saveAndFlush(saved);
        entityManager.clear();

        ContentReportEntity hidden = contentReportRepository.findById(id).orElseThrow();
        assertThat(hidden.getContentHidden()).isTrue();

        hidden.unhideContent();
        contentReportRepository.saveAndFlush(hidden);
        entityManager.clear();

        ContentReportEntity unhidden = contentReportRepository.findById(id).orElseThrow();
        assertThat(unhidden.getContentHidden()).isFalse();
    }

}
