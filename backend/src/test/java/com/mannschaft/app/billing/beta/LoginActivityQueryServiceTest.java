package com.mannschaft.app.billing.beta;

import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.common.timezone.UserTimezoneCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link LoginActivityQueryService} 単体テスト（F20.3 activeDays の <b>ユーザー各自のタイムゾーン</b>集計・試練先行）。
 *
 * <p>「日をまたぐ境界（＝activeDays の 1 日）をどのタイムゾーンで切るか」は付与可否を直接左右する。
 * 従来は DB セッション tz 依存で日境界が UTC に寄る危険があったため、本改修で
 * <b>ユーザーの IANA timezone → 数値オフセット（{@code "+09:00"} 形式）</b>を解決し、
 * 「その TZ での当日 00:00 から windowDays 日前の 00:00」を UTC に直した値を {@code since} として
 * リポジトリへ渡す設計に改める。</p>
 *
 * <h3>受け入れ条件（AC）トレーサビリティ</h3>
 * <ul>
 *   <li>AC-TZ3: TZ 文字列が不正／空／null でも例外を投げず {@code Asia/Tokyo} にフォールバックする</li>
 *   <li>AC-TZ4: 異なる TZ のユーザーが混在しても<b>オフセットごとに 1 回</b>だけ bulk 集計する（群分け）</li>
 *   <li>AC-TZ5: {@code since} が「ユーザー TZ での当日 00:00 − windowDays」を UTC 化した値と <b>秒単位で一致</b>する</li>
 *   <li>AC-P1: 同一 TZ なら件数に依らず TZ 解決 1 回・bulk 1 回（件数比例クエリを作らない）</li>
 *   <li>AC-P2: bulk 経路で per-user リポジトリメソッドを一度も呼ばない（N+1 非退行）</li>
 *   <li>AC-N1: 集計結果に現れない userId も <b>0 日</b>として Map に載る（0 埋め）</li>
 *   <li>AC-N2: userIds が null / 空なら空 Map・例外なし・依存を一切呼ばない</li>
 *   <li>AC-I1: TZ 解決の失敗は握り潰さず伝播する（0 日扱いへの黙殺を禁じる番人）</li>
 * </ul>
 *
 * <p>DB 非依存の純 UT（Mockito）。実 DB での日境界検証は {@code LoginActivityQueryTimezoneIT} が担う。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LoginActivityQueryService（activeDays 集計・ユーザーTZ基準の日境界）")
class LoginActivityQueryServiceTest {

    /** 評価ウィンドウ（F20.3 criteria の既定値と同じ 60 日）。 */
    private static final int WINDOW_DAYS = 60;

