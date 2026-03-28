package com.mannschaft.app.advertising;

import com.mannschaft.app.advertising.dto.AdSegmentResponse;
import com.mannschaft.app.advertising.service.AdSegmentService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.venue.entity.VenueEntity;
import com.mannschaft.app.venue.repository.VenueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdSegmentService 単体テスト")
class AdSegmentServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private VenueRepository venueRepository;
    @InjectMocks private AdSegmentService service;

    @Test
    @DisplayName("正常系: テンプレート+都道府県でセグメント抽出")
    void セグメント抽出_正常() {
        // Given
        TeamEntity team = TeamEntity.builder()
                .name("東京ベアーズ").template("baseball").prefecture("東京都").city("新宿区")
                .build();
        // TeamEntity extends BaseEntity which has id set via @GeneratedValue, so we use reflection-free approach
        var pageable = PageRequest.of(0, 20);
        given(teamRepository.findActiveTeamsForSegment("baseball", "東京都", pageable))
                .willReturn(new PageImpl<>(List.of(team), pageable, 1));
        given(userRoleRepository.countByTeamId(any())).willReturn(35L);
        given(scheduleRepository.countByTeamIdAndStartAtAfter(any(), any(LocalDateTime.class)))
                .willReturn(12L);
        given(scheduleRepository.findTopVenueByTeamId(any())).willReturn(List.of());

        // When
        PagedResponse<AdSegmentResponse> result = service.getSegments(
                "baseball", "東京都", null, pageable);

        // Then
        assertThat(result.getData()).hasSize(1);
        AdSegmentResponse segment = result.getData().get(0);
        assertThat(segment.getTeamName()).isEqualTo("東京ベアーズ");
        assertThat(segment.getTemplate()).isEqualTo("baseball");
        assertThat(segment.getMemberCount()).isEqualTo(35);
        assertThat(segment.getScheduleCountLast30Days()).isEqualTo(12);
    }

    @Test
    @DisplayName("正常系: メンバー数フィルタで少人数チームを除外")
    void セグメント抽出_メンバー数フィルタ() {
        // Given
        TeamEntity team = TeamEntity.builder()
                .name("小規模チーム").template("soccer").prefecture("大阪府").build();
        var pageable = PageRequest.of(0, 20);
        given(teamRepository.findActiveTeamsForSegment(null, null, pageable))
                .willReturn(new PageImpl<>(List.of(team), pageable, 1));
        given(userRoleRepository.countByTeamId(any())).willReturn(5L);

        // When
        PagedResponse<AdSegmentResponse> result = service.getSegments(
                null, null, 30L, pageable);

        // Then
        assertThat(result.getData()).isEmpty();
    }

    @Test
    @DisplayName("正常系: 最頻利用施設名が返る")
    void セグメント抽出_施設名あり() {
        // Given
        TeamEntity team = TeamEntity.builder()
                .name("横浜FC").template("soccer").prefecture("神奈川県").build();
        var pageable = PageRequest.of(0, 20);
        given(teamRepository.findActiveTeamsForSegment(eq("soccer"), eq("神奈川県"), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(team), pageable, 1));
        given(userRoleRepository.countByTeamId(any())).willReturn(50L);
        given(scheduleRepository.countByTeamIdAndStartAtAfter(any(), any(LocalDateTime.class)))
                .willReturn(8L);

        List<Object[]> topVenues = new java.util.ArrayList<>();
        topVenues.add(new Object[]{10L, 15L});
        given(scheduleRepository.findTopVenueByTeamId(any())).willReturn(topVenues);
        given(venueRepository.findById(10L)).willReturn(
                Optional.of(VenueEntity.builder().name("横浜スタジアム").build()));

        // When
        PagedResponse<AdSegmentResponse> result = service.getSegments(
                "soccer", "神奈川県", null, pageable);

        // Then
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getTopVenueName()).isEqualTo("横浜スタジアム");
    }
}
