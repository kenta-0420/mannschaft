package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.jobmatching.config.QrSigningProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link QrSigningKeyProvider} のユニットテスト。F13.1 Phase 13.1.2。
 *
 * <p>起動時バリデーション（鍵長不足・kid 重複・active 鍵未設定）と、
 * 検証用 {@link QrSigningKeyProvider#find(String)} の解決挙動を網羅する。</p>
 */
@DisplayName("QrSigningKeyProvider 単体テスト")
class QrSigningKeyProviderTest {

    /** 32 バイト以上の有効ダミー secret。 */
    private static final String VALID_SECRET_V1 = "unit-test-qr-signing-key-v1-32bytes-xxxxxxxxx";

    /** 32 バイト以上の有効ダミー secret（ローテーション用）。 */
    private static final String VALID_SECRET_V2 = "unit-test-qr-signing-key-v2-32bytes-yyyyyyyyy";

    /**
     * 有効な {@link QrSigningProperties.SigningKey} を組み立てるヘルパ。
     */
    private static QrSigningProperties.SigningKey key(String kid, String secret, boolean active) {
        QrSigningProperties.SigningKey k = new QrSigningProperties.SigningKey();
        k.setKid(kid);
        k.setSecret(secret);
        k.setActive(active);
        return k;
    }

    /**
     * {@link QrSigningProperties} にキーリストを設定して返すヘルパ。
     */
    private static QrSigningProperties propertiesWith(List<QrSigningProperties.SigningKey> keys) {
        QrSigningProperties p = new QrSigningProperties();
        p.setSigningKeys(keys);
        return p;
    }

    /**
     * {@link QrSigningKeyProvider#initialize()} を手動で起動するヘルパ（{@code @PostConstruct} は
     * Spring Context 外では自動起動しないため）。
     */
    private static QrSigningKeyProvider initProvider(QrSigningProperties properties) {
        QrSigningKeyProvider provider = new QrSigningKeyProvider(properties);
        provider.initialize();
        return provider;
    }

    @Nested
    @DisplayName("起動時バリデーション")
    class InitializeValidation {

        @Test
        @DisplayName("正常系: active=true の鍵が current として解決される")
        void 正常_active鍵がcurrentになる() {
            QrSigningProperties props = propertiesWith(List.of(
                    key("v1", VALID_SECRET_V1, true)
            ));

            QrSigningKeyProvider provider = initProvider(props);

            QrSigningKeyProvider.CurrentKey current = provider.current();
            assertThat(current.kid()).isEqualTo("v1");
            assertThat(current.key()).isNotNull();
        }

        @Test
        @DisplayName("正常系: ローテーション途中（旧 v1=false + 新 v2=true）でも v2 が current")
        void 正常_ローテーション途中_v2がcurrent() {
            QrSigningProperties props = propertiesWith(List.of(
                    key("v1", VALID_SECRET_V1, false),
                    key("v2", VALID_SECRET_V2, true)
            ));

            QrSigningKeyProvider provider = initProvider(props);

            assertThat(provider.current().kid()).isEqualTo("v2");
        }

        @Test
        @DisplayName("異常系: 鍵長が 32 バイト未満で起動失敗")
        void 異常_鍵長不足で起動失敗() {
            QrSigningProperties props = propertiesWith(List.of(
                    key("v1", "too-short-key", true)
            ));

            assertThatThrownBy(() -> initProvider(props))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("短すぎます")
                    .hasMessageContaining("v1");
        }

        @Test
        @DisplayName("異常系: active=true の鍵が 1 件もないと起動失敗")
        void 異常_active鍵なしで起動失敗() {
            QrSigningProperties props = propertiesWith(List.of(
                    key("v1", VALID_SECRET_V1, false)
            ));

            assertThatThrownBy(() -> initProvider(props))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active=true");
        }

        @Test
        @DisplayName("異常系: kid 重複で起動失敗")
        void 異常_kid重複で起動失敗() {
            QrSigningProperties props = propertiesWith(List.of(
                    key("v1", VALID_SECRET_V1, true),
                    key("v1", VALID_SECRET_V2, false)
            ));

            assertThatThrownBy(() -> initProvider(props))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("重複")
                    .hasMessageContaining("v1");
        }
    }

    @Nested
    @DisplayName("find(kid)")
    class FindByKid {

        @Test
        @DisplayName("正常系: active な鍵を kid で解決できる")
        void find_active鍵解決() {
            QrSigningProperties props = propertiesWith(List.of(
                    key("v1", VALID_SECRET_V1, true)
            ));
            QrSigningKeyProvider provider = initProvider(props);

            Optional<SecretKey> key = provider.find("v1");

            assertThat(key).isPresent();
        }

        @Test
        @DisplayName("正常系: 旧鍵（active=false）も検証用に解決可能（ローテーション中の発行済みトークン対応）")
        void find_旧鍵も解決可能() {
            QrSigningProperties props = propertiesWith(List.of(
                    key("v1", VALID_SECRET_V1, false),
                    key("v2", VALID_SECRET_V2, true)
            ));
            QrSigningKeyProvider provider = initProvider(props);

            assertThat(provider.find("v1")).isPresent();
            assertThat(provider.find("v2")).isPresent();
        }

        @Test
        @DisplayName("異常系: 未知の kid は empty を返す（BusinessException は Service 層で発生）")
        void find_未知kidはempty() {
            QrSigningProperties props = propertiesWith(List.of(
                    key("v1", VALID_SECRET_V1, true)
            ));
            QrSigningKeyProvider provider = initProvider(props);

            assertThat(provider.find("unknown")).isEmpty();
        }
    }
}
