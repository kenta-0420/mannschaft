package com.mannschaft.app.common.storage.acl;

/** ACL 参照キーの入力値を共通して検証する。 */
final class StorageAclReferenceValidator {

    private static final int MAX_LENGTH = 64;

    private StorageAclReferenceValidator() {
    }

    static void validate(String type, String key, String label) {
        if (type == null || type.isBlank() || type.length() > MAX_LENGTH || key == null || key.isBlank()
                || key.length() > MAX_LENGTH || !isPrintableAscii(type) || !isPrintableAscii(key)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private static boolean isPrintableAscii(String value) {
        return value.chars().allMatch(character -> character >= 0x20 && character <= 0x7e);
    }
}
