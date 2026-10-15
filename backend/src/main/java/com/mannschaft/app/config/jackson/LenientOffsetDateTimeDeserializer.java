package com.mannschaft.app.config.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * オフセット付き / オフセット無しのどちらの ISO-8601 文字列も受け取れる {@link OffsetDateTime} デシリアライザ。
 *
 * <p><b>用途</b>: 元々 {@code LocalDateTime}（オフセット無し）で宣言されていたフィールドを
 * {@link OffsetDateTime} へ移行する際、<b>旧形式の入力を壊さない</b>ために使う。
 * 標準の Jackson デシリアライザは {@code "2026-08-01T09:00:00"}（オフセット無し）を
 * {@link OffsetDateTime} として解釈できず 400 になるため、移行の障壁になる。</p>
 *
 * <p><b>解釈規則</b>:</p>
 * <ol>
 *   <li>オフセット付き（{@code +09:00} / {@code Z}）→ そのまま {@link OffsetDateTime} として解釈する</li>
 *   <li>オフセット無し → {@link #FALLBACK_ZONE}（サーバー既定 TZ = Asia/Tokyo）の時刻として解釈する。
 *       DB に保存されている日時は JST の {@code LocalDateTime} であり、旧クライアントも JST 前提で
 *       送信していたため、この解釈が旧挙動と一致する</li>
 *   <li>どちらとしても解釈できない → {@link InvalidFormatException} を投げる
 *       （不正入力を握り潰さない。400 として正しく失敗させる）</li>
 * </ol>
 */
public class LenientOffsetDateTimeDeserializer extends JsonDeserializer<OffsetDateTime> {

    /** オフセット無し文字列を解釈する際に補うタイムゾーン（サーバー既定 TZ）。 */
    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Tokyo");

    @Override
    public OffsetDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String raw = p.getValueAsString();
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }

        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException withOffsetFailed) {
            try {
                // 後方互換: オフセット無しは JST として解釈する
                return LocalDateTime.parse(text).atZone(FALLBACK_ZONE).toOffsetDateTime();
            } catch (DateTimeParseException withoutOffsetFailed) {
                throw InvalidFormatException.from(p,
                        "日時として解釈できません（ISO-8601 形式で指定してください）: " + text,
                        text, OffsetDateTime.class);
            }
        }
    }
}
