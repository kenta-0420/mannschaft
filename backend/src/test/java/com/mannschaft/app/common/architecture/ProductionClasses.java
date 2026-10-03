package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.function.Supplier;

/**
 * 本番クラス全体（{@code com.mannschaft.app}、テストクラスを除く）の ArchUnit 取り込み結果を
 * JVM 内で1つだけ保持する共有ホルダ（CMP-261002-1606）。
 *
 * <p><b>なぜ要るか</b>: 全量 CI の shard 5 が {@code Java heap space} で毎回落ちていた。原因は、
 * テストクラスがそれぞれ {@code new ClassFileImporter().importPackages("com.mannschaft.app")} で
 * 本番全体を取り込み、static フィールドやメソッド内で何コピーも保持していたこと。
 * {@code @AnalyzeClasses} は ArchUnit の ClassCache で同一キーを共有するが、手動の
 * {@link ClassFileImporter} 呼び出しは共有されない。本番全体が要るテストは必ず {@link #get()}
 * を使い、取り込みを JVM 内で1回に抑える。この約束は番人
 * {@link ProductionClassImportGuardTest} が機械的に強制する。
 *
 * <p><b>失敗時の契約（AC-3）</b>: 取り込み関数が例外を投げた場合、
 * <ul>
 *   <li>初回の呼び出し元には、その例外が<b>そのまま（同一インスタンスで）</b>伝わる。</li>
 *   <li>2回目以降は<b>再試行しない</b>。初回の例外を cause に持つ {@link IllegalStateException}
 *       を投げ続ける（取り込みは同じクラスパスに対して決定的であり、再試行はメモリと時間を
 *       浪費するだけで、OOM 直後の再試行は部分的な状態を生みうるため）。</li>
 *   <li>取り込み関数が {@code null} または空の {@link JavaClasses} を返した場合も失敗として扱い、
 *       {@link IllegalStateException} を投げる（空・部分的な結果を正常値として返さない）。
 *       以後の呼び出しも同じ失敗を再送する。</li>
 * </ul>
 */
public final class ProductionClasses {

    /** 本番コードのルートパッケージ。 */
    static final String PRODUCTION_ROOT = "com.mannschaft.app";

    private static final Memoizer SHARED = new Memoizer(ProductionClasses::importProduction);

    private ProductionClasses() {
    }

    /**
     * 本番クラス全体の取り込み結果を返す。JVM 内で初回だけ取り込み、以後は同一インスタンスを返す。
     *
     * @return 本番クラス全体（テストクラスを含まない）
     */
    public static JavaClasses get() {
        return SHARED.get();
    }

    private static JavaClasses importProduction() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PRODUCTION_ROOT);
    }

    /**
     * 取り込み関数を1回だけ呼び、その結果（または失敗）を保持するメモ化器。
     * 試験から取り込み関数を差し込めるよう package-private とする。
     */
    static final class Memoizer {

        private final Supplier<JavaClasses> importer;

        /** 取り込み成功後の結果。成功するまで null。volatile で二重検査ロックの公開を安全にする。 */
        private volatile JavaClasses value;

        /** 初回の失敗。以後の呼び出しはこれを cause に持つ ISE を投げる（synchronized 内でのみ読み書き）。 */
        private Throwable failure;

        Memoizer(Supplier<JavaClasses> importer) {
            this.importer = importer;
        }

        /**
         * 取り込み結果を返す。契約はクラス Javadoc の「失敗時の契約」を参照。
         *
         * @return 取り込み結果（非空）
         */
        JavaClasses get() {
            JavaClasses current = value;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                if (value != null) {
                    return value;
                }
                if (failure != null) {
                    throw new IllegalStateException(
                            "本番クラスの取り込みは既に失敗している（再試行しない）", failure);
                }
                JavaClasses imported;
                try {
                    imported = importer.get();
                } catch (RuntimeException | Error e) {
                    failure = e;
                    throw e;
                }
                if (imported == null || imported.isEmpty()) {
                    IllegalStateException empty = new IllegalStateException(
                            "本番クラスの取り込み結果が" + (imported == null ? " null" : "空") + "だった");
                    failure = empty;
                    throw empty;
                }
                value = imported;
                return imported;
            }
        }
    }
}
