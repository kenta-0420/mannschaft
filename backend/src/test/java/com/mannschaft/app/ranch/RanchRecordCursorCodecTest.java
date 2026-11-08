package com.mannschaft.app.ranch;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.ranch.service.RanchRecordCursorCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 私的一覧cursorは本人・MICROS・署名を保持し、他人再利用と改ざんを拒否する。 */
class RanchRecordCursorCodecTest {
    private final RanchRecordCursorCodec codec = new RanchRecordCursorCodec(
            new EncryptionService(new byte[32], new byte[32]));

    @Test
    void cursorIsBoundToUserAndRejectsTampering() {
        Instant at = Instant.parse("2026-10-04T02:00:00.123456Z");
        UUID id = UUID.randomUUID();
        String encoded = codec.encode(17L, at, id);
        assertThat(codec.decode(17L, encoded))
                .isEqualTo(new RanchRecordCursorCodec.Position(at, id));
        assertThatThrownBy(() -> codec.decode(18L, encoded))
                .isInstanceOf(BusinessException.class);
        String tampered = (encoded.charAt(0) == 'A' ? 'B' : 'A')
                + encoded.substring(1);
        assertThatThrownBy(() -> codec.decode(17L, tampered))
                .isInstanceOf(BusinessException.class);
        String inventoryCursor = codec.encodeInventory(17L, at, id);
        assertThat(codec.decodeInventory(17L, inventoryCursor))
                .isEqualTo(new RanchRecordCursorCodec.Position(at, id));
        assertThatThrownBy(() -> codec.decode(17L, inventoryCursor))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.decodeInventory(17L, encoded))
                .isInstanceOf(BusinessException.class);
    }
}
