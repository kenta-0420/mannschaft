package com.mannschaft.app.search.service;

import com.mannschaft.app.search.dto.SearchResultResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * グローバル検索サービス。9種別（schedules, events, reservations, shifts,
 * safetyChecks, queues, teams, organizations, users）を横断検索する。
 *
 * <p>各種別のRepository参照は将来実装時に追加する。現在はスタブ結果を返す。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GlobalSearchService {

    // TODO: 各種別のRepository/Serviceを注入（実装時に追加）
    // private final ScheduleRepository scheduleRepository;
    // private final EventRepository eventRepository;
    // private final ReservationRepository reservationRepository;
    // private final ShiftRepository shiftRepository;
    // private final SafetyCheckRepository safetyCheckRepository;
    // private final QueueRepository queueRepository;
    // private final TeamRepository teamRepository;
    // private final OrganizationRepository organizationRepository;
    // private final UserRepository userRepository;

    private static final List<String> SEARCH_TYPES = List.of(
            "schedules", "events", "reservations", "shifts",
            "safetyChecks", "queues", "teams", "organizations", "users"
    );

    /**
     * 9種別横断検索を実行する。
     *
     * <p>各種別のRepositoryから部分一致検索を行い、統合結果を返す。
     * 現時点では各種別のRepository参照がTODOのため、空の結果を返す。</p>
     *
     * @param query  検索クエリ
     * @param userId 検索実行ユーザーID
     * @return 統合検索結果
     */
    public SearchResultResponse search(String query, Long userId) {
        long startTime = System.currentTimeMillis();

        Map<String, List<Map<String, Object>>> results = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();

        for (String type : SEARCH_TYPES) {
            // TODO: 各種別のRepository横断検索を実装
            results.put(type, Collections.emptyList());
            counts.put(type, 0L);
        }

        long executionTimeMs = System.currentTimeMillis() - startTime;
        log.info("グローバル検索実行: query='{}', userId={}, executionTime={}ms", query, userId, executionTimeMs);

        return new SearchResultResponse(query, results, counts, executionTimeMs);
    }
}
