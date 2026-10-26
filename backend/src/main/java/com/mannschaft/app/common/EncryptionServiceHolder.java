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

    /**
     * 現在の登録値をそのまま返す（未設定なら {@code null}）。例外は投げない。
     *
     * <p>本ホルダーは JVM グローバルな静的状態であり、テストが一時的に差し替える場合は
     * <b>元の値を退避して必ず戻さねばならない</b>。{@code null} で潰すと、同一テスト JVM 内で
     * 後から走る結合テスト（Spring コンテキストは生成済みのため {@code EncryptionConfig} が
     * 再度 {@link #set} を呼ばない）が
     * {@code IllegalStateException: EncryptionService has not been initialized} で落ちる。
     * 退避のためだけに {@link #getEncryptionService()} を try-catch で包むのは
     * 例外の握り潰しになるため、退避専用の本メソッドを用意する。</p>
     *
     * @return 登録済みの {@link EncryptionService}。未設定なら {@code null}
     */
    public static EncryptionService peek() {
        return instance;
    }

    public static EncryptionService getEncryptionService() {
        if (instance == null) {
            throw new IllegalStateException("EncryptionService has not been initialized");
        }
        return instance;
    }
}
