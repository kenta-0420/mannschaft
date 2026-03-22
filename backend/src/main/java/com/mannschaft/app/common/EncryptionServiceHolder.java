package com.mannschaft.app.common;

/**
 * JPA AttributeConverterからEncryptionServiceにアクセスするための静的ホルダー。
 * <p>
 * JPAのConverterはSpring管理外で生成されるため、DIが使えない。
 * EncryptionConfigの初期化時にセットされる。
 */
public final class EncryptionServiceHolder {

    private static volatile EncryptionService instance;

    private EncryptionServiceHolder() {
    }

    public static void set(EncryptionService encryptionService) {
        instance = encryptionService;
    }

    public static EncryptionService getEncryptionService() {
        if (instance == null) {
            throw new IllegalStateException("EncryptionService has not been initialized");
        }
        return instance;
    }
}
