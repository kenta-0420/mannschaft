package com.mannschaft.app.tournament;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.tournament.dto.ParticipantResponse;
import com.mannschaft.app.tournament.service.ParticipantTeamNameEnricher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ParticipantTeamNameEnricher} の単体テスト（CMP-260929-0654: 参加チーム表のチーム名が空欄だった欠陥）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParticipantTeamNameEnricher 単体テスト")
class ParticipantTeamNameEnricherTest {

    @Mock private NameResolverService nameResolverService;

    @InjectMocks
    private ParticipantTeamNameEnricher enricher;

    private static ParticipantResponse response(long id, long teamId) {
        return new ParticipantResponse(id, 10L, teamId, null, null, "ACTIVE", null, null);
    }

    @Test
    @DisplayName("一覧: teamName が入り、チーム名は全チームぶんを1回の呼び出しで解決する（N+1 なし）")
    void 一覧にチーム名() {
        given(nameResolverService.resolveTeamNames(Set.of(5L, 6L)))
                .willReturn(Map.of(5L, "レッドFC", 6L, "ブルーFC"));

        List<ParticipantResponse> result = enricher.enrich(List.of(response(1, 5), response(2, 6)));

        assertThat(result).extracting(ParticipantResponse::getTeamName).containsExactly("レッドFC", "ブルーFC");
        assertThat(result).extracting(ParticipantResponse::getTeamId).containsExactly(5L, 6L);
        verify(nameResolverService, times(1)).resolveTeamNames(Set.of(5L, 6L));
    }

    @Test
    @DisplayName("単一: teamName が入り、他の項目は保持される")
    void 単一にチーム名() {
        given(nameResolverService.resolveTeamNames(Set.of(5L))).willReturn(Map.of(5L, "レッドFC"));

        ParticipantResponse result = enricher.enrich(response(1, 5));

        assertThat(result.getTeamName()).isEqualTo("レッドFC");
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getDivisionId()).isEqualTo(10L);
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("チームが解決できない（削除済み等）場合は teamName が null")
    void 解決不能はnull() {
        given(nameResolverService.resolveTeamNames(Set.of(5L))).willReturn(Map.of());

        assertThat(enricher.enrich(response(1, 5)).getTeamName()).isNull();
    }
}
