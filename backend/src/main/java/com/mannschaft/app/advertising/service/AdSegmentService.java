package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.dto.AdSegmentResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.venue.entity.VenueEntity;
import com.mannschaft.app.venue.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 広告セグメント抽出サービス（SYSTEM_ADMIN用）。
 * 1st Party Dataを広告主が欲しがるセグメントで抽出する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdSegmentService {

    private final TeamRepository teamRepository;
    private final UserRoleRepository userRoleRepository;
    private final ScheduleRepository scheduleRepository;
    private final VenueRepository venueRepository;

    /**
     * チーム単位のセグメント情報をフィルタリングして取得する。
     *
     * @param template       テンプレート（競技カテゴリ）絞り込み（nullは全件）
     * @param prefecture     都道府県絞り込み（nullは全件）
     * @param minMemberCount 最小メンバー数（nullは制限なし）
     * @param pageable       ページネーション
     */
    public PagedResponse<AdSegmentResponse> getSegments(
            String template, String prefecture, Long minMemberCount, Pageable pageable) {

        Page<TeamEntity> teams = teamRepository.findActiveTeamsForSegment(
                template, prefecture, pageable);

        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);

        Page<AdSegmentResponse> result = teams.map(team -> {
            long memberCount = userRoleRepository.countByTeamId(team.getId());

            // メンバー数フィルタ（JPA側でできないためアプリ層で）
            if (minMemberCount != null && memberCount < minMemberCount) {
                return null;
            }

            long scheduleCount = scheduleRepository.countByTeamIdAndStartAtAfter(
                    team.getId(), thirtyDaysAgo);

            // 最頻利用施設を取得
            String topVenueName = null;
            var topVenues = scheduleRepository.findTopVenueByTeamId(team.getId());
            if (!topVenues.isEmpty()) {
                Long venueId = (Long) topVenues.get(0)[0];
                if (venueId != null) {
                    topVenueName = venueRepository.findById(venueId)
                            .map(VenueEntity::getName)
                            .orElse(null);
                }
            }

            return new AdSegmentResponse(
                    team.getId(),
                    team.getName(),
                    team.getTemplate(),
                    team.getPrefecture(),
                    team.getCity(),
                    memberCount,
                    scheduleCount,
                    topVenueName
            );
        });

        // null（メンバー数フィルタで除外）を除去した結果を返す
        var filtered = result.getContent().stream()
                .filter(r -> r != null)
                .toList();

        return PagedResponse.of(
                filtered,
                new PagedResponse.PageMeta(
                        result.getTotalElements(),
                        result.getNumber(),
                        result.getSize(),
                        result.getTotalPages()
                )
        );
    }
}
