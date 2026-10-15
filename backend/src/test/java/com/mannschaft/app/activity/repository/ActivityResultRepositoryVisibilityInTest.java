package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityStatus;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-028 Phase B — {@link ActivityResultRepository#findVisibleByScopeTypeAndScopeId} 結合テスト。
 *
 * <p>受け入れ条件 AC-6（歯抜けゼロ）・AC-7（総件数正確）・AC-9（fail-closed 維持）を
 * 実 MySQL に対して検証する。旧実装（1 ページ取得後メモリフィルタ）は、可視な行が
 * {@code size} 件以上あっても不可視行が間に混ざると要求件数を割り込む「歯抜け」が
 * あったため、<strong>可視行と不可視行を交互に挿入した</strong>フィクスチャで検証する
 * （全件公開データのみのフィクスチャでは歯抜けを検出できない — 過去の AC-25 の教訓）。
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ActivityResultRepository.findVisibleByScopeTypeAndScopeId — 結合テスト")
class ActivityResultRepositoryVisibilityInTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ActivityResultRepository activityResultRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long SCOPE_ID = 100L;
    private static final Long VIEWER_ID = 999L;
    private static final Long OTHER_AUTHOR_ID = 1L;

    @BeforeEach
    void setUp() {
        // AC-6 の赤を検出できるフィクスチャ: 可視(PUBLIC, PUBLISHED) 20件の間に
        // 不可視(MEMBERS_ONLY, 他人作成)を10件、他人のDRAFTを5件挟み込む。
        for (int i = 0; i < 20; i++) {
            persistActivity(ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED, OTHER_AUTHOR_ID);
            if (i < 10) {
                persistActivity(ActivityVisibility.MEMBERS_ONLY, ActivityStatus.PUBLISHED, OTHER_AUTHOR_ID);
            }
            if (i < 5) {
                persistActivity(ActivityVisibility.PUBLIC, ActivityStatus.DRAFT, OTHER_AUTHOR_ID);
            }
        }
        em.flush();
        em.clear();
    }

    /**
     * AC-6: viewer が PUBLIC のみ可視（非会員相当のラダー）でも、20件の PUBLIC 行が
     * 存在する以上 size=20 で必ず 20 件返る。旧実装は不可視行が間に混ざるとここで
     * 20 件を割り込んでいた（歯抜け）。
     */
    @Test
    @DisplayName("AC-6: 可視行20件+不可視行混在でも size=20 で必ず20件返る（歯抜けゼロ）")
    void 歯抜けゼロ() {
        Page<ActivityResultEntity> page = activityResultRepository.findVisibleByScopeTypeAndScopeId(
                ActivityScopeType.TEAM, SCOPE_ID, Set.of(ActivityVisibility.PUBLIC),
                VIEWER_ID, false, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).allMatch(a -> a.getVisibility() == ActivityVisibility.PUBLIC);
        assertThat(page.getContent()).allMatch(a -> a.getStatus() == ActivityStatus.PUBLISHED);
    }

    /**
     * AC-7: 総件数は実際の可視件数（PUBLIC×PUBLISHED = 20件）と一致し、
     * 不可視行を含めた全行数（35件）や近似値にはならない。
     */
    @Test
    @DisplayName("AC-7: 総件数は実可視件数と一致する（上界近似ではない）")
    void 総件数は実可視件数と一致() {
        Page<ActivityResultEntity> page = activityResultRepository.findVisibleByScopeTypeAndScopeId(
                ActivityScopeType.TEAM, SCOPE_ID, Set.of(ActivityVisibility.PUBLIC),
                VIEWER_ID, false, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(20L);
    }

    /**
     * AC-9（拒否側）: 非会員相当（PUBLICのみ可視）の viewer には MEMBERS_ONLY 行も
     * 他人の DRAFT 行も一切含まれない。
     */
    @Test
    @DisplayName("AC-9（拒否側）: PUBLICのみ可視な閲覧者にMEMBERS_ONLY/他人DRAFTは漏れない")
    void 機微な行は漏れない() {
        Page<ActivityResultEntity> page = activityResultRepository.findVisibleByScopeTypeAndScopeId(
                ActivityScopeType.TEAM, SCOPE_ID, Set.of(ActivityVisibility.PUBLIC),
                VIEWER_ID, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).noneMatch(a -> a.getVisibility() == ActivityVisibility.MEMBERS_ONLY);
        assertThat(page.getContent()).noneMatch(a -> a.getStatus() == ActivityStatus.DRAFT);
    }

    /**
     * AC-9（許可側）: MEMBERS_ONLY まで可視なラダーを持つ閲覧者には
     * MEMBERS_ONLY 行も正しく含まれる（許可側を書かないと「全部不可視になる」実装ミスに
     * 気付けないという申し送りに基づく）。
     */
    @Test
    @DisplayName("AC-9（許可側）: MEMBERS_ONLY まで可視な閲覧者にはMEMBERS_ONLY行が含まれる")
    void MEMBERS_ONLYまで可視なら含まれる() {
        Page<ActivityResultEntity> page = activityResultRepository.findVisibleByScopeTypeAndScopeId(
                ActivityScopeType.TEAM, SCOPE_ID,
                Set.of(ActivityVisibility.PUBLIC, ActivityVisibility.MEMBERS_ONLY),
                VIEWER_ID, false, PageRequest.of(0, 100));

        assertThat(page.getTotalElements()).isEqualTo(30L); // 20 PUBLIC + 10 MEMBERS_ONLY
        assertThat(page.getContent()).anyMatch(a -> a.getVisibility() == ActivityVisibility.MEMBERS_ONLY);
    }

    /**
     * AC-9（許可側 DRAFT）: 作成者本人には自分の DRAFT が見える。
     */
    @Test
    @DisplayName("AC-9（許可側）: 作成者本人には自分のDRAFTが見える")
    void 作成者本人にはDRAFTが見える() {
        Long myDraftId = persistActivity(ActivityVisibility.PUBLIC, ActivityStatus.DRAFT, VIEWER_ID);
        em.flush();
        em.clear();

        Page<ActivityResultEntity> page = activityResultRepository.findVisibleByScopeTypeAndScopeId(
                ActivityScopeType.TEAM, SCOPE_ID, Set.of(ActivityVisibility.PUBLIC),
                VIEWER_ID, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).extracting(ActivityResultEntity::getId).contains(myDraftId);
    }

    /**
     * AC-9（許可側 SystemAdmin）: SystemAdmin フラグが立っていれば他人の DRAFT も見える。
     */
    @Test
    @DisplayName("AC-9（許可側）: SystemAdminには他人のDRAFTも見える")
    void SystemAdminには他人のDRAFTも見える() {
        Page<ActivityResultEntity> page = activityResultRepository.findVisibleByScopeTypeAndScopeId(
                ActivityScopeType.TEAM, SCOPE_ID, Set.of(ActivityVisibility.PUBLIC),
                VIEWER_ID, true, PageRequest.of(0, 100));

        long draftCount = page.getContent().stream()
                .filter(a -> a.getStatus() == ActivityStatus.DRAFT)
                .count();
        assertThat(draftCount).isEqualTo(5L);
    }

    private Long persistActivity(ActivityVisibility visibility, ActivityStatus status, Long createdBy) {
        ActivityResultEntity entity = ActivityResultEntity.builder()
                .scopeType(ActivityScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .title("活動記録")
                .activityDate(LocalDate.now())
                .visibility(visibility)
                .status(status)
                .createdBy(createdBy)
                .build();
        em.persist(entity);
        return entity.getId();
    }
}
