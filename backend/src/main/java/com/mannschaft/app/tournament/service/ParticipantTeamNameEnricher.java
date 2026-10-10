package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.tournament.dto.ParticipantResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 参加チームレスポンスに team ドメインのチーム名を付与する。
 *
 * <p>team ドメインの参照は {@link NameResolverService} 経由の ID 一括解決のみ（Repository を直接触らない・N+1 なし）。
 * {@code @Transactional} な {@link DivisionService} からは team ドメインの Repository へ到達させない
 * （D-3T: 越境トランザクション禁止）ため、本クラスは非トランザクションで Controller から呼ぶ。</p>
 */
@Component
@RequiredArgsConstructor
public class ParticipantTeamNameEnricher {

    private final NameResolverService nameResolverService;

    /** 参加チーム一覧のチーム名をまとめて1回で解決して付与する。 */
    public List<ParticipantResponse> enrich(List<ParticipantResponse> participants) {
        Set<Long> teamIds = participants.stream()
                .map(ParticipantResponse::getTeamId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = nameResolverService.resolveTeamNames(teamIds);
        return participants.stream()
                .map(p -> ParticipantResponse.withTeamName(p, names.get(p.getTeamId())))
                .toList();
    }

    /** 単一の参加チームにチーム名を付与する。 */
    public ParticipantResponse enrich(ParticipantResponse participant) {
        return enrich(Collections.singletonList(participant)).get(0);
    }
}
