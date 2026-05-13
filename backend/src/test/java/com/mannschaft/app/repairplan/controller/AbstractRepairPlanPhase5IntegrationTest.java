package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.repairplan.module.RepairPlanModuleGuard;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * F08.8 Phase 5 申し送りパック Controller / Service 統合テスト共通基底クラス。
 *
 * <p>{@link AbstractMySqlIntegrationTest} を継承し、Phase 5 テストで共通して必要となる
 * モック設定を集約する。</p>
 *
 * <h3>モック化対象</h3>
 * <ul>
 *   <li>{@link RepairPlanModuleGuard} — モジュール有効化チェックを no-op にする</li>
 *   <li>{@link R2StorageService} — R2 への実際のアップロードをモックにする（テスト環境に R2 が不要）</li>
 *   <li>{@link PdfGeneratorService} — Thymeleaf + Flying Saucer の PDF 生成をモックにする</li>
 * </ul>
 */
public abstract class AbstractRepairPlanPhase5IntegrationTest extends AbstractMySqlIntegrationTest {

    /** ModuleGuard は別途単体テスト済みのため、統合テストでは no-op にする。 */
    @MockitoBean
    protected RepairPlanModuleGuard repairPlanModuleGuard;

    /** R2 アップロード / ダウンロードはテスト環境では mock にする。 */
    @MockitoBean
    protected R2StorageService r2StorageService;

    /** PDF 生成は Flying Saucer / Thymeleaf 依存が大きいため mock にする。 */
    @MockitoBean
    protected PdfGeneratorService pdfGeneratorService;

    /**
     * UserEntity の暗号化フィールドをテスト用に暗号化する。
     * native query で INSERT する際はこのメソッドを経由すること。
     */
    @Autowired
    protected EncryptionService encryptionService;

    /** テストの native INSERT で UserEntity 暗号化フィールドに格納する値を暗号化する。 */
    protected String encryptForTest(String plain) {
        return encryptionService.encrypt(plain);
    }

    /** 全モックを no-op にセットアップする。派生クラスの {@code @BeforeEach} で呼ぶこと。 */
    protected void mockDependenciesNoop() {
        lenient().doNothing().when(repairPlanModuleGuard).requireEnabled(any(), anyLong());

        // R2 アップロードは何もしない
        lenient().doNothing().when(r2StorageService).upload(anyString(), any(byte[].class), anyString());
        // R2 ダウンロード URL は固定の署名付き URL を返す
        lenient().when(r2StorageService.generateDownloadUrl(anyString(), any()))
                .thenReturn("https://mock-r2.example.com/signed-url?token=test");

        // PDF 生成はダミーバイト列を返す
        lenient().when(pdfGeneratorService.generateFromTemplate(anyString(), any()))
                .thenReturn("DUMMY_PDF_BYTES".getBytes());
        // SHA-256 はダミーハッシュを返す
        lenient().when(pdfGeneratorService.sha256Hex(any(byte[].class)))
                .thenReturn("a".repeat(64));
    }
}
