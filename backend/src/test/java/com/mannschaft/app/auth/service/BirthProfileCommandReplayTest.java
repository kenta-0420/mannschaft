package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.BirthProfileCommandEntity;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 成功履歴の拒否順と鍵交代分類だけを検証する。実API再送の証拠は別MySQL ITで確認する。 */
class BirthProfileCommandReplayTest {
    private static final String KEY = "a".repeat(64);
    private static final String OTHER_KEY = "b".repeat(64);
    private static final String HASH = "c".repeat(64);
    private BirthProfileCommandEntity saved(String type, String key) {
        return BirthProfileCommandEntity.builder().commandType(type).requestKeyId(key).requestHash(HASH).build();
    }
    private void rejected(String code, BirthProfileCommandEntity saved, String type, String key, String hash) {
        assertThatThrownBy(() -> BirthProfileCommandReplay.verify(saved, type, key, hash))
                .isInstanceOf(BusinessException.class).satisfies(error ->
                    org.assertj.core.api.Assertions.assertThat(((BusinessException) error).getErrorCode().getCode()).isEqualTo(code));
    }
    @Test @DisplayName("旧種別NULLはJSON形や鍵識別がRAWらしくても006")
    void unknownHistoryRejectedBeforeKeyClassification() {
        rejected("BIRTHPROFILE_006", saved(null, null), BirthProfileCommandReplay.RAW_PROFILE_PUT, KEY, HASH);
        rejected("BIRTHPROFILE_006", saved(null, KEY), BirthProfileCommandReplay.RAW_PROFILE_PUT, KEY, HASH);
    }
    @Test @DisplayName("種別違いは鍵欠損や本文違いより先に006")
    void differentTypeRejectedFirst() {
        rejected("BIRTHPROFILE_006", saved(BirthProfileCommandReplay.CONFIRM_BIRTH_STYLE, null),
                BirthProfileCommandReplay.RAW_PROFILE_PUT, KEY, OTHER_KEY);
    }
    @Test @DisplayName("既知RAWの欠損または旧鍵だけが008")
    void knownRawMissingOrRotatedKeyRejected() {
        rejected("BIRTHPROFILE_008", saved(BirthProfileCommandReplay.RAW_PROFILE_PUT, null),
                BirthProfileCommandReplay.RAW_PROFILE_PUT, KEY, HASH);
        rejected("BIRTHPROFILE_008", saved(BirthProfileCommandReplay.RAW_PROFILE_PUT, OTHER_KEY),
                BirthProfileCommandReplay.RAW_PROFILE_PUT, KEY, OTHER_KEY);
    }
    @Test @DisplayName("同種RAWの同鍵同本文は保存ACKを利用可能")
    void sameRawCommandAccepted() {
        assertThatCode(() -> BirthProfileCommandReplay.verify(saved(BirthProfileCommandReplay.RAW_PROFILE_PUT, KEY),
                BirthProfileCommandReplay.RAW_PROFILE_PUT, KEY, HASH)).doesNotThrowAnyException();
    }
    @Test @DisplayName("同鍵RAWの別本文は006")
    void sameKeyDifferentPayloadRejected() {
        rejected("BIRTHPROFILE_006", saved(BirthProfileCommandReplay.RAW_PROFILE_PUT, KEY),
                BirthProfileCommandReplay.RAW_PROFILE_PUT, KEY, OTHER_KEY);
    }
    @Test @DisplayName("既知CONFIRMのkeyIdNULLは鍵交代拒否にしない")
    void confirmationNullKeyAccepted() {
        assertThatCode(() -> BirthProfileCommandReplay.verify(saved(BirthProfileCommandReplay.CONFIRM_BIRTH_STYLE, null),
                BirthProfileCommandReplay.CONFIRM_BIRTH_STYLE, OTHER_KEY, HASH)).doesNotThrowAnyException();
    }
}
