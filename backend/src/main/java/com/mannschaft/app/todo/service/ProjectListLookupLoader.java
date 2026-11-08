package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.repository.ProjectMilestoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * プロジェクト一覧の変換に必要な参照データ（マイルストーン件数・作成者名）を、
 * ページ内のプロジェクト数に依存しない固定本数のクエリで一括取得する（N+1 回避）。
 *
 * <p>トランザクション境界は呼び出し元（{@link ProjectService}）に従う。
 * 本クラス自体は {@code @Transactional} を宣言しない。</p>
 */
@Component
@RequiredArgsConstructor
public class ProjectListLookupLoader {

    private final ProjectMilestoneRepository milestoneRepository;
    private final NameResolverService nameResolverService;

    /** 一覧変換用の一括参照データ。 */
    public record Lookups(Map<Long, long[]> counts, Map<Long, String> userNames) {

        /** [総数, 完了数]。マイルストーンが無いプロジェクトは [0, 0]。 */
        public long[] milestoneCounts(Long projectId) {
            return counts.getOrDefault(projectId, new long[]{0L, 0L});
        }

        /** 作成者の表示。退会者・解決不能は空文字。 */
        public ProjectResponse.UserInfo userInfo(Long userId) {
            return new ProjectResponse.UserInfo(userId, userNames.getOrDefault(userId, ""));
        }
    }

    public Lookups load(List<ProjectEntity> projects) {
        if (projects.isEmpty()) {
            return new Lookups(Map.of(), Map.of());
        }
        Set<Long> projectIds = projects.stream().map(ProjectEntity::getId).collect(Collectors.toSet());
        Map<Long, long[]> counts = new HashMap<>();
        for (Object[] row : milestoneRepository.countSummaryByProjectIds(projectIds)) {
            counts.put(((Number) row[0]).longValue(),
                    new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        Set<Long> creatorIds = projects.stream().map(ProjectEntity::getCreatedBy)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return new Lookups(counts, nameResolverService.resolveUserDisplayNames(creatorIds));
    }
}
