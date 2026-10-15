package com.mannschaft.app.config.webmvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * クエリパラメータの日時バインディング（{@code @RequestParam}）を検証するための観測用コントローラ。
 *
 * <p>本番コントローラ群に実在する <b>3 種類の受け方</b>を同じ形で並べ、
 * {@link com.mannschaft.app.config.WebMvcConfig#addFormatters} が組み立てる
 * {@code FormattingConversionService} を通ったあとに<b>コントローラへ実際に届いた値</b>を
 * 文字列としてそのまま返す。</p>
 *
 * <ul>
 *   <li>A 種別 — {@code @DateTimeFormat(iso = DATE_TIME)}
 *       （例: {@code GuardianChildViewController} / {@code AuditLogScopeController}）</li>
 *   <li>B 種別 — アノテーション無し
 *       （例: {@code MatchStatsController} / {@code MatchRecordController}）</li>
 *   <li>C 種別 — {@code @DateTimeFormat(pattern = ...)}
 *       （例: {@code UserController#207}）</li>
 * </ul>
 *
 * <p>戻り値は {@code text/plain} の素の文字列である。Jackson の
 * {@link com.mannschaft.app.config.jackson.LocalDateTimeTimezoneSerializer} を経由すると
 * 出力側でも TZ 変換が掛かり「入力側の解釈」を観測できなくなるため、
 * 意図的にメッセージコンバータを通さない形にしている。</p>
 */
@RestController
@RequestMapping("/__test__/query-param-datetime")
public class QueryParamDateTimeProbeController {

    /** A 種別: ISO DATE_TIME 指定。 */
    @GetMapping(value = "/iso", produces = "text/plain;charset=UTF-8")
    public String iso(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime value) {
        return value.toString();
    }

    /** B 種別: アノテーション無し。 */
    @GetMapping(value = "/plain", produces = "text/plain;charset=UTF-8")
    public String plain(@RequestParam LocalDateTime value) {
        return value.toString();
    }

    /** C 種別: pattern 指定。 */
    @GetMapping(value = "/pattern", produces = "text/plain;charset=UTF-8")
    public String pattern(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime value) {
        return value.toString();
    }

    /**
     * 範囲検索の境界挙動を観測する。
     *
     * <p>Spring Data の {@code ...StartAtBetween...} と同じ<b>両端 inclusive</b> の述語を
     * 固定フィクスチャに適用し、ヒットしたものを返す。ここで可変なのは
     * {@code from} / {@code to} の<b>バインド結果</b>だけなので、
     * クエリパラメータの解釈が変わって境界レコードが範囲から外れれば、このテストが落ちる。</p>
     */
    @GetMapping(value = "/range", produces = "text/plain;charset=UTF-8")
    public String range(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        List<LocalDateTime> fixtures = List.of(
                LocalDateTime.parse("2027-03-14T23:59:59"),
                LocalDateTime.parse("2027-03-15T00:00:00"),
                LocalDateTime.parse("2027-03-15T12:00:00"),
                LocalDateTime.parse("2027-03-15T23:59:59"),
                LocalDateTime.parse("2027-03-16T00:00:00"));
        return fixtures.stream()
                .filter(t -> !t.isBefore(from) && !t.isAfter(to))
                .map(LocalDateTime::toString)
                .collect(Collectors.joining(","));
    }

    /** {@link LocalDate} の受け口（{@code ActivityStatsController} 等と同じ形）。 */
    @GetMapping(value = "/date-iso", produces = "text/plain;charset=UTF-8")
    public String dateIso(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate value) {
        return value.toString();
    }

    /** {@link LocalDate} のアノテーション無し受け口（{@code ActivityStatsController#getStats} と同じ形）。 */
    @GetMapping(value = "/date-plain", produces = "text/plain;charset=UTF-8")
    public String datePlain(@RequestParam LocalDate value) {
        return value.toString();
    }
}
