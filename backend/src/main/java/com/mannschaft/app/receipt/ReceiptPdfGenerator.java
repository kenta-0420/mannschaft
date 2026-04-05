package com.mannschaft.app.receipt;

import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptLineItemEntity;

import java.util.List;

/**
 * 領収書 PDF 生成インターフェース。
 * OpenPDF 2.x + IPAex明朝で実装する。
 */
public interface ReceiptPdfGenerator {

    /**
     * 領収書 PDF を生成し、バイト配列として返却する。
     *
     * @param receipt   領収書エンティティ
     * @param lineItems 明細行リスト（空の場合は単一税率）
     * @param logoBytes ロゴ画像のバイト配列（null の場合はロゴなし）
     * @param sealSvg   電子印鑑の SVG 文字列（null の場合は押印なし）
     * @param customFooter カスタムフッターテキスト（null の場合はフッターなし）
     * @return PDF のバイト配列
     */
    byte[] generate(ReceiptEntity receipt, List<ReceiptLineItemEntity> lineItems,
                    byte[] logoBytes, String sealSvg, String customFooter);

    /**
     * 無効化された領収書の PDF を生成する（赤スタンプオーバーレイ付き）。
     *
     * @param receipt   領収書エンティティ（voided_at 設定済み）
     * @param lineItems 明細行リスト
     * @param logoBytes ロゴ画像のバイト配列
     * @param sealSvg   電子印鑑の SVG 文字列
     * @param customFooter カスタムフッターテキスト
     * @return 無効スタンプ付き PDF のバイト配列
     */
    byte[] generateVoided(ReceiptEntity receipt, List<ReceiptLineItemEntity> lineItems,
                          byte[] logoBytes, String sealSvg, String customFooter);
}
