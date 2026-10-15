package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.ranch.service.RanchAdminCursorCodec;
import com.mannschaft.app.ranch.service.RanchAdminInputParser;
import com.mannschaft.app.ranch.service.RanchAdminPublicationCalendar;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 管理入力とcursorの公開境界。DB書込み・認可・実機の証明とは分離する。 */
class RanchAdminBoundaryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RanchAdminInputParser parser = new RanchAdminInputParser();

    @Test
    void rejectsActorInjectionAndNonCanonicalNumbers() {
        ObjectNode body = care();
        body.put("actorUserId", "9");
        assertThatThrownBy(() -> parser.care(body)).isInstanceOf(BusinessException.class);
        body.remove("actorUserId");
        body.put("amountXp", "01");
        assertThatThrownBy(() -> parser.care(body)).isInstanceOf(BusinessException.class);
        body.put("amountXp", "9223372036854775808");
        assertThatThrownBy(() -> parser.care(body)).isInstanceOf(BusinessException.class);
        body.put("amountXp", Long.toString(Long.MAX_VALUE));
        assertThat(parser.care(body).amountXp()).isEqualTo(Long.toString(Long.MAX_VALUE));
    }

    @Test
    void pauseReasonHasItsOwnFortyCharacterLimit() {
        ObjectNode body = json.createObjectNode().put("version", "0")
                .put("isCareEnabled", false).put("isShopEnabled", false)
                .put("isDeliveryPaused", true).put("isRewardsPaused", true)
                .put("reasonCode", "A".repeat(40));
        assertThat(parser.controls(body).reasonCode()).hasSize(40);
        body.put("reasonCode", "A".repeat(41));
        assertThatThrownBy(() -> parser.controls(body)).isInstanceOf(BusinessException.class);
        ObjectNode care = care().put("reasonCode", "A".repeat(80));
        assertThat(parser.care(care).reasonCode()).hasSize(80);
    }

    @Test
    void onlyFutureUtcWeekBoundariesAreAccepted() {
        Instant now = Instant.parse("2026-10-05T00:00:00Z");
        RanchAdminPublicationCalendar.requireFutureWeek(Instant.parse("2026-10-12T00:00:00Z"), now);
        assertThatThrownBy(() -> RanchAdminPublicationCalendar.requireFutureWeek(now, now))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> RanchAdminPublicationCalendar.requireFutureWeek(Instant.parse("2026-10-12T00:00:00.000001Z"), now))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> RanchAdminPublicationCalendar.requireFutureWeek(Instant.MAX, now))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void signedCursorRejectsAnotherActorPurposeAndTampering() {
        var codec = new RanchAdminCursorCodec(new EncryptionService(new byte[32], new byte[32]));
        String token = codec.encode(17L, RanchAdminCursorCodec.Kind.CARE, 12L);
        assertThat(codec.decode(17L, RanchAdminCursorCodec.Kind.CARE, token)).isEqualTo(12L);
        assertThatThrownBy(() -> codec.decode(18L, RanchAdminCursorCodec.Kind.CARE, token)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.decode(17L, RanchAdminCursorCodec.Kind.POLICY, token)).isInstanceOf(BusinessException.class);
        String changed = (token.charAt(0) == 'A' ? 'B' : 'A') + token.substring(1);
        assertThatThrownBy(() -> codec.decode(17L, RanchAdminCursorCodec.Kind.CARE, changed)).isInstanceOf(BusinessException.class);
    }

    private ObjectNode care() {
        return json.createObjectNode().put("effectiveAt", "2026-10-12T00:00:00Z")
                .put("amountXp", "20").put("weeklyCapXp", "100")
                .put("juvenileXp", "60").put("adultXp", "100").put("reasonCode", "INITIAL_CARE");
    }
}