    /** 基準時刻（UTC）: 2026-07-28T01:00Z = JST 2026-07-28 10:00 = PDT 2026-07-27 18:00。 */
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 7, 28, 1, 0, 0);

    /** Asia/Tokyo の数値オフセット（DST 無し・通年 +09:00）。 */
    private static final String JST_OFFSET = "+09:00";

    /** America/Los_Angeles の 7 月時点の数値オフセット（PDT = 夏時間 −07:00）。 */
    private static final String PDT_OFFSET = "-07:00";

    @Mock private AuditLogRepository auditLogRepository;
    @Mock private UserTimezoneCache userTimezoneCache;

    private LoginActivityQueryService service;

    @BeforeEach
    void setUp() {
        service = new LoginActivityQueryService(auditLogRepository, userTimezoneCache);
    }

    /** bulk 集計の 1 行（{@code [0]=userId, [1]=days}）。 */
    private static Object[] row(long userId, long days) {
        return new Object[]{userId, days};
    }

    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    // ============================================================
    // AC-TZ4: TZ 混在 → オフセットごとに 1 回ずつ bulk する（群分け）
    // ============================================================

    /**
     * AC-TZ4: Asia/Tokyo と America/Los_Angeles のユーザーが同一 bulk 呼び出しに混在した場合、
     * リポジトリは<b>オフセットごとに 1 回ずつ（計 2 回）</b>呼ばれ、各群の userIds が正しく振り分けられ、
     * 戻り値の Map は両群のユーザーを含む。
     */
    @Test
    @DisplayName("AC-TZ4: TZ 混在ならオフセット単位で群分けし、群ごとに1回だけ bulk 集計する")
    void acTz4_mixedTimezones_groupedByOffset() {
        Map<Long, String> timezones = new HashMap<>();
        timezones.put(1L, "Asia/Tokyo");
        timezones.put(2L, "America/Los_Angeles");
        timezones.put(3L, "Asia/Tokyo");
        when(userTimezoneCache.getTimezones(any())).thenReturn(timezones);
        when(auditLogRepository.countDistinctLoginDaysSinceByUsers(any(), any(), eq(JST_OFFSET)))
                .thenReturn(rows(row(1L, 3L), row(3L, 5L)));
        when(auditLogRepository.countDistinctLoginDaysSinceByUsers(any(), any(), eq(PDT_OFFSET)))
                .thenReturn(rows(row(2L, 7L)));

        Map<Long, Long> result =
                service.countDistinctActiveDaysWithinByUsers(List.of(1L, 2L, 3L), WINDOW_DAYS, NOW_UTC);

        // 戻り値は両群を含む（TZ が違っても 1 つの Map に統合される）。
        assertThat(result).containsOnlyKeys(1L, 2L, 3L)
                .containsEntry(1L, 3L)
                .containsEntry(2L, 7L)
                .containsEntry(3L, 5L);

        // リポジトリ呼び出しはオフセット数（2）と一致し、ユーザー数（3）に比例しない。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> userIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<String> offsetCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogRepository, times(2)).countDistinctLoginDaysSinceByUsers(
                userIdsCaptor.capture(), any(), offsetCaptor.capture());

        List<String> offsets = offsetCaptor.getAllValues();
        List<Collection<Long>> userIdGroups = userIdsCaptor.getAllValues();
        assertThat(offsets).containsExactlyInAnyOrder(JST_OFFSET, PDT_OFFSET);

        // 同一 invocation の index で offset ↔ userIds を突き合わせる。
        int jstIndex = offsets.indexOf(JST_OFFSET);
        int pdtIndex = offsets.indexOf(PDT_OFFSET);
        assertThat(userIdGroups.get(jstIndex)).containsExactlyInAnyOrder(1L, 3L);
        assertThat(userIdGroups.get(pdtIndex)).containsExactly(2L);
    }

    // ============================================================
    // AC-TZ3: 不正 TZ / 空文字 / null は Asia/Tokyo フォールバック
    // ============================================================

    /**
     * AC-TZ3: {@code getTimezones} が返した値が不正な IANA 名・空文字・null のいずれであっても、
     * 例外を投げず既定の {@code Asia/Tokyo}（+09:00）として扱い、3 人とも同一群に入る。
     */
    @Test
    @DisplayName("AC-TZ3: 不正TZ・空文字・null は例外にせず Asia/Tokyo(+09:00) 群として扱う")
    void acTz3_invalidTimezone_fallsBackToAsiaTokyo() {
        Map<Long, String> timezones = new HashMap<>();
        timezones.put(1L, "Not/AZone");
        timezones.put(2L, "");
        timezones.put(3L, null);
        when(userTimezoneCache.getTimezones(any())).thenReturn(timezones);
        when(auditLogRepository.countDistinctLoginDaysSinceByUsers(any(), any(), eq(JST_OFFSET)))
                .thenReturn(rows(row(1L, 1L), row(2L, 2L), row(3L, 3L)));

        Map<Long, Long> result =
                service.countDistinctActiveDaysWithinByUsers(List.of(1L, 2L, 3L), WINDOW_DAYS, NOW_UTC);

        assertThat(result).containsOnlyKeys(1L, 2L, 3L);

        // フォールバックにより群は 1 つ（+09:00）だけ。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> userIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(auditLogRepository, times(1)).countDistinctLoginDaysSinceByUsers(
                userIdsCaptor.capture(), any(), eq(JST_OFFSET));
        assertThat(userIdsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    // ============================================================
    // AC-TZ5: since の境界（ユーザー TZ の当日 00:00 − windowDays を UTC 化）
    // ============================================================

    /**
     * AC-TZ5: windowDays=60・TZ=Asia/Tokyo・nowUtc=2026-07-28T01:00Z（＝JST 7/28 10:00）のとき、
     * リポジトリへ渡る {@code since} は <b>JST 2026-05-29 00:00 を UTC 化した 2026-05-28T15:00</b> になる。
     *
     * <p>「当日の途中（10:00）」を切り捨てて日頭に揃えること・windowDays を JST 日付で引くこと・
     * 最後に UTC へ戻すことの 3 点を同時に固定する。1 秒でもずれたら落ちる厳密比較。</p>
     */
    @Test
    @DisplayName("AC-TZ5: since は「JST当日00:00 − 60日」を UTC 化した 2026-05-28T15:00 と厳密一致する")
    void acTz5_sinceBoundaryIsUserTimezoneMidnightConvertedToUtc() {
        when(userTimezoneCache.getTimezones(any())).thenReturn(Map.of(1L, "Asia/Tokyo"));
        when(auditLogRepository.countDistinctLoginDaysSinceByUsers(any(), any(), eq(JST_OFFSET)))
                .thenReturn(rows(row(1L, 60L)));

        service.countDistinctActiveDaysWithinByUsers(List.of(1L), WINDOW_DAYS, NOW_UTC);

        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auditLogRepository).countDistinctLoginDaysSinceByUsers(
                any(), sinceCaptor.capture(), eq(JST_OFFSET));

        // JST 2026-05-29 00:00:00 = UTC 2026-05-28 15:00:00
        assertThat(sinceCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 5, 28, 15, 0, 0));
    }

    /**
     * AC-TZ5（per-user 版）: 単体照会 {@code countDistinctActiveDaysWithin} も bulk と同一の日境界規則で
     * {@code since} を組み立て、同一のオフセット文字列をリポジトリへ渡す。
     *
     * <p>bulk と per-user で境界がずれると「自分の進捗表示では 14 日なのに自動付与されない」という
     * 不整合が生まれるため、両経路の規則一致を試練で固定する。</p>
     *
     * <p>単体照会の TZ 解決に既存の {@code getTimezone(Long)} を使うか新設の {@code getTimezones(Collection)} を
     * 使うかは実装の裁量に委ねるため、TZ 解決の stub は {@code lenient()}（未使用でも失敗させない）とし、
     * 検証はリポジトリへ渡る {@code since}／オフセットに絞る。</p>
     */
    @Test
    @DisplayName("AC-TZ5(per-user): 単体照会も同じ日境界（JST当日00:00 −60日 → UTC）で since を組む")
    void acTz5_perUser_sameBoundaryRule() {
        lenient().when(userTimezoneCache.getTimezone(1L)).thenReturn("Asia/Tokyo");
        lenient().when(userTimezoneCache.getTimezones(any())).thenReturn(Map.of(1L, "Asia/Tokyo"));
        when(auditLogRepository.countDistinctLoginDaysSince(eq(1L), any(), eq(JST_OFFSET))).thenReturn(21L);

        long days = service.countDistinctActiveDaysWithin(1L, WINDOW_DAYS, NOW_UTC);

        assertThat(days).isEqualTo(21L);
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auditLogRepository).countDistinctLoginDaysSince(eq(1L), sinceCaptor.capture(), eq(JST_OFFSET));
        assertThat(sinceCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 5, 28, 15, 0, 0));
    }

    // ============================================================
    // AC-P1 / AC-P2: 件数に比例しない・per-user 経路へ落ちない
    // ============================================================

    /**
     * AC-P1: userIds 500 件がすべて同一 TZ のとき、TZ 解決は 1 回・bulk 集計も 1 回だけ。
     * クエリ本数がユーザー件数に比例しない（N+1 を作らない）ことを示す。
     */
    @Test
    @DisplayName("AC-P1: 同一TZの500件でも getTimezones 1回・bulk 1回（件数に比例しない）")
    void acP1_sameTimezone500Users_singleBulkQuery() {
        List<Long> userIds = LongStream.rangeClosed(1L, 500L).boxed().toList();
        Map<Long, String> timezones = userIds.stream()
                .collect(Collectors.toMap(id -> id, id -> "Asia/Tokyo"));
        when(userTimezoneCache.getTimezones(any())).thenReturn(timezones);
        when(auditLogRepository.countDistinctLoginDaysSinceByUsers(any(), any(), eq(JST_OFFSET)))
                .thenReturn(rows(row(1L, 10L)));

        Map<Long, Long> result = service.countDistinctActiveDaysWithinByUsers(userIds, WINDOW_DAYS, NOW_UTC);

        assertThat(result).hasSize(500);
        verify(userTimezoneCache, times(1)).getTimezones(any());
        verify(auditLogRepository, times(1)).countDistinctLoginDaysSinceByUsers(any(), any(), any());
    }

    /**
     * AC-P2（非退行）: bulk 経路では per-user リポジトリメソッド
     * {@code countDistinctLoginDaysSince} を一度も呼ばない。
     */
    @Test
    @DisplayName("AC-P2: bulk 経路で per-user の countDistinctLoginDaysSince を一度も呼ばない")
    void acP2_bulkPath_neverCallsPerUserRepositoryMethod() {
        List<Long> userIds = List.of(1L, 2L, 3L);
        Map<Long, String> timezones = new HashMap<>();
        timezones.put(1L, "Asia/Tokyo");
        timezones.put(2L, "America/Los_Angeles");
        timezones.put(3L, "Asia/Tokyo");
        when(userTimezoneCache.getTimezones(any())).thenReturn(timezones);
        when(auditLogRepository.countDistinctLoginDaysSinceByUsers(any(), any(), any()))
                .thenReturn(rows());

        service.countDistinctActiveDaysWithinByUsers(userIds, WINDOW_DAYS, NOW_UTC);

        verify(auditLogRepository, never()).countDistinctLoginDaysSince(any(), any(), any());
    }

    // ============================================================
    // AC-N1 / AC-N2: 欠損の 0 埋め・空入力
    // ============================================================

    /**
     * AC-N1: bulk の {@code GROUP BY} は「ログイン記録が無いユーザー」の行を返さないため、
     * 結果 Map に現れない userId は <b>0 日として Map に載せる</b>（欠損のまま返さない）。
     *
     * <p>呼び出し側の {@code getOrDefault(id, 0L)} 依存を撤廃し、
     * 「Map に無い＝未計測なのか 0 日なのか」の曖昧さを型で潰す。</p>
     */
    @Test
    @DisplayName("AC-N1: 集計行が無いユーザーも 0 日として Map に載る（全 userId 分を 0 埋めで返す）")
    void acN1_missingUsersAreZeroFilled() {
        when(userTimezoneCache.getTimezones(any()))
                .thenReturn(Map.of(1L, "Asia/Tokyo", 2L, "Asia/Tokyo"));
        // 2L はログイン記録が無く GROUP BY の結果に現れない。
        when(auditLogRepository.countDistinctLoginDaysSinceByUsers(any(), any(), eq(JST_OFFSET)))
                .thenReturn(rows(row(1L, 4L)));

        Map<Long, Long> result =
                service.countDistinctActiveDaysWithinByUsers(List.of(1L, 2L), WINDOW_DAYS, NOW_UTC);

        assertThat(result).containsOnlyKeys(1L, 2L)
                .containsEntry(1L, 4L)
                .containsEntry(2L, 0L);
    }

    /** AC-N2: userIds が null なら空 Map を返し、リポジトリも TZ キャッシュも呼ばない。 */
    @Test
    @DisplayName("AC-N2: userIds が null なら空Map・例外なし・依存を一切呼ばない")
    void acN2_nullUserIds_returnsEmptyMapWithoutInteractions() {
        Map<Long, Long> result = service.countDistinctActiveDaysWithinByUsers(null, WINDOW_DAYS, NOW_UTC);

        assertThat(result).isEmpty();
        verifyNoInteractions(auditLogRepository, userTimezoneCache);
    }

    /** AC-N2: userIds が空 Collection なら空 Map を返す（{@code IN ()} の不正 SQL を撃たない）。 */
    @Test
    @DisplayName("AC-N2: userIds が空Collectionなら空Map・例外なし・依存を一切呼ばない")
    void acN2_emptyUserIds_returnsEmptyMapWithoutInteractions() {
        Map<Long, Long> result = service.countDistinctActiveDaysWithinByUsers(List.of(), WINDOW_DAYS, NOW_UTC);

        assertThat(result).isEmpty();
        verifyNoInteractions(auditLogRepository, userTimezoneCache);
    }

    // ============================================================
    // AC-I1: 握りつぶし禁止（番人テスト）
    // ============================================================

    /**
     * AC-I1: TZ 解決が {@link RuntimeException} で失敗した場合、
     * <b>黙って 0 日扱い（空 Map）にせず例外をそのまま伝播</b>させる。
     *
     * <p>「TZ が引けなかったので全員 0 日」は付与漏れを静かに生む対処療法であり、
     * 将来 catch して空 Map を返す実装が入らないよう番人として固定する（CLAUDE.md 障害対応の原則）。</p>
     */
    @Test
    @DisplayName("AC-I1: TZ解決の失敗は握り潰さず例外がそのまま伝播する（0日扱いへの黙殺を禁じる）")
    void acI1_timezoneResolutionFailurePropagates() {
        when(userTimezoneCache.getTimezones(any()))
                .thenThrow(new RuntimeException("timezone lookup failed"));

        assertThatThrownBy(() ->
                service.countDistinctActiveDaysWithinByUsers(List.of(1L, 2L), WINDOW_DAYS, NOW_UTC))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("timezone lookup failed");

        verifyNoInteractions(auditLogRepository);
    }
}
