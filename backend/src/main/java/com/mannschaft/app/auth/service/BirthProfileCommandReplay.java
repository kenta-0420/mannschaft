package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.BirthProfileErrorCode;
import com.mannschaft.app.auth.entity.BirthProfileCommandEntity;
import com.mannschaft.app.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 成功履歴の種別、鍵識別、本文同一性をこの順に検証する。原情報は保持しない。 */
public final class BirthProfileCommandReplay {
    public static final String RAW_PROFILE_PUT = "RAW_PROFILE_PUT";
    public static final String CONFIRM_BIRTH_STYLE = "CONFIRM_BIRTH_STYLE";
    private BirthProfileCommandReplay() {}

    public static void verify(BirthProfileCommandEntity saved, String commandType,
            String currentKeyId, String requestHash) {
        if (saved == null || (!RAW_PROFILE_PUT.equals(commandType)
                && !CONFIRM_BIRTH_STYLE.equals(commandType))) throw new IllegalArgumentException("命令種別が不正です");
        // 種別NULLは旧履歴の未知状態。keyIdや応答の形だけでRAWと決めない。
        if (!commandType.equals(saved.getCommandType())) {
            throw new BusinessException(BirthProfileErrorCode.COMMAND_CONFLICT);
        }
        if (RAW_PROFILE_PUT.equals(commandType) && !equal(currentKeyId, saved.getRequestKeyId())) {
            throw new BusinessException(BirthProfileErrorCode.COMMAND_KEY_ROTATED);
        }
        if (!equal(requestHash, saved.getRequestHash())) {
            throw new BusinessException(BirthProfileErrorCode.COMMAND_CONFLICT);
        }
    }

    private static boolean equal(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}
