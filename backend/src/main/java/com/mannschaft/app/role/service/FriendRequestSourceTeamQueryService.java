package com.mannschaft.app.role.service;

import com.mannschaft.app.role.repository.FriendRequestSourceTeamRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** キャッシュを介さず現在DBの申請元適格性を公開するrole読取API。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FriendRequestSourceTeamQueryService {
    private final FriendRequestSourceTeamRepository repository;

    /** 全件ロードせず、SQLで絞込み済みのページと総数だけを返す。 */
    public Page<SourceTeamSummary> findSourceTeams(Long userId, String targetSlug, Pageable pageable) {
        long total = repository.countEligibleSources(userId, targetSlug);
        if (pageable.getOffset() > Integer.MAX_VALUE) {
            return new PageImpl<>(List.of(), pageable, total);
        }
        var rows = repository.findEligibleSources(userId, targetSlug, pageable.getPageSize(), pageable.getOffset())
                .stream().map(row -> new SourceTeamSummary(((Number) row[0]).longValue(),
                        (String) row[1], (String) row[2])).toList();
        return new PageImpl<>(rows, pageable, total);
    }

    /** 候補と同じWHEREで、権限キャッシュがwarmでも剥奪を即時に検出する。 */
    public boolean isEligibleSource(Long userId, Long sourceTeamId) {
        return repository.countEligibleSource(userId, sourceTeamId) > 0;
    }

    /** role所有の読取値。Entityをドメイン境界の外へ出さない。 */
    public record SourceTeamSummary(Long teamId, String teamSlug, String teamName) { }
}
