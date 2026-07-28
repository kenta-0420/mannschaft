package com.mannschaft.app.billing.beta;

import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.common.timezone.UserTimezoneCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F20.3 ベータ特典: 個人の {@code activeDays}（アクティブ日数）計測サービス（設計書 02 §2・README §7）。
 *
 * <p><b>唯一の計測源</b>: {@code audit_logs} の {@code LOGIN_SUCCESS} を
 * {@code COUNT(DISTINCT DATE(...))} で数える（F10.8 {@code page_view_logs} は TEAM/ORG スコープ
 * 限定で USER を持たないため使えない・README §7）。</p>
 *
 * <h3>日境界は「ユーザー各自のタイムゾーン」で切る（2026-07-28 是正）</h3>
 * <p>{@code audit_logs.created_at} は UTC 格納である。素の {@code DATE(created_at)} で数えると日境界が UTC に寄り、
 * 例えば JST 23:30 と翌 JST 00:30 のログイン（＝同一 UTC 日）が <b>1 日</b>に潰れる。付与可否を直接左右するため、
 * 本サービスは以下の 2 点を<b>ユーザーの {@code users.timezone}</b> に基づいて解決する:</p>
 * <ol>
 *   <li><b>日付の切り出し</b> — {@code CONVERT_TZ(created_at, '+00:00', :tzOffset)}（{@link AuditLogRepository}）。
 *       {@code tzOffset} は<b>その瞬間の実オフセット</b>（夏時間を含む。7 月の {@code America/Los_Angeles} は
 *       {@code -07:00}）を {@code "+09:00"} 形式で渡す。</li>
 *   <li><b>評価ウィンドウ起点（{@code since}）</b> — 「ユーザー現地の当日 00:00」から {@code windowDays} 日前の
 *       00:00 を UTC へ直した値。リポジトリへは <b>UTC のまま</b>渡す（{@code created_at >= :since} は UTC 同士の
 *       比較であり、変換するとインデックスが効かなくなる）。</li>
 * </ol>
 *
 * <h3>N+1 回避（bulk の群分け）</h3>
 * <p>bulk 版はユーザー TZ を {@link UserTimezoneCache#getTimezones} で 1 回に畳んで解決し、
 * <b>同一オフセットのユーザーを 1 群に束ねて群ごとに 1 クエリ</b>だけ撃つ（ユーザー数に比例したクエリを作らない）。
 * 群内の {@code since} は当該オフセットから導くため群内で一意である。</p>
 *
 * <p><b>DST の扱い（既知の割り切り）</b>: 群の {@code since} は「現在のオフセット」で当日 00:00 を求める。評価
 * ウィンドウ（既定 60 日）が夏時間切替を跨ぐ場合、起点が真の現地 00:00 から最大 1 時間ずれる。日数カウント側
 * （{@code CONVERT_TZ}）はレコードごとに正しいオフセットで切られるため影響はなく、起点の 1 時間差が 60 日窓の
 * 判定を変える確率は無視できる。ここを厳密化すると群分けが崩れ N+1 に戻るため、意図的にこの精度で固定する。</p>
 *
 * <p><b>TZ 解決失敗を握り潰さない</b>: {@link UserTimezoneCache} が例外を投げた場合はそのまま伝播させる
 * （「TZ が引けないので全員 0 日」は付与漏れを静かに生む対処療法・CLAUDE.md 障害対応の原則）。一方、
 * <b>値が不正な IANA 名 / 空 / NULL</b> の場合は例外ではなく既定 {@code Asia/Tokyo} へフォールバックし WARN を残す
 * （データ品質の問題であり、集計を止める理由にはならない）。</p>
 *
 * <p><b>クロスドメイン方針（{@code ScopeMemberCountService} と同型）</b>: 本サービスは auth ドメインの
 * {@link AuditLogRepository} を read-only 参照するが、
 * <ul>
 *   <li><b>{@code @Transactional} を付けない</b> — クラスに {@code @Transactional} が無ければ
 *       クロスドメイン {@code @Transactional} 番人（D-3）に抵触しない。呼び出し元
 *       （{@link BetaPerkEligibilityService} / {@link BetaGrantService}）の tx 境界に読み取りだけ参加する。</li>
 *   <li><b>{@code AuditLogEntity} を import しない</b> — リポジトリは scalar（{@code long}）を返すため、
 *       クロスドメイン Entity 参照番人（D-1）にも抵触しない。TZ 解決も auth の Repository を直接触らず
 *       {@code common} の {@link UserTimezoneCache} 経由で行う（D-5 の越境 Repository 依存を増やさない）。</li>
 * </ul></p>
 *
 * <h3>{@code audit_logs.created_at} の格納基準 TZ は設定値（2026-07-28 是正）</h3>
 * <p>{@link AuditLogRepository} の {@code CONVERT_TZ(created_at, storedZoneOffset, tzOffset)} における
 * <b>変換元</b> {@code storedZoneOffset} は、{@code audit_logs.created_at} が<b>どの TZ の壁時計として
 * 格納されているか</b>を表す前提値であり、以前は SQL に {@code '+00:00'} 直書きだった。この前提は
 * {@code spring.jpa.properties.hibernate.jdbc.time_zone} と JVM 既定 TZ に依存し、<b>環境によって割れている
 * 可能性がある</b>（現状 {@code test} プロファイルにのみ {@code hibernate.jdbc.time_zone: UTC} の明示指定があり、
 * 本番の格納基準は未決着）。そこで本サービスはこの値をリテラルではなく設定キー
 * {@code mannschaft.audit.stored-zone-offset}（既定 {@code "+00:00"}＝UTC・{@code application.yml} 参照）から
 * {@code @Value} で受け取り、リポジトリへバインドパラメータとして渡す。格納基準が UTC でない環境では、
 * この設定値を実態に合わせて上書きすることで、SQL を触らずに前提を差し替えられる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginActivityQueryService {

    /** TZ が不正 / 未設定のときの既定タイムゾーン（アプリ全体の既定と一致）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Tokyo");

    private final AuditLogRepository auditLogRepository;
    private final UserTimezoneCache userTimezoneCache;

    /**
     * {@code audit_logs.created_at} の格納基準 TZ（{@code "+00:00"} 形式・既定 UTC）。
     * {@code CONVERT_TZ} の変換元として {@link AuditLogRepository} へそのまま渡す。
     * 既定値のみで運用する限り挙動は変わらない（{@code application.yml} の
     * {@code mannschaft.audit.stored-zone-offset} 参照）。
     */
    @Value("${mannschaft.audit.stored-zone-offset:+00:00}")
    private String storedZoneOffset;

    /**
     * 指定ユーザーの、直近 {@code windowDays} 日間のアクティブ日数（ログイン成功日の distinct 数）を返す。
     *
     * <p>日境界・ウィンドウ起点はいずれも<b>当該ユーザーのタイムゾーン</b>で切る（bulk 版と同一規則）。
     * 両経路で規則がずれると「本人の進捗表示では 14 日なのに自動付与されない」不整合が生まれるため、
     * 境界の求め方は {@link #windowStartUtc} に一本化している。</p>
     *
     * @param userId     対象ユーザー（個人特典の scope_id）
     * @param windowDays 評価ウィンドウ（日数）
     * @param nowUtc     評価基準時刻（<b>UTC の壁時計</b>。呼び出し側は UTC の {@code Clock} から導くこと）
     * @return アクティブ日数
     */
    public long countDistinctActiveDaysWithin(Long userId, int windowDays, LocalDateTime nowUtc) {
        if (userId == null || nowUtc == null) {
            return 0L;
        }
        ZoneOffset offset = resolveOffset(userTimezoneCache.getTimezone(userId), nowUtc);
        return auditLogRepository.countDistinctLoginDaysSince(
                userId, windowStartUtc(offset, windowDays, nowUtc), storedZoneOffset, formatOffset(offset));
    }

    /**
     * 複数ユーザーのアクティブ日数を <b>オフセット群ごとに 1 クエリ</b>で一括取得する
     * （F20.3 Phase2 自動付与バッチの N+1 回避）。
     *
     * <p>{@link #countDistinctActiveDaysWithin} の bulk 版。TZ 解決は 1 回、集計クエリは<b>登場したオフセットの
     * 種類数</b>だけ（ユーザー数には比例しない）。<b>ログイン記録の無いユーザーも 0 日として Map に載せる</b>
     * （「Map に無い＝未計測なのか 0 日なのか」の曖昧さを契約で潰す）。</p>
     *
     * @param userIds    対象ユーザーID群（null/空なら空 Map を返す＝{@code IN ()} 不正 SQL を防ぐ）
     * @param windowDays 評価ウィンドウ（日数）
     * @param nowUtc     評価基準時刻（UTC の壁時計）
     * @return userId → アクティブ日数（<b>全 userId 分・記録の無いユーザーは 0</b>）
     */
    public Map<Long, Long> countDistinctActiveDaysWithinByUsers(
            Collection<Long> userIds, int windowDays, LocalDateTime nowUtc) {

        Map<Long, Long> result = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty() || nowUtc == null) {
            return result;
        }
        // 重複除去（同一 userId を 2 度数えない）＋ 0 埋めの土台を先に作る。
        Set<Long> targets = new LinkedHashSet<>();
        for (Long userId : userIds) {
            if (userId != null) {
                targets.add(userId);
            }
        }
        if (targets.isEmpty()) {
            return result;
        }
        targets.forEach(userId -> result.put(userId, 0L));

        // TZ 解決は 1 回。失敗（例外）は握り潰さず伝播させる（0 日扱いへの黙殺を禁じる）。
        Map<Long, String> timezones = userTimezoneCache.getTimezones(targets);

        // 同一オフセットのユーザーを 1 群に束ねる（群数 = クエリ本数）。
        Map<String, List<Long>> userIdsByOffset = new LinkedHashMap<>();
        for (Long userId : targets) {
            String offsetId = formatOffset(resolveOffset(timezones.get(userId), nowUtc));
            userIdsByOffset.computeIfAbsent(offsetId, key -> new ArrayList<>()).add(userId);
        }

        for (Map.Entry<String, List<Long>> group : userIdsByOffset.entrySet()) {
            String offsetId = group.getKey();
            LocalDateTime since = windowStartUtc(ZoneOffset.of(offsetId), windowDays, nowUtc);
            for (Object[] row : auditLogRepository.countDistinctLoginDaysSinceByUsers(
                    group.getValue(), since, storedZoneOffset, offsetId)) {
                result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            }
        }
        return result;
    }

    /**
     * 評価ウィンドウ起点を返す:「そのオフセットにおける当日 00:00」から {@code windowDays} 日前の 00:00 を
     * UTC の壁時計へ直した値。
     *
     * <p>例: {@code nowUtc=2026-07-28T01:00}（＝JST 7/28 10:00）・{@code offset=+09:00}・{@code windowDays=60}
     * → JST 2026-05-29 00:00 → <b>UTC {@code 2026-05-28T15:00}</b>。当日の途中（10:00）を日頭へ丸め、
     * windowDays を<b>現地日付で</b>引き、最後に UTC へ戻す。</p>
     */
    private static LocalDateTime windowStartUtc(ZoneOffset offset, int windowDays, LocalDateTime nowUtc) {
        LocalDate localToday = nowUtc.atOffset(ZoneOffset.UTC).withOffsetSameInstant(offset).toLocalDate();
        return localToday.minusDays(windowDays)
                .atStartOfDay()
                .atOffset(offset)
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }

    /**
     * IANA タイムゾーン名を、{@code nowUtc} 時点の<b>実オフセット</b>へ解決する（夏時間を反映）。
     *
     * <p>不正な名前・空文字・NULL は既定 {@code Asia/Tokyo} へフォールバックする（AC-TZ3）。
     * 固定オフセット（常に {@code -08:00} 等）に丸めず、必ずその瞬間のルールを引くこと。</p>
     */
    private static ZoneOffset resolveOffset(String timezone, LocalDateTime nowUtc) {
        return toZoneId(timezone).getRules().getOffset(nowUtc.toInstant(ZoneOffset.UTC));
    }

    /** IANA 名 → {@link ZoneId}。不正値は既定へフォールバックし WARN で可視化する（黙って握り潰さない）。 */
    private static ZoneId toZoneId(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException ex) {
            log.warn("activeDays 集計: 不正なタイムゾーン '{}' を既定 {} として扱う", timezone, DEFAULT_ZONE, ex);
            return DEFAULT_ZONE;
        }
    }

    /**
     * MySQL {@code CONVERT_TZ} へ渡す数値オフセット文字列（{@code "+09:00"} / {@code "-07:00"}）へ整形する。
     *
     * <p>{@link ZoneOffset#getId()} は UTC を {@code "Z"} と表すが、{@code CONVERT_TZ} は {@code "Z"} を解釈できず
     * <b>黙って NULL を返して集計が 0 になる</b>ため、常に符号付き {@code ±HH:mm} へ自前で整形する。</p>
     */
    private static String formatOffset(ZoneOffset offset) {
        int totalSeconds = offset.getTotalSeconds();
        int absSeconds = Math.abs(totalSeconds);
        return String.format("%s%02d:%02d",
                totalSeconds < 0 ? "-" : "+", absSeconds / 3600, (absSeconds % 3600) / 60);
    }
}
