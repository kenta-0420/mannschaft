package com.mannschaft.app.config.webmvc;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;

import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.springframework.format.Formatter;

/**
 * クエリパラメータ・パス変数の {@link LocalDateTime} を、リクエストボディ（Jackson）と
 * <b>同じ規則</b>でサーバー保持形式（Asia/Tokyo の壁時計）へ解釈する Formatter（Issue #2508 Phase 1）。
 *
 * <h2>なぜ必要か</h2>
 *
 * <p>{@code @RequestParam} は Jackson を通らず Spring の {@code ConversionService} を通るため、
 * ボディ側の {@code LocalDateTimeTimezoneDeserializer} が効かない。標準の日時変換は
 * <b>オフセットを黙って捨てる</b>ので、ロサンゼルスのユーザーが送った {@code 10:30-07:00} と
 * 日本のユーザーが送った {@code 10:30+09:00} が同じ {@code 10:30} に潰れ、
 * 検索レンジが無言で 16 時間ずれていた。</p>
 *
 * <h2>登録のされ方</h2>
 *
 * <p>{@link com.mannschaft.app.config.WebMvcConfig#addFormatters} で
 * {@code addFormatterForFieldType(LocalDateTime.class, …)} として 1 本だけ登録する。
 * これは {@code @DateTimeFormat(iso = …)} / {@code @DateTimeFormat(pattern = …)} /
 * アノテーション無し の<b>いずれの受け方も</b>この Formatter が担当することを実測で確認している。
 * よってコントローラ側の {@code @DateTimeFormat} は一切変更していない。
 * （{@code addFormatterForFieldAnnotation} は fieldType 側の登録に負けて呼ばれないため使わない。）</p>
 *
 * <p>解釈規則そのものは持たず、{@link UserZoneLocalDateTimeParser} に委譲する。
 * ボディ側と規則がずれることを構造的に防ぐためである。</p>
 */
public class UserZoneLocalDateTimeFormatter implements Formatter<LocalDateTime> {

    /**
     * クエリパラメータ文字列を解釈する。
     *
     * <p>解釈できない場合は {@link ParseException} を投げる。Spring はこれを
     * {@code ConversionFailedException} 経由で {@code MethodArgumentTypeMismatchException} に包み、
     * HTTP 400 として応答する（500 に化けない）。</p>
     */
    @Override
    public LocalDateTime parse(String text, Locale locale) throws ParseException {
        String trimmed = text == null ? "" : text.trim();
        try {
            return UserZoneLocalDateTimeParser.parse(trimmed);
        } catch (DateTimeParseException unparseable) {
            throw new ParseException(
                    "日時として解釈できません（ISO-8601 形式で指定してください）: " + trimmed,
                    unparseable.getErrorIndex());
        }
    }

    /**
     * 出力方向。従来の {@code ISO_LOCAL_DATE_TIME} と同じ文字列を返し、既存挙動を変えない
     * （クエリパラメータの print はほとんど使われないが、契約として明示しておく）。
     */
    @Override
    public String print(LocalDateTime object, Locale locale) {
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(object);
    }
}
