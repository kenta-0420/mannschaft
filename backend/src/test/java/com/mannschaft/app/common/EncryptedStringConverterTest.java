package com.mannschaft.app.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EncryptedStringConverter} の単体テスト。
 *
 * <p>本テストは「予定詳細 GET が 500 を返す本番バグ」(入口④ E2E が検出) の回帰防止番人を含む。
 * 真因: 暗号化導入（{@code V9.053}）以前に平文のまま保存されたシードユーザー
 * （システムユーザー id=1 = {@code last_name='システム'} 等）の行を Hibernate がハイドレートする際、
 * 当 Converter が素朴に {@code decrypt()} を呼んで Base64 デコードに失敗し
 * （{@code IllegalArgumentException: Illegal base64 character 3f}）、
 * 当該ユーザーを参照する読み取り経路（作成者表示名の解決）が全て 500 になっていた。</p>
 */
@DisplayName("EncryptedStringConverter 単体テスト")
class EncryptedStringConverterTest {

    private EncryptedStringConverter converter;
    private EncryptionService encryptionService;

    /**
     * 差し替える前の {@link EncryptionServiceHolder} の登録値。
     *
     * <p>{@link EncryptionServiceHolder} は JVM グローバルな静的状態である。
     * 旧実装は {@code tearDown()} で無条件に {@code set(null)} していたため、
     * 同一テスト JVM 内で本テストより<b>後に</b>走る結合テストが
     * {@code IllegalStateException: EncryptionService has not been initialized} で落ちていた
     * （Spring コンテキストは既に生成済みで {@code EncryptionConfig} が再度 set しないため）。
     * 発火するか否かは shard 内のクラス実行順にしか依存しないので、
     * <b>テストクラスを1つ足すだけで無関係な結合テストが決定的に壊れる</b>という
     * 極めて追いにくい壊れ方をする（実際に Gate 基盤工事④-B の PR で shard 2 が落ちた）。
     * 退避して必ず元へ戻す。</p>
     */
    private EncryptionService previousHolderValue;

    @BeforeEach
    void setUp() {
        previousHolderValue = EncryptionServiceHolder.peek();
        byte[] encKey = new byte[32];
        byte[] hmacKey = new byte[32];
        new SecureRandom().nextBytes(encKey);
        new SecureRandom().nextBytes(hmacKey);
        encryptionService = new EncryptionService(encKey, hmacKey);
        // Converter は Spring 管理外。Holder 経由で EncryptionService を解決する。
        EncryptionServiceHolder.set(encryptionService);
        converter = new EncryptedStringConverter();
    }

    @AfterEach
    void tearDown() {
        // null で潰さず、差し替える前の値へ戻す（フィールドの Javadoc 参照）。
        EncryptionServiceHolder.set(previousHolderValue);
    }

    @Test
    @DisplayName("正常系: 暗号化→DB格納→復号で元の平文に戻る")
    void roundTrip_元の平文に戻る() {
        // Given
        String plain = "山田太郎";

        // When
        String dbColumn = converter.convertToDatabaseColumn(plain);
        String restored = converter.convertToEntityAttribute(dbColumn);

        // Then
        assertThat(dbColumn).isNotEqualTo(plain); // 暗号化されている
        assertThat(restored).isEqualTo(plain);
    }

    @Test
    @DisplayName("回帰防止: 暗号化導入前に平文保存された非Base64値を読んでも例外にならずそのまま返る")
    void convertToEntityAttribute_レガシー平文_例外にならない() {
        // Given: V1.012 システムユーザー(id=1) が last_name='システム' を平文挿入している。
        //        従来はここで Illegal base64 character 3f が発生し、予定詳細 GET が 500 になっていた。
        String legacyPlain = "システム";

        // When / Then: 例外を投げず平文をそのまま返す
        assertThat(converter.convertToEntityAttribute(legacyPlain)).isEqualTo("システム");
    }

    @Test
    @DisplayName("回帰防止: 退会センチネル(id=0)の空文字レガシー平文も例外にならない")
    void convertToEntityAttribute_空文字レガシー_例外にならない() {
        // Given: V12.004 退会センチネルは last_name='' / first_name='' を平文挿入している
        // When / Then
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
    }

    @Test
    @DisplayName("境界値: null入力でnullを返す（双方向）")
    void null入力_null() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    @DisplayName("異常系: 暗号文の形だがGCM認証に失敗する真の異常は症状を隠さず例外送出")
    void convertToEntityAttribute_改竄暗号文_例外送出() {
        // Given
        String dbColumn = converter.convertToDatabaseColumn("秘匿情報");
        byte[] decoded = java.util.Base64.getDecoder().decode(dbColumn);
        decoded[decoded.length - 1] ^= 0xFF;
        String tampered = java.util.Base64.getEncoder().encodeToString(decoded);

        // When / Then: レガシー平文扱いで握り潰さず例外
        assertThatThrownBy(() -> converter.convertToEntityAttribute(tampered))
                .isInstanceOf(EncryptionService.EncryptionException.class);
    }
}
