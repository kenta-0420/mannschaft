package com.mannschaft.app.succession.controller;

import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * F09.15 区分所有者承継支援 Controller 統合テスト共通基底（S1 第四陣）。
 *
 * <p>{@link AbstractMySqlIntegrationTest} を継承し、Succession 系の統合テストでのみ
 * 必要となる {@link R2StorageService} / {@link PdfGeneratorService} のモック化を集約する。</p>
 *
 * <h3>なぜ中間基底クラスが必要か</h3>
 * <ol>
 *   <li>{@code SuccessionCovenantService} は誓約 PDF を {@link R2StorageService#upload}
 *       で実 R2 に直接アップロードする。本テスト群はストレージ実体の検証ではなく
 *       Controller 〜 Service 〜 DB 間の連携を検証することが目的のため、R2 通信は不要であり、
 *       実 SDK が呼ばれるとローカル環境で接続エラーとなる。</li>
 *   <li>{@code PdfGeneratorService} は内部署名鍵が設定されていないとテスト環境で
 *       例外を投げるため、モック化して固定の {@code SignedPdfResult} を返す。</li>
 *   <li>{@link AbstractMySqlIntegrationTest} に直接 {@code @MockitoBean} を追加すると
 *       既存の他統合テストの ApplicationContext 構成が変わり TestContext Cache 分裂が生じる。</li>
 *   <li>Succession 限定の中間基底クラスを 1 枚追加することで、Succession 系テスト間で
 *       TestContext Cache を 1 つに収束させる。</li>
 * </ol>
 *
 * <p>Controller 統合テストでは {@code @EnabledIf} の再宣言を派生クラスでも忘れないこと
 * （{@link AbstractMySqlIntegrationTest} クラスコメント参照）。</p>
 */
public abstract class AbstractSuccessionIntegrationTest extends AbstractMySqlIntegrationTest {

    /**
     * R2 ストレージは実 SDK 呼び出しを避けるためモック化する。
     *
     * <p>{@code upload(...)} はデフォルト no-op。各テストで必要に応じて
     * Mockito の {@code when(...)} で個別にスタブする。</p>
     */
    @MockitoBean
    protected R2StorageService r2StorageService;

    /**
     * PDF 生成サービスをモック化する。
     *
     * <p>テスト環境では内部署名鍵が未設定のため実際の生成は行えない。
     * 各テストで {@code given(...).willReturn(...)} でダミーの {@code SignedPdfResult} をスタブする。</p>
     */
    @MockitoBean
    protected PdfGeneratorService pdfGeneratorService;

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
