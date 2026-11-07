package com.mannschaft.app.timeline.service;

import com.mannschaft.app.timeline.dto.TimelineContentFingerprint.AttachmentRef;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 正準化の純粋試験。本人資格、暗号鍵、DB 勝者選定の証明にはしない。 */
class TimelineContentFingerprintServiceTest {
    private static final Instant TIME = Instant.parse("2026-10-04T23:59:59.999999Z");

    @Test void nfcAndNewlinesAndOuterUnicodeWhitespace() {
        assertThat(TimelineContentFingerprintService.normalize("\u3000e\u0301\r\nnext\rline\u00a0"))
                .isEqualTo("é\nnext\nline");
        assertThat(tuple(7, TIME, "a  b", List.of()))
                .isNotEqualTo(tuple(7, TIME, "a b", List.of()));
    }

    @Test void utcWeekBoundaryUsesNativeTime() {
        assertThat(TimelineContentFingerprintService.week(TIME)).isEqualTo(LocalDate.of(2026,9,28));
        assertThat(TimelineContentFingerprintService.week(TIME.plusNanos(1000)))
                .isEqualTo(LocalDate.of(2026,10,5));
    }

    @Test void attachmentOrderIsIgnoredAndMultiplicityIsRetained() {
        var one = new AttachmentRef("LONG", "12");
        var two = new AttachmentRef("UUID", "00000000-0000-0000-0000-000000000001");
        assertThat(tuple(7,TIME,"text",List.of(one,two)))
                .isEqualTo(tuple(7,TIME,"text",List.of(two,one)));
        assertThat(tuple(7,TIME,"text",List.of(one,one)))
                .isNotEqualTo(tuple(7,TIME,"text",List.of(one)));
    }

    @Test void userAndWeekArePartOfComparison() {
        assertThat(tuple(7,TIME,"text",List.of())).isNotEqualTo(tuple(8,TIME,"text",List.of()));
        assertThat(tuple(7,TIME,"text",List.of()))
                .isNotEqualTo(tuple(7,TIME.plusSeconds(604800),"text",List.of()));
    }

    @Test void invalidAttachmentDoesNotAppearInException() {
        String marker="synthetic-private-marker";
        assertThatThrownBy(() -> tuple(7,TIME,"text",List.of(new AttachmentRef("LONG",marker))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid content fingerprint input")
                .hasNoCause();
    }

    private static String tuple(long user, Instant time, String body, List<AttachmentRef> refs) {
        return TimelineContentFingerprintService.canonicalTuple(user,time,body,refs);
    }
}
