package com.mannschaft.app.common.erdiagram;

import com.mannschaft.app.common.architecture.DomainPackages;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinTable;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * {@code @Entity} が宣言するテーブル名から「テーブル名 → 所属ドメイン」を解決する。
 *
 * <p>ArchUnit の {@code TableDomainResolver} と同じ解決規則（{@code @Table(name)} → ドメインは
 * {@code com.mannschaft.app} 直下のパッケージ）を、ArchUnit を使わずクラスパス走査で行う。
 * ArchUnit を参照するとこのテスト群が {@code ArchUnitTestTagGuardTest} の対象になり、
 * Spring 系テストと別 JVM に分離する前提を崩すため。
 *
 * <p>Entity 本体に加え、{@code @SecondaryTable} / {@code @CollectionTable} / {@code @JoinTable} が
 * 宣言するテーブル（Entity を持たない付随テーブル）も、宣言元 Entity のドメインへ紐付ける。
 */
final class EntityTableDomains {

    private static final String BASE_PACKAGE = "com.mannschaft.app";

    private EntityTableDomains() {
    }

    /** @return テーブル名（小文字）→ ドメイン名。同名が複数ならクラス名の辞書順で先勝ち（決定的）。 */
    static Map<String, String> scan() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        TreeSet<String> classNames = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
            classNames.add(bd.getBeanClassName());
        }
        Map<String, String> result = new TreeMap<>();
        for (String className : classNames) {
            Class<?> clazz;
            try {
                clazz = Class.forName(className, false, EntityTableDomains.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                throw new IllegalStateException("Entity クラスを読み込めない: " + className, e);
            }
            String domain = DomainPackages.domainOf(clazz.getPackageName());
            if (domain == null) {
                continue;
            }
            put(result, tableNameOf(clazz), domain);
            for (SecondaryTable st : clazz.getAnnotationsByType(SecondaryTable.class)) {
                put(result, st.name(), domain);
            }
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    CollectionTable ct = f.getAnnotation(CollectionTable.class);
                    if (ct != null) {
                        put(result, ct.name(), domain);
                    }
                    JoinTable jt = f.getAnnotation(JoinTable.class);
                    if (jt != null) {
                        put(result, jt.name(), domain);
                    }
                }
            }
        }
        return result;
    }

    private static void put(Map<String, String> map, String tableName, String domain) {
        if (tableName == null || tableName.isBlank()) {
            return;
        }
        map.putIfAbsent(tableName.replace("`", "").trim().toLowerCase(), domain);
    }

    private static String tableNameOf(Class<?> clazz) {
        Table table = clazz.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        // Spring Boot 既定の物理命名（CamelCase → snake_case）。@Entity(name) があればそれを優先する。
        Entity entity = clazz.getAnnotation(Entity.class);
        String base = entity != null && !entity.name().isBlank() ? entity.name() : clazz.getSimpleName();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
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
}
