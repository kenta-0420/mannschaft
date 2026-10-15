package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code @Entity} クラス集合から「テーブル名 → 所属ドメイン」のマップを構築する
 * 共有ユーティリティ。SQL 走査系の境界番人（D-4: クロスドメイン FK 検出等）が、
 * DDL に現れるテーブル名を {@link DomainPackages#domainOf(String) ドメイン} へ
 * 解決するために用いる。
 *
 * <p>解決規則:
 * <ul>
 *   <li>各 {@code @Entity} クラスについて {@code @Table(name=...)} を読み、その値を
 *       テーブル名キーとする。</li>
 *   <li>{@code @Table} が無い／name 未指定の Entity は、クラス名から末尾
 *       {@code Entity} を除いた上でスネークケース化した名前を既定テーブル名とする
 *       （JPA/Hibernate の素朴な命名規則に合わせたフォールバック）。</li>
 *   <li>ドメインは Entity のパッケージから {@link DomainPackages#domainOf(String)} で抽出する。</li>
 * </ul>
 *
 * <p>テーブル名は MySQL の慣習に合わせて小文字へ正規化し、バッククォートで囲まれた
 * 名前にも備えて除去する。
 */
final class TableDomainResolver {

    private TableDomainResolver() {
        // ユーティリティクラス
    }

    /**
     * {@code @Entity} クラス集合から「テーブル名 → ドメイン」マップを構築する。
     *
     * @param classes ArchUnit が読み込んだ全クラス集合
     * @return キー＝正規化済みテーブル名（小文字）、値＝ドメイン名
     */
    static Map<String, String> resolve(JavaClasses classes) {
        Map<String, String> tableToDomain = new HashMap<>();
        for (JavaClass clazz : classes) {
            if (!clazz.isAnnotatedWith(Entity.class)) {
                continue;
            }
            String domain = DomainPackages.domainOf(clazz.getPackageName());
            if (domain == null) {
                continue;
            }
            String tableName = tableNameOf(clazz);
            if (tableName == null || tableName.isEmpty()) {
                continue;
            }
            // 同名テーブルが複数解決された場合は最初に勝った方を優先（事実上発生しない）。
            tableToDomain.putIfAbsent(normalize(tableName), domain);
        }
        return tableToDomain;
    }

    /**
     * Entity の {@code @Table(name)} を読む。未指定ならクラス名フォールバック。
     */
    private static String tableNameOf(JavaClass clazz) {
        if (clazz.isAnnotatedWith(Table.class)) {
            Table table = clazz.getAnnotationOfType(Table.class);
            String name = table.name();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return defaultTableName(clazz.getSimpleName());
    }

    /**
     * クラス単純名から既定テーブル名（スネークケース）を導出する。
     * 末尾の {@code Entity} は除去する（例: {@code UserEntity} → {@code user}）。
     */
    static String defaultTableName(String simpleName) {
        String base = simpleName;
        if (base.endsWith("Entity") && base.length() > "Entity".length()) {
            base = base.substring(0, base.length() - "Entity".length());
        }
        return toSnakeCase(base);
    }

    /** キャメルケース/パスカルケースをスネークケースへ変換する。 */
    private static String toSnakeCase(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 8);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** テーブル名を比較用に正規化する（バッククォート除去＋小文字化）。 */
    static String normalize(String tableName) {
        return tableName.replace("`", "").trim().toLowerCase();
    }
}
