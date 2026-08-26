package com.mannschaft.app.billing.beta;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20.3: <b>activeDays（活動日数）をユーザー各自のタイムゾーンで数える</b>ことを実 MySQL（Testcontainers）越しに
 * 検証する統合テスト（試練＝実装より先に書く red テスト）。
 *
 * <h3>なぜ IT でしか検証できないか</h3>
 * <p>日境界の切り出しは MySQL 側の日付関数（{@code DATE()} / {@code CONVERT_TZ()}）が担う。現行実装は
 * {@code COUNT(DISTINCT FUNCTION('DATE', a.createdAt))}（{@code AuditLogRepository}）＝<b>TZ 変換なし</b>で、
 * 日境界が DB セッションの {@code time_zone} 任せになっている。モック UT では DB の日付関数そのものを検証できず
 * 偽 green になるため（memory {@code feedback_adapter_mock_ut_false_green_downstream_enum}）、実 MySQL で検証する。</p>
 *
 * <h3>実装契約（本 IT が要求するシグネチャ・実装は本 IT の後に行う）</h3>
 * <pre>{@code
 * // com.mannschaft.app.auth.repository.AuditLogRepository
 * long countDistinctLoginDaysSince(Long userId, LocalDateTime since, String storedZoneOffset, String tzOffset);
 * List<Object[]> countDistinctLoginDaysSinceByUsers(
 *     Collection<Long> userIds, LocalDateTime since, String storedZoneOffset, String tzOffset);
 * //   tzOffset は "+09:00" 形式。storedZoneOffset（格納基準TZ、既定 "+00:00"）格納値をこのオフセットへ
 * //   変換してから日付を切る（2026-07-28 是正: storedZoneOffset を SQL 直書きからバインドパラメータへ切り出し）。
 *
 * // com.mannschaft.app.billing.beta.LoginActivityQueryService
 * long countDistinctActiveDaysWithin(Long userId, int windowDays, LocalDateTime nowUtc);
 * Map<Long, Long> countDistinctActiveDaysWithinByUsers(Collection<Long> userIds, int windowDays, LocalDateTime nowUtc);
 * //   ユーザーの users.timezone を解決し、評価ウィンドウ起点も「そのユーザーの現地日付の 00:00」へ丸める。
 * //   ログイン記録の無いユーザーも 0 日として Map に載せる（bulk の GROUP BY 欠損を 0 埋めする）。
 * }</pre>
 *
 * <h3>フィクスチャの TZ 経路（最重要・9h ズレの罠）</h3>
 * <p>テスト JVM は {@code build.gradle.kts} の {@code -Duser.timezone=Asia/Tokyo} で <b>JST 固定</b>、
 * {@code application-test.yml} は {@code hibernate.jdbc.time_zone: UTC}。この組み合わせでは
 * <b>Hibernate がバインドする {@code LocalDateTime} と、生の文字列リテラル INSERT の値が 9h ずれる</b>
 * （memory {@code feedback_it_fixture_datetime_tz_bind}: 実効窓が −9h シフトして境界フィクスチャが落ちた実績）。
 * そこで本 IT は検証したい軸ごとに投入経路を意図的に使い分ける:</p>
 * <table>
 *   <caption>フィクスチャ投入経路の使い分け</caption>
 *   <tr><th>ヘルパ</th><th>経路</th><th>保証</th><th>用途</th></tr>
 *   <tr>
 *     <td>{@link #insertLoginAtRawUtc}</td>
 *     <td>{@link JdbcTemplate} ＋ 文字列リテラル</td>
 *     <td><b>DB の生格納値が指定した UTC 壁時計そのもの</b>になる（ドライバ変換を一切通さない）</td>
 *     <td>日境界（{@code CONVERT_TZ} の入力が真に UTC であることが前提）— AC-TZ1/TZ2/TZ4/N1</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #insertLoginAtAppLocal}</td>
 *     <td>Hibernate ネイティブクエリの {@code setParameter(LocalDateTime)}</td>
 *     <td><b>本番クエリの {@code :since} と完全に同一のバインド経路</b>（ズレがあっても両側に等しく効く）</td>
 *     <td>ウィンドウ起点の 1 秒境界 — AC-TZ5</td>
 *   </tr>
 * </table>
 * <p>いずれの経路も {@code AuditLogEntity} の {@code @PrePersist} が {@code created_at} を {@code now()} で
 * 上書きするため JPA 経由の save は使えない（既存 {@code BetaPerkAutoGrantBatchIT} と同じ制約）。</p>
 *
 * <h3>決定論</h3>
 * <p>基準時刻は<b>注入した {@link Clock}（UTC）</b>からのみ導き、{@code LocalDateTime.now()} の素呼び出し・
 * システム既定 TZ 依存の計算は一切しない。JST/PST への換算は {@link ZoneId} を明示した {@code java.time} 演算で行う。
 * {@code America/Los_Angeles} は夏時間で −08:00/−07:00 に振れるが、本 IT のケースは<b>どちらのオフセットでも
 * 期待値が変わらない</b>ように時刻を選んである（AC-TZ4 参照）。</p>
 *
 * <h3>共有コンテナのデータ非分離</h3>
 * <p>コンテナは JVM 内共有・ロールバックしないため、ユーザーは毎回新規生成し、アサーションは
 * <b>自分が生成した user_id に対してのみ</b>行う（全体件数に依存しない）。</p>
 */
@DisplayName("F20.3 activeDays タイムゾーン日境界 統合テスト（ユーザー各自の TZ で日を数える）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class LoginActivityTimezoneIT extends AbstractMySqlIntegrationTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    /** 評価ウィンドウ（日）。日境界ケースの seed（5 日前）は十分内側に入る。 */
    private static final int WINDOW_DAYS = 60;

    /** 日境界ケースの seed 基準日（now から何日前か）。過去かつウィンドウ内であればよい。 */
    private static final int ANCHOR_DAYS_AGO = 5;

    private static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    private static final DateTimeFormatter MYSQL_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired private LoginActivityQueryService loginActivityQueryService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;

    @PersistenceContext private EntityManager entityManager;

    // ============================================================
    // AC-TZ1 / AC-TZ2: 個人の日境界がユーザーの TZ で切られる
    // ============================================================

    /**
     * AC-TZ1: {@code Asia/Tokyo} のユーザーが JST 23:30 と翌 JST 00:30 にログイン
     * （＝UTC では 14:30 と 15:30 で<b>同じ UTC 日</b>）→ activeDays = 2。
     *
     * <p>現行実装（{@code DATE(created_at)} を UTC のまま切る）では 1 を返すので red。</p>
     */
    @Test
    @DisplayName("AC-TZ1: JST 23:30 と翌 JST 00:30（同一 UTC 日）は JST では別日 ＝ activeDays 2")
    void acTz1_jstMidnightCrossing_countsAsTwoDays() {
        LocalDateTime nowUtc = nowUtc();
        Long user = persistActiveUser(TOKYO.getId());
        seedJstMidnightCrossing(user, nowUtc);

        long activeDays = loginActivityQueryService
                .countDistinctActiveDaysWithin(user, WINDOW_DAYS, nowUtc);

        assertThat(activeDays)
                .as("JST 23:30 と翌 JST 00:30 は JST では 2 日（UTC のまま数えると 1 日になる）")
                .isEqualTo(2L);
    }

    /**
     * AC-TZ2: 同じ {@code Asia/Tokyo} のユーザーが UTC 23:00 と翌 UTC 01:00 にログイン
     * （＝JST では同日 08:00 と 10:00）→ activeDays = 1。
     *
     * <p>現行実装（UTC で日を切る）では 2 を返すので red。AC-TZ1 の逆向き＝
     * 「UTC で分かれるが JST では同日」を潰す。</p>
     */
    @Test
    @DisplayName("AC-TZ2: UTC 23:00 と翌 UTC 01:00（UTC では別日）は JST では同日 ＝ activeDays 1")
    void acTz2_utcMidnightCrossing_countsAsOneDay() {
        LocalDateTime nowUtc = nowUtc();
        Long user = persistActiveUser(TOKYO.getId());

        LocalDate anchor = anchorDate(nowUtc);
        // UTC 23:00 → JST 翌日 08:00 / UTC 翌日 01:00 → JST 同じ翌日 10:00。
        insertLoginAtRawUtc(user, anchor.atTime(23, 0));
        insertLoginAtRawUtc(user, anchor.plusDays(1).atTime(1, 0));

        long activeDays = loginActivityQueryService
                .countDistinctActiveDaysWithin(user, WINDOW_DAYS, nowUtc);

        assertThat(activeDays)
                .as("UTC 23:00 と翌 UTC 01:00 はどちらも JST の同じ日（UTC のまま数えると 2 日になる）")
                .isEqualTo(1L);
    }

    // ============================================================
    // AC-TZ5: 評価ウィンドウ起点も現地日の 00:00 で切る（1 秒境界）
    // ============================================================

    /**
     * AC-TZ5（境界・包含側）: {@code windowDays=60} のとき、<b>JST 基準でちょうど 60 日前の 00:00 ちょうど</b>の
     * ログインは活動日数に<b>数え入れられる</b>（境界は「以上」）。
     *
     * <p>ウィンドウ起点がユーザー現地日の 00:00 に丸められること自体の検証。現行実装は
     * {@code now - windowDays} を素の日時差で取るため、この 1 秒境界は担保されていない。</p>
     */
    @Test
    @DisplayName("AC-TZ5 境界(包含): JST でちょうど60日前 00:00 ちょうどのログインは activeDays に入る")
    void acTz5_exactlyAtJstWindowStart_isIncluded() {
        LocalDateTime nowUtc = nowUtc();
        Long user = persistActiveUser(TOKYO.getId());

        insertLoginAtAppLocal(user, jstWindowStartAsUtc(nowUtc, WINDOW_DAYS));

        long activeDays = loginActivityQueryService
                .countDistinctActiveDaysWithin(user, WINDOW_DAYS, nowUtc);

        assertThat(activeDays)
                .as("ウィンドウ起点 = JST 60日前 00:00 ちょうどは境界『以上』ゆえ含まれる")
                .isEqualTo(1L);
    }

    /**
     * AC-TZ5（境界・除外側）: 同じ {@code windowDays=60} で、<b>JST 60 日前 00:00 のさらに 1 秒前</b>
     * （＝JST では 61 日前の 23:59:59）のログインは活動日数に<b>入らない</b>。
     */
    @Test
    @DisplayName("AC-TZ5 境界(除外): JST で60日前 00:00 の 1 秒前のログインは activeDays に入らない")
    void acTz5_oneSecondBeforeJstWindowStart_isExcluded() {
        LocalDateTime nowUtc = nowUtc();
        Long user = persistActiveUser(TOKYO.getId());

        insertLoginAtAppLocal(user, jstWindowStartAsUtc(nowUtc, WINDOW_DAYS).minusSeconds(1));

        long activeDays = loginActivityQueryService
                .countDistinctActiveDaysWithin(user, WINDOW_DAYS, nowUtc);

        assertThat(activeDays)
                .as("ウィンドウ起点の 1 秒前（JST 61日前 23:59:59）は範囲外")
                .isZero();
    }

    // ============================================================
    // AC-TZ4 / AC-N1: bulk 版が各ユーザーの TZ で別々に日を切る
    // ============================================================

    /**
     * AC-TZ4: {@code Asia/Tokyo} と {@code America/Los_Angeles} のユーザーに<b>同一の UTC 時刻</b>のログインを
     * 仕込み、bulk 版を 1 回呼ぶと<b>各自の TZ で異なる日数</b>が返る。
     *
     * <p>UTC の 14:30 / 15:30 は、JST では 23:30 と翌 00:30 ＝ <b>2 日</b>、太平洋時間では 06:30 と 07:30
     * （夏時間 −07:00 でも 07:30 と 08:30）＝ <b>1 日</b>。米国夏時間の切替でオフセットが −08:00/−07:00 に
     * 振れても期待値が変わらない時刻を選んでいる（実行時期に依存しない）。</p>
     *
     * <p>現行実装は全ユーザー一律 UTC で日を切るため両者とも 1 になり red。</p>
     */
    @Test
    @DisplayName("AC-TZ4: 同一 UTC 時刻のログインでも Asia/Tokyo は 2 日・America/Los_Angeles は 1 日（bulk 1 回）")
    void acTz4_bulk_perUserTimezone_yieldsDifferentDayCounts() {
        LocalDateTime nowUtc = nowUtc();
        Long tokyoUser = persistActiveUser(TOKYO.getId());
        Long laUser = persistActiveUser(LOS_ANGELES.getId());
        seedJstMidnightCrossing(tokyoUser, nowUtc);
        seedJstMidnightCrossing(laUser, nowUtc);

        Map<Long, Long> byUser = loginActivityQueryService
                .countDistinctActiveDaysWithinByUsers(List.of(tokyoUser, laUser), WINDOW_DAYS, nowUtc);

        assertThat(byUser)
                .as("同一 UTC 時刻でもユーザーの TZ ごとに日境界が違う")
                .containsEntry(tokyoUser, 2L)
                .containsEntry(laUser, 1L);
    }

    /**
     * AC-N1: ログイン記録が 1 件も無いユーザーも <b>0 日として Map に載る</b>。
     *
     * <p>bulk の {@code GROUP BY} では記録の無いユーザーの行が返らないため、サービス側で 0 埋めが必要
     * （呼び出し側の {@code getOrDefault} 頼みにせず、契約として 0 を返す）。</p>
     */
    @Test
    @DisplayName("AC-N1: ログイン記録ゼロのユーザーも bulk 結果に 0 日として載る（欠損させない）")
    void acN1_bulk_userWithoutLogins_isZeroFilled() {
        LocalDateTime nowUtc = nowUtc();
        Long active = persistActiveUser(TOKYO.getId());
        Long silent = persistActiveUser(TOKYO.getId());
        seedJstMidnightCrossing(active, nowUtc);

        Map<Long, Long> byUser = loginActivityQueryService
                .countDistinctActiveDaysWithinByUsers(List.of(active, silent), WINDOW_DAYS, nowUtc);

        assertThat(byUser)
                .as("記録ゼロのユーザーは欠損ではなく 0 で載せる")
                .containsEntry(active, 2L)
                .containsEntry(silent, 0L);
    }

    // ============================================================
    // Repository 契約: tzOffset パラメータそのものの意味づけ
    // ============================================================

    /**
     * AC-TZ1 補強（Repository 契約）: {@code AuditLogRepository#countDistinctLoginDaysSince} の
     * {@code tzOffset} は「UTC 格納値を当該オフセットへ変換してから日付を切る」ことを意味する。
     *
     * <p>同一データに対し {@code "+00:00"} なら 1 日・{@code "+09:00"} なら 2 日になることで、
     * オフセットが実際にクエリへ効いていることを直接固定する（サービス層の TZ 解決とは独立に検証）。</p>
     */
    @Test
    @DisplayName("AC-TZ1 補強: Repository の tzOffset が効く（同一データで +00:00 は 1 日・+09:00 は 2 日）")
    void repositoryContract_tzOffsetIsApplied() {
        LocalDateTime nowUtc = nowUtc();
        Long user = persistActiveUser(TOKYO.getId());
        seedJstMidnightCrossing(user, nowUtc);
        LocalDateTime since = nowUtc.minusDays(WINDOW_DAYS);

        // 格納基準（storedZoneOffset）は seed が生 UTC 文字列リテラルのため "+00:00" 固定。
        // 変換先（第4引数 tzOffset）だけを振って効果を検証する。
        assertThat(auditLogRepository.countDistinctLoginDaysSince(user, since, "+00:00", "+00:00"))
                .as("UTC で切れば 14:30 と 15:30 は同日")
                .isEqualTo(1L);
        assertThat(auditLogRepository.countDistinctLoginDaysSince(user, since, "+00:00", "+09:00"))
                .as("+09:00 で切れば 23:30 と翌 00:30 で別日")
                .isEqualTo(2L);
    }

    // ============================================================
    // AC-TZ6（Issue #2487 項目 5）: 30分/45分刻み・+13:00/+14:00 の特殊オフセット
    // ============================================================

    /**
     * AC-TZ6: <b>時間単位ではないオフセット（30 分 / 45 分刻み）</b>と、<b>+12:00 を超えるオフセット</b>でも
     * 現地の日境界が正しく切られる。
     *
     * <p>#2482 のテストが確認していたのは {@code +09:00} と {@code -07:00} の 2 値のみで、いずれも<b>時間単位</b>
     * かつ {@code ±12:00} の内側だった。オフセット文字列を組み立てる処理（{@code LoginActivityQueryService
     * #formatOffset}）は分まで扱う実装になっているが、それを課すテストが無く、<b>分を落として時間へ丸める
     * 退行を検出できない</b>状態だった。</p>
     *
     * <p>検証は 2 段構え:</p>
     * <ol>
     *   <li>現地 23:50 と翌現地 00:10 のログインが <b>2 日</b>と数えられること（サービス層）</li>
     *   <li>同じデータを {@code naiveOffset}（分を落として時間へ丸めた / {@code +12:00} へ丸めたオフセット）で
     *       切ると <b>1 日</b>に潰れること（Repository 層）。丸めが起きれば必ず落ちる＝分まで効いていることの裏取り</li>
     * </ol>
     *
     * <p>選んだ 4 ゾーンはいずれも現行 tzdb で夏時間を持たない（固定オフセット）。日境界の直近に seed するため、
     * 評価ウィンドウ内で切り替わる TZ を選ぶとフレークの原因になる。seed は<b>実装と同じく「now 時点の実オフセット」</b>
     * から現地時刻を組み立てるため、仮に将来 DST が導入されても seed と集計が同一オフセットで揃う。</p>
     */
    @ParameterizedTest(name = "AC-TZ6: {0} の現地日境界で activeDays=2（{1} へ丸めると 1 に潰れる）")
    @CsvSource({
            "Asia/Kolkata,       +05:00",
            "Asia/Kathmandu,     +05:00",
            "Pacific/Apia,       +12:00",
            "Pacific/Kiritimati, +13:00"
    })
    @DisplayName("AC-TZ6: 30分/45分刻み・+13:00/+14:00 のユーザーでも現地の日境界で数える")
    void acTz6_specialOffsets_countLocalDays(String zoneName, String naiveOffset) {
        ZoneId zone = ZoneId.of(zoneName);
        LocalDateTime nowUtc = nowUtc();
        Long user = persistActiveUser(zoneName);
        seedLocalMidnightCrossing(user, zone, nowUtc);

        long activeDays = loginActivityQueryService
                .countDistinctActiveDaysWithin(user, WINDOW_DAYS, nowUtc);

        assertThat(activeDays)
                .as("%s の現地 23:50 と翌 00:10 は別日（実オフセット %s）", zoneName, offsetAt(zone, nowUtc))
                .isEqualTo(2L);

        // 分を落として丸めたオフセットでは同日に潰れる＝オフセットが分単位まで効いていることの裏取り。
        assertThat(auditLogRepository.countDistinctLoginDaysSince(
                user, nowUtc.minusDays(WINDOW_DAYS), "+00:00", naiveOffset.trim()))
                .as("%s へ丸めると日境界がずれて 1 日に潰れる（この差が出なければ丸め退行を検出できない）", naiveOffset)
                .isEqualTo(1L);
    }

    /**
     * AC-TZ6 補強（bulk）: 特殊オフセットのユーザーを混在させて bulk を 1 回呼んでも、
     * <b>各自のオフセットで別々に</b>日が切られる（オフセット群の分け方が分単位で正しいこと）。
     *
     * <p>群分けは {@code formatOffset} の文字列をキーにしている。分を落とすと {@code Asia/Kolkata}（+05:30）と
     * {@code Asia/Kathmandu}（+05:45）が同一群に畳まれ、片方の日境界が壊れる。</p>
     */
    @Test
    @DisplayName("AC-TZ6 補強: +05:30 / +05:45 / +13:00 / +14:00 を混在させた bulk でも全員 2 日になる")
    void acTz6_bulk_mixedSpecialOffsets_areNotCollapsed() {
        LocalDateTime nowUtc = nowUtc();
        Map<String, Long> userByZone = new LinkedHashMap<>();
        for (String zoneName : List.of("Asia/Kolkata", "Asia/Kathmandu", "Pacific/Apia", "Pacific/Kiritimati")) {
            Long user = persistActiveUser(zoneName);
            seedLocalMidnightCrossing(user, ZoneId.of(zoneName), nowUtc);
            userByZone.put(zoneName, user);
        }

        Map<Long, Long> byUser = loginActivityQueryService.countDistinctActiveDaysWithinByUsers(
                List.copyOf(userByZone.values()), WINDOW_DAYS, nowUtc);

        assertThat(userByZone).allSatisfy((zoneName, userId) ->
                assertThat(byUser.get(userId))
                        .as("%s のユーザーは自分のオフセットで 2 日と数えられる", zoneName)
                        .isEqualTo(2L));
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    /** {@code nowUtc} 時点における当該ゾーンの実オフセット（実装の TZ 解決と同一規則）。 */
    private static ZoneOffset offsetAt(ZoneId zone, LocalDateTime nowUtc) {
        return zone.getRules().getOffset(nowUtc.toInstant(ZoneOffset.UTC));
    }

    /**
     * 当該ゾーンの<b>現地 23:50</b> と<b>翌現地 00:10</b> にログイン 2 件を仕込む（現地では別日・UTC では同日）。
     *
     * <p>現地時刻 → UTC の換算には実装と同じ「{@code nowUtc} 時点の実オフセット」を用いる。
     * こうすることで、集計側が {@code CONVERT_TZ} に渡すオフセットと seed のオフセットが必ず一致し、
     * 評価ウィンドウ内の DST 切替に左右されない。</p>
     */
    private void seedLocalMidnightCrossing(Long userId, ZoneId zone, LocalDateTime nowUtc) {
        ZoneOffset offset = offsetAt(zone, nowUtc);
        LocalDate localAnchor = nowUtc.atOffset(ZoneOffset.UTC)
                .withOffsetSameInstant(offset)
                .toLocalDate()
                .minusDays(ANCHOR_DAYS_AGO);
        insertLoginAtRawUtc(userId, toUtcWallClock(localAnchor.atTime(23, 50), offset));
        insertLoginAtRawUtc(userId, toUtcWallClock(localAnchor.plusDays(1).atTime(0, 10), offset));
    }

    /** 現地の壁時計 + オフセット → UTC の壁時計。 */
    private static LocalDateTime toUtcWallClock(LocalDateTime localWallClock, ZoneOffset offset) {
        return localWallClock.atOffset(offset).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** 評価基準時刻（UTC 壁時計）。注入 Clock のみから導き、システム既定 TZ に一切依存しない。 */
    private LocalDateTime nowUtc() {
        return LocalDateTime.now(clock).withNano(0);
    }

    /** 日境界フィクスチャの基準日（UTC 日付・過去かつウィンドウ内）。 */
    private LocalDate anchorDate(LocalDateTime nowUtc) {
        return nowUtc.toLocalDate().minusDays(ANCHOR_DAYS_AGO);
    }

    /**
     * 「UTC では同日 14:30 / 15:30、JST では 23:30 と翌 00:30」のログイン 2 件を仕込む。
     * AC-TZ1（JST=2 日）と AC-TZ4（LA=1 日）の共通 seed。
     */
    private void seedJstMidnightCrossing(Long userId, LocalDateTime nowUtc) {
        LocalDate anchor = anchorDate(nowUtc);
        insertLoginAtRawUtc(userId, anchor.atTime(14, 30));
        insertLoginAtRawUtc(userId, anchor.atTime(15, 30));
    }

    /**
     * {@code windowDays} 日前の <b>JST 00:00 ちょうど</b>を UTC の壁時計（アプリが扱う {@code LocalDateTime}）で返す。
     * 換算は {@link ZoneId} を明示した {@code java.time} 演算のみで行う。
     */
    private LocalDateTime jstWindowStartAsUtc(LocalDateTime nowUtc, int windowDays) {
        LocalDate jstToday = nowUtc.atZone(UTC).withZoneSameInstant(TOKYO).toLocalDate();
        return jstToday.minusDays(windowDays)
                .atStartOfDay(TOKYO)
                .withZoneSameInstant(UTC)
                .toLocalDateTime();
    }

    /** ACTIVE / 未削除の新規ユーザーを 1 件作成し、採番された id を返す。 */
    private Long persistActiveUser(String timezone) {
        UserEntity user = UserEntity.builder()
                .email("beta-tz-" + UUID.randomUUID() + "@example.com")
                .passwordHash("x")
                .lastName("時差").firstName("検証").displayName("時差検証")
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja").timezone(timezone).isSearchable(true)
                .build();
        return userRepository.save(user).getId();
    }

    /**
     * ログイン成功ログを <b>DB の生格納値が指定 UTC 壁時計そのもの</b>になるよう投入する。
     *
     * <p>日時を文字列リテラルとしてバインドするため、JDBC ドライバ／Hibernate の TZ 変換を一切通らない。
     * {@code CONVERT_TZ(created_at, '+00:00', :tz)} は<b>生格納値を UTC とみなす</b>ため、日境界の検証では
     * この経路でなければ検証対象そのもの（変換）が打ち消されてしまう
     * （テスト JVM は JST 固定・{@code hibernate.jdbc.time_zone=UTC} で 9h ズレが生じるため）。</p>
     */
    private void insertLoginAtRawUtc(Long userId, LocalDateTime utcWallClock) {
        jdbcTemplate.update(
                "INSERT INTO audit_logs (user_id, event_type, created_at) VALUES (?, ?, ?)",
                userId, LOGIN_SUCCESS, utcWallClock.format(MYSQL_DATETIME));
    }

    /**
     * ログイン成功ログを <b>本番クエリの {@code :since} と同一のバインド経路</b>（Hibernate の
     * {@code LocalDateTime} バインド）で投入する。
     *
     * <p>ウィンドウ起点の 1 秒境界（AC-TZ5）は、フィクスチャとクエリパラメータの TZ 経路が一致していないと
     * 9h ズレて偽陰性／偽陽性になる（memory {@code feedback_it_fixture_datetime_tz_bind}）。ネイティブクエリを
     * 使うのは {@code AuditLogEntity} の {@code @PrePersist} が {@code created_at} を上書きするため。</p>
     */
    private void insertLoginAtAppLocal(Long userId, LocalDateTime appLocalUtc) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "INSERT INTO audit_logs (user_id, event_type, created_at) VALUES (?1, ?2, ?3)")
                        .setParameter(1, userId)
                        .setParameter(2, LOGIN_SUCCESS)
                        .setParameter(3, appLocalUtc)
                        .executeUpdate());
    }
}
