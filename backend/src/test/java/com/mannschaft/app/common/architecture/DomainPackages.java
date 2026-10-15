package com.mannschaft.app.common.architecture;

/**
 * モジュラーモノリス境界の番人テスト群が共有する<b>ドメイン判定ユーティリティ</b>。
 *
 * <p>パッケージ名 {@code com.mannschaft.app.<domain>...} の先頭セグメント {@code <domain>}
 * を「そのクラスの所属ドメイン」とみなす。境界番人（D-1 / D-3 / D-4 等）は
 * すべてこの単一実装を参照することで、ドメイン判定ロジックの二重メンテを排除する。
 *
 * <p>従来 {@code CrossDomainEntityImportArchTest} の private static ヘルパーとして
 * 存在していたものを、複数の番人テストから再利用できるよう public static へ抽出した。
 * <b>判定ロジック・除外規則は抽出前と完全に同一であり、凍結ストアの照合キーや
 * 既存ルールの挙動には一切影響しない</b>。
 */
final class DomainPackages {

    /** アプリのルートパッケージ。ドメイン名抽出の基点。 */
    static final String ROOT_PACKAGE = "com.mannschaft.app";

    /**
     * 全ドメインから共有される基盤パッケージ。相手先ドメインがこれの場合は許容し、
     * 発生元がこれの場合も対象外とする（例: {@code common.entity.UuidV7Entity}）。
     */
    static final String SHARED_DOMAIN = "common";

    private DomainPackages() {
        // ユーティリティクラス
    }

    /**
     * パッケージ名から所属ドメイン（{@code com.mannschaft.app} 直下の先頭セグメント）を
     * 取り出す。アプリ配下でない場合は {@code null}。
     *
     * @param packageName 判定対象のパッケージ名（{@code null} 可）
     * @return ドメイン名、またはアプリ配下でなければ {@code null}
     */
    static String domainOf(String packageName) {
        if (packageName == null || !packageName.startsWith(ROOT_PACKAGE)) {
            return null;
        }
        String rest = packageName.substring(ROOT_PACKAGE.length());
        if (rest.startsWith(".")) {
            rest = rest.substring(1);
        }
        if (rest.isEmpty()) {
            return null;
        }
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /** {@code ..entity} または {@code ..entity.*} 配下かどうか。 */
    static boolean isEntityPackage(String packageName) {
        return packageName.contains(".entity.") || packageName.endsWith(".entity");
    }

    /** {@code ..entity.enums} または {@code ..entity.enums.*} 配下かどうか。 */
    static boolean isEnumPackage(String packageName) {
        return packageName.contains(".entity.enums.") || packageName.endsWith(".entity.enums");
    }

    /** {@code common} 共有ドメインかどうか（{@code null} は false）。 */
    static boolean isSharedDomain(String domain) {
        return SHARED_DOMAIN.equals(domain);
    }
}
