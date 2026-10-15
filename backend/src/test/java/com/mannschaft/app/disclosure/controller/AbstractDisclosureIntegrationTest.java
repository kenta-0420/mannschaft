package com.mannschaft.app.disclosure.controller;

import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * F09.14 重要事項説明書 Controller / Service 統合テスト共通基底（Phase 2-ζ-A）。
 *
 * <p>{@link AbstractMySqlIntegrationTest} を継承し、Disclosure 系の統合テストでのみ
 * 必要となる {@link R2StorageService} のモック化を集約する。</p>
 *
 * <h3>なぜ中間基底クラスが必要か</h3>
 * <ol>
 *   <li>{@code DisclosureExportService} は出力 PDF / Excel を {@link R2StorageService#upload}
 *       で実 R2（または LocalStack）に直接アップロードする。本テスト群はストレージ実体の
 *       検証ではなく Controller 〜 Service 〜 DB 間の連携を検証することが目的のため、
 *       R2 通信は不要であり、実 SDK が呼ばれるとローカル環境で接続エラーとなる。</li>
 *   <li>{@link AbstractMySqlIntegrationTest} に直接 {@code @MockitoBean R2StorageService}
 *       を追加すると、既存の他 15 件以上の統合テスト（F00 / F03 / F09.13 等）の
 *       ApplicationContext 構成が変わってしまい、TestContext Cache 分裂の影響範囲が
 *       読みきれなくなる。F09.13 ファイルへの変更も避けるべきとされている。</li>
 *   <li>そこで Disclosure 限定の中間基底クラスを 1 枚追加し、ここで {@code R2StorageService}
 *       をモック化する。Disclosure 系 4 ファイル間ではこの基底クラス経由で構成が完全に
 *       揃い、TestContext Cache はこのフェーズ内で 1 つに収束する。</li>
 * </ol>
 *
 * <p>Controller 統合テストでは {@code @EnabledIf} の再宣言を派生クラスでも忘れないこと
 * （{@link AbstractMySqlIntegrationTest} クラスコメント参照）。</p>
 */
public abstract class AbstractDisclosureIntegrationTest extends AbstractMySqlIntegrationTest {

    /**
     * R2 ストレージは実 SDK 呼び出しを避けるためモック化する。
     *
     * <p>{@code upload(...)} はデフォルト no-op、{@code download(...)} と
     * {@code generateDownloadUrl(...)} は各テストで Mockito の {@code when(...)} で
     * 個別にスタブする。</p>
     */
    @MockitoBean
    protected R2StorageService r2StorageService;

    /**
     * UserEntity の暗号化フィールド ({@code last_name}/{@code first_name} 等) を
     * 平文 INSERT すると JPA 復号で {@code IllegalArgumentException: Illegal base64} を投げる。
     * テスト用ユーザーを native query で投入する際は {@link #encryptForTest(String)} で
     * 値を暗号化してから INSERT すること。
     */
    @Autowired
    protected EncryptionService encryptionService;

    /** テストの native INSERT で UserEntity 暗号化フィールドに格納する値を暗号化する。 */
    protected String encryptForTest(String plain) {
        return encryptionService.encrypt(plain);
    }
}
