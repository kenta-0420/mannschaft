package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.timeline.dto.TimelineContentFingerprint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** TL の完全一致比較専用。本人資格や初回勝者をここで決定しない。 */
@Service
@RequiredArgsConstructor
public class TimelineContentFingerprintService {
    static final String VERSION = "ranch-content-exact-v1";
    private final EncryptionService encryptionService;

    /** 本文を返却せず、現在鍵の比較証跡だけを返す。 */
    public TimelineContentFingerprint fingerprint(long recipientUserId, Instant occurredAt,
            String body, List<TimelineContentFingerprint.AttachmentRef> attachments) {
        LocalDate week = week(occurredAt);
        String tuple = canonicalTuple(recipientUserId, occurredAt, body, attachments);
        return new TimelineContentFingerprint(VERSION, week,
                encryptionService.hmac("ranch-content:key-id:v1"), encryptionService.hmac(tuple));
    }

    static String canonicalTuple(long userId, Instant at, String body,
            List<TimelineContentFingerprint.AttachmentRef> attachments) {
        if (userId <= 0 || body == null || attachments == null) throw invalid();
        List<String> sorted = new ArrayList<>(attachments.size());
        for (var ref : attachments) {
            if (ref == null || ref.idType() == null || ref.canonicalId() == null) throw invalid();
            String id = ref.canonicalId();
            if ("LONG".equals(ref.idType())) {
                try {
                    if (!id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) <= 0) throw invalid();
                } catch (NumberFormatException ignored) { throw invalid(); }
            } else if ("UUID".equals(ref.idType())) {
                try {
                    if (!UUID.fromString(id).toString().equals(id)) throw invalid();
                } catch (IllegalArgumentException ignored) { throw invalid(); }
            } else throw invalid();
            sorted.add(encoded(ref.idType(), id));
        }
        sorted.sort(String::compareTo);
        List<String> values = new ArrayList<>(List.of("ranch-content:digest:v1", VERSION,
                "TIMELINE_ORIGINAL", Long.toString(userId), week(at).toString(), "",
                normalize(body), Integer.toString(sorted.size())));
        values.addAll(sorted);
        return encoded(values.toArray(String[]::new));
    }

    static LocalDate week(Instant at) {
        if (at == null) throw invalid();
        return at.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    static String normalize(String text) {
        if (text == null) throw invalid();
        String value = Normalizer.normalize(text.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFC);
        int from = 0, to = value.length();
        while (from < to && whitespace(value.codePointAt(from)))
            from += Character.charCount(value.codePointAt(from));
        while (from < to && whitespace(value.codePointBefore(to)))
            to -= Character.charCount(value.codePointBefore(to));
        return value.substring(from, to);
    }

    private static boolean whitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static String encoded(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values)
            result.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        return result.toString();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid content fingerprint input");
    }
}
