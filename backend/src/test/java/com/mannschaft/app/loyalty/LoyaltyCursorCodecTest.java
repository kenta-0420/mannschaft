package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.EncryptionService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC14/30の署名・本人/TEAM/filter/並び順束縛。実HMACを使いsecurity mockを置かない。 */
class LoyaltyCursorCodecTest {
    private final LoyaltyCursorCodec codec = new LoyaltyCursorCodec(new EncryptionService(new byte[32], new byte[32]));
    private final UUID id = UUID.fromString("019a1000-0000-7000-8000-000000000001");
    private final LocalDateTime expires = LocalDateTime.of(2026, 10, 16, 12, 30);
    private LoyaltyCursorCodec.Context context(LoyaltyCursorCodec.Stream stream) {
        return new LoyaltyCursorCodec.Context(stream, 10L,
                stream == LoyaltyCursorCodec.Stream.ACCOUNTS || stream == LoyaltyCursorCodec.Stream.MY_REQUESTS ? null : 20L);
    }
    private LoyaltyCursorCodec.Position position(LoyaltyCursorCodec.Stream stream) {
        return new LoyaltyCursorCodec.Position(id, stream == LoyaltyCursorCodec.Stream.STAFF_PENDING ? expires : null);
    }

    @ParameterizedTest
    @EnumSource(LoyaltyCursorCodec.Stream.class)
    void AC14_全seek並びで最終位置を往復できる(LoyaltyCursorCodec.Stream stream) {
        String cursor = codec.encode(context(stream), position(stream));
        assertThat(cursor).hasSizeLessThan(512);
        assertThat(codec.decode(context(stream), cursor)).isEqualTo(position(stream));
    }

    @Test
    void AC30_本人TEAMfilter並びを変更したcursorは400() {
        var scope = context(LoyaltyCursorCodec.Stream.STAFF_PENDING);
        String cursor = codec.encode(scope, position(scope.stream()));
        rejected(new LoyaltyCursorCodec.Context(scope.stream(), 11L, 20L), cursor);
        rejected(new LoyaltyCursorCodec.Context(scope.stream(), 10L, 21L), cursor);
        rejected(context(LoyaltyCursorCodec.Stream.STAFF_ALL), cursor);
        rejected(context(LoyaltyCursorCodec.Stream.TRANSACTIONS), cursor);
    }

    @Test
    void AC14_署名とpayloadの改変と別鍵を拒否する() {
        var scope = context(LoyaltyCursorCodec.Stream.TRANSACTIONS);
        String cursor = codec.encode(scope, position(scope.stream()));
        rejected(scope, (cursor.charAt(0) == 'A' ? "B" : "A") + cursor.substring(1));
        int signature = cursor.indexOf('.') + 1;
        rejected(scope, cursor.substring(0, signature) + (cursor.charAt(signature) == 'A' ? "B" : "A") + cursor.substring(signature + 1));
        byte[] otherKey = new byte[32]; otherKey[0] = 1;
        LoyaltyCursorCodec differentKey = new LoyaltyCursorCodec(new EncryptionService(new byte[32], otherKey));
        assertThatThrownBy(() -> differentKey.decode(scope, cursor)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_001);
    }

    @Test
    void AC14_空形式不正と巨大cursorは値をコピーせず入力不正にする() {
        var scope = context(LoyaltyCursorCodec.Stream.MY_REQUESTS);
        for (String cursor : new String[]{"", " ", "not-base64", "a.b.c", "a".repeat(513)}) rejected(scope, cursor);
    }

    @Test
    void AC30_pending位置には期限が必須で通常seekへ期限を混ぜない() {
        assertThatThrownBy(() -> codec.encode(context(LoyaltyCursorCodec.Stream.STAFF_PENDING), new LoyaltyCursorCodec.Position(id, null)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_001);
        assertThatThrownBy(() -> codec.encode(context(LoyaltyCursorCodec.Stream.STAFF_ALL), new LoyaltyCursorCodec.Position(id, expires)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_001);
    }

    private void rejected(LoyaltyCursorCodec.Context scope, String cursor) {
        assertThatThrownBy(() -> codec.decode(scope, cursor)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_001);
    }
}
