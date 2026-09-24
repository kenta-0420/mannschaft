package com.mannschaft.app.common.pdf;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.config.PdfFontConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import com.lowagie.text.pdf.BaseFont;
import org.xhtmlrenderer.pdf.ITextFontResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * 共通PDF生成サービス。
 * Thymeleaf テンプレートからHTMLを生成し、Flying Saucer で PDF に変換する。
 *
 * <p>F12.1 §5.14 / F09.15 §9.4 — 入居時誓約 PDF 用に
 * {@link #signWithInternalToken(byte[], String)} および
 * {@link #generateSignedCovenantPdf(SuccessionCovenantContext)} を提供する。
 */
@Slf4j
@Service
public class PdfGeneratorService {

    /** 内部署名トークンの HMAC アルゴリズム */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** SHA-256 ダイジェストアルゴリズム */
    private static final String SHA_256 = "SHA-256";

    /** 入居時誓約テンプレ名 */
    private static final String COVENANT_TEMPLATE = "pdf/succession-covenant";

    private final TemplateEngine templateEngine;
    private final PdfFontConfig pdfFontConfig;

    /**
     * 内部署名鍵（v1 / 簡易方式）。null の場合は {@link #signWithInternalToken} は PDF_007 で例外を投げる。
     * Spring から ConfigurationProperties 経由で注入される。
     */
    private final InternalPdfSigningProperties internalSigningProperties;

    @Autowired
    public PdfGeneratorService(
            TemplateEngine templateEngine,
            PdfFontConfig pdfFontConfig,
            InternalPdfSigningProperties internalSigningProperties) {
        this.templateEngine = templateEngine;
        this.pdfFontConfig = pdfFontConfig;
        this.internalSigningProperties = internalSigningProperties;
    }

    /**
     * 既存テスト互換コンストラクタ（内部署名機能を使わない場合）。
     */
    public PdfGeneratorService(TemplateEngine templateEngine, PdfFontConfig pdfFontConfig) {
        this(templateEngine, pdfFontConfig, new InternalPdfSigningProperties(null));
    }

    /**
     * Thymeleaf テンプレートからPDFを生成する。
     *
     * @param templateName テンプレート名（例: "pdf/receipt"）
     * @param variables    テンプレートに渡す変数マップ
     * @return PDF の byte[]
     */
    public byte[] generateFromTemplate(String templateName, Map<String, Object> variables) {
        String html = renderHtml(templateName, variables);
        return convertHtmlToPdf(html);
    }

    /**
     * PDF バイト配列に内部署名トークンを付与し、改ざん検知用ハッシュとセットで返す。
     *
     * <p>v1 簡易方式（F12.1 §5.14）:
     * <pre>
     *   hash         = SHA-256(pdfBytes)
     *   token_input  = hash_hex || "|" || subjectId || "|" || issuedAtEpochMs
     *   token        = Base64URL( HMAC-SHA256(secret, token_input) || "." || issuedAtEpochMs )
     * </pre>
     *
     * <p>本実装では PDF バイト本体は加工せず、トークンを並列に返す（PDF への埋め込みは
     * テンプレ側 {@code timestamp_token} 表示領域での文字列表示に留め、構造的改ざんを避ける）。
     *
     * @param pdfBytes  元の PDF バイト
     * @param subjectId 署名対象の識別子（succession_covenants.id 等）
     * @return SignedPdfResult（PDF 本体・SHA-256・内部署名トークン・署名時刻・subjectId）
     * @throws BusinessException PDF_007: 内部署名鍵未設定
     */
    public SignedPdfResult signWithInternalToken(byte[] pdfBytes, String subjectId) {
        Objects.requireNonNull(pdfBytes, "pdfBytes は必須");
        Objects.requireNonNull(subjectId, "subjectId は必須");
        requireSigningKey();

        String hashHex = sha256Hex(pdfBytes);
        Instant signedAt = Instant.now();
        String token = computeInternalToken(hashHex, subjectId, signedAt);

        return new SignedPdfResult(pdfBytes, hashHex, token, signedAt, subjectId);
    }

    /**
     * 入居時誓約 PDF を生成し、内部署名トークンを付与する。
     *
     * @param ctx 描画コンテキスト
     * @return 署名済み PDF
     */
    public SignedPdfResult generateSignedCovenantPdf(SuccessionCovenantContext ctx) {
        Objects.requireNonNull(ctx, "SuccessionCovenantContext は必須");
        requireSigningKey();

        Map<String, Object> vars = new HashMap<>();
        vars.put("subjectId", ctx.subjectId());
        vars.put("covenantType", ctx.covenantType());
        vars.put("covenantTypeLabel", ctx.covenantTypeLabel());
        vars.put("covenantVersion", ctx.covenantVersion());
        vars.put("residentName", ctx.residentName());
        vars.put("dwellingUnitLabel", ctx.dwellingUnitLabel());
        vars.put("residentType", ctx.residentType());
        vars.put("contractDate", ctx.contractDate());
        vars.put("signedAt", ctx.signedAt());
        vars.put("organizationName", ctx.organizationName());
        vars.put("representativeName", ctx.representativeName());
        // 一段目の生成では署名フィールドはプレースホルダ。本体生成後に再描画して埋め込む。
        vars.put("contentHashSha256", null);
        vars.put("timestampToken", null);
        vars.put("issuedAt", null);

        byte[] firstPassPdf = generateFromTemplate(COVENANT_TEMPLATE, vars);
        // 一段目の PDF バイトに対して内部署名トークンを生成
        String hashHex = sha256Hex(firstPassPdf);
        Instant signedAtInstant = Instant.now();
        String token = computeInternalToken(hashHex, ctx.subjectId(), signedAtInstant);

        // 二段目: フッターに contentHashSha256 / timestampToken / issuedAt を埋め込んだ最終 PDF を生成
        vars.put("contentHashSha256", hashHex);
        vars.put("timestampToken", token);
        vars.put("issuedAt", LocalDateTime.ofInstant(signedAtInstant, ZoneOffset.UTC));
        byte[] finalPdf = generateFromTemplate(COVENANT_TEMPLATE, vars);

        // 最終 PDF の SHA-256（フッターを含めたバイト列）を保存対象とする
        String finalHashHex = sha256Hex(finalPdf);
        String finalToken = computeInternalToken(finalHashHex, ctx.subjectId(), signedAtInstant);

        return new SignedPdfResult(finalPdf, finalHashHex, finalToken, signedAtInstant, ctx.subjectId());
    }

    /**
     * 検証用の内部署名トークン再計算（外部 Service から利用される）。
     * 改ざん検知時の二段保護のため、計算ロジックは generation 側と同一を維持する。
     */
    public String recomputeInternalToken(String hashHex, String subjectId, Instant signedAt) {
        requireSigningKey();
        return computeInternalToken(hashHex, subjectId, signedAt);
    }

    /**
     * 任意のバイト列の SHA-256 を hex 小文字 64 桁で返す（検証 Service と共通利用）。
     */
    public String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance(SHA_256);
            byte[] digest = md.digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // JRE 標準アルゴリズムが見つからない事態は想定外
            throw new BusinessException(PdfErrorCode.PDF_002, e);
        }
    }

    // ────────────────────────────────────────────────────
    // 内部ヘルパー
    // ────────────────────────────────────────────────────

    private void requireSigningKey() {
        if (internalSigningProperties == null
                || internalSigningProperties.internalSigningKey() == null
                || internalSigningProperties.internalSigningKey().isBlank()) {
            log.error("PDF 内部署名鍵が設定されていません。"
                    + "環境変数 MANNSCHAFT_INTERNAL_SIGNING_KEY または "
                    + "application.yml の mannschaft.security.internal-signing-key を設定してください。");
            throw new BusinessException(PdfErrorCode.PDF_007);
        }
    }

    private String computeInternalToken(String hashHex, String subjectId, Instant signedAt) {
        try {
            long epochMs = signedAt.toEpochMilli();
            String tokenInput = hashHex + "|" + subjectId + "|" + epochMs;
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            byte[] keyBytes = internalSigningProperties.internalSigningKey().getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGORITHM));
            byte[] hmac = mac.doFinal(tokenInput.getBytes(StandardCharsets.UTF_8));
            String hmacB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
            // token は HMAC 部 + "." + epochMs（検証側で epochMs を分離して再計算するため）
            return hmacB64 + "." + epochMs;
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            log.error("内部署名トークン計算失敗", e);
            throw new BusinessException(PdfErrorCode.PDF_002, e);
        }
    }

    private String renderHtml(String templateName, Map<String, Object> variables) {
        try {
            Context context = new Context();
            context.setVariables(variables);
            return templateEngine.process(templateName, context);
        } catch (Exception e) {
            log.error("Thymeleaf テンプレート処理失敗: template={}", templateName, e);
            throw new BusinessException(PdfErrorCode.PDF_001);
        }
    }

    private byte[] convertHtmlToPdf(String html) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            registerFonts(renderer);

            String baseUrl = resolveBaseUrl();
            renderer.setDocumentFromString(html, baseUrl);
            renderer.layout();
            renderer.createPDF(outputStream);

            return outputStream.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("PDF 変換失敗", e);
            throw new BusinessException(PdfErrorCode.PDF_002);
        }
    }

    /**
     * PDF テンプレート内の相対 URL（{@code <link rel="stylesheet" href="/css/...">} や
     * CSS の {@code @font-face src: url('/fonts/...')}）を解決する基準 URL を求める。
     *
     * <p>{@code new ClassPathResource("/").getURL()} はクラスローダーの実効クラスパスの
     * うち<strong>最初に見つかったルート</strong>を返す。Gradle のテスト実行や IDE 実行では
     * クラスパスが {@code build/classes/java/main}（クラスファイル）と
     * {@code build/resources/main}（リソース）に分離されており、前者が先に来ると
     * css/fonts を含まないディレクトリを base URL にしてしまう。この場合スタイルシートの
     * 読み込みが例外にならず静かに失敗し、{@code body} に日本語フォント指定が一切
     * 当たらないまま CJK グリフだけが欠落した PDF が生成される（本番の fat jar では
     * クラスパスルートが単一に統合されるため顕在化しない差異）。
     *
     * <p>実際に {@code css/pdf-common.css} を含むルートを {@link ClassPathResource} 経由
     * （クラスローダー全体を検索する）で特定し、そのルートを base URL とすることで、
     * クラスパスのレイアウトに依存せず css/fonts を確実に解決できるようにする。
     */
    private String resolveBaseUrl() throws java.io.IOException {
        String cssRelativePath = "css/pdf-common.css";
        String cssUrl = new ClassPathResource(cssRelativePath).getURL().toExternalForm();
        if (!cssUrl.endsWith(cssRelativePath)) {
            // 想定外のURL形状（jar内パス等）の場合は従来どおりのフォールバック。
            return new ClassPathResource("/").getURL().toExternalForm();
        }
        return cssUrl.substring(0, cssUrl.length() - cssRelativePath.length());
    }

    private void registerFonts(ITextRenderer renderer) {
        ITextFontResolver fontResolver = renderer.getFontResolver();
        for (PdfFontConfig.FontEntry font : pdfFontConfig.getRegisteredFonts()) {
            try {
                ClassPathResource resource = new ClassPathResource(font.classpathLocation());
                try (InputStream is = resource.getInputStream()) {
                    String fontUrl = resource.getURL().toExternalForm();
                    // CJK（日本語）グリフを埋め込むには IDENTITY_H エンコーディングが必須。
                    // 既定の addFont(path, embedded) は BaseFont.CP1252 を使うため、
                    // 埋め込み自体は成功しても日本語グリフが欠落し無音で失敗する。
                    //
                    // また family 名はここで familyName() へ明示的に上書きする。TTF内部の
                    // name テーブル（例: "Noto Sans JP"、スペースあり）を自動採用させると、
                    // pdf-common.css の font-family: 'NotoSansJP'（スペースなし）と一致せず
                    // Flying Saucer がCJK非対応のフォールバックフォントへ静かに切り替わり、
                    // 日本語グリフが欠落する。
                    fontResolver.addFont(fontUrl, font.familyName(), BaseFont.IDENTITY_H, true, null);
                }
            } catch (Exception e) {
                log.error("フォント登録失敗: {}", font.familyName(), e);
                throw new BusinessException(PdfErrorCode.PDF_003);
            }
        }
    }
}
