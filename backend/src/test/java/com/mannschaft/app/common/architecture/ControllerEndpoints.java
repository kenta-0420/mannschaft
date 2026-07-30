package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller 走査述語の<b>共有ユーティリティ</b>。
 *
 * <p>「{@code @RestController}/{@code @Controller} クラスに定義された public な
 * Mapping アノテーション付きメソッド（＝公開エンドポイント）」を選別する述語を、
 * 複数の Controller 番人テストから再利用するために package-visible の static ヘルパーへ
 * 抽出したもの。
 *
 * <p>従来 {@link AuthzControllerGuardArchTest} の private static メソッド
 * {@code areMappingEndpointsOfControllerClasses()} / {@code isControllerClass()} として
 * 存在していたロジックをそのまま移設した。<b>述語の判定内容・述語説明文は抽出前と完全に
 * 同一であり、既存ルールの照合キー（{@code .as(...)}）や凍結ストアの挙動には一切影響しない</b>
 * （{@link DomainPackages} の抽出と同じ方針）。
 *
 * <p>利用側:
 * <ul>
 *   <li>{@link AuthzControllerGuardArchTest} — 公開EPの認可シグナル有無（Wave4）</li>
 *   <li>{@link ControllerEntityResponseArchTest} — 公開EPが JPA Entity を返さないこと（D-6）</li>
 *   <li>{@link JsonRequestBodyCreatorArchTest} — 公開EPの {@code @RequestBody} 型が Jackson で
 *       デシリアライズ可能なこと（D-7）</li>
 * </ul>
 */
final class ControllerEndpoints {

    private ControllerEndpoints() {
        // ユーティリティクラス
    }

    /** 「@RestController/@Controller クラスの public Mapping メソッド」を表す述語。 */
    static DescribedPredicate<JavaMethod> areMappingEndpointsOfControllerClasses() {
        return new DescribedPredicate<>(
                "are public Mapping-annotated methods of @RestController/@Controller classes") {
            @Override
            public boolean test(JavaMethod method) {
                if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
                    return false;
                }
                JavaClass owner = method.getOwner();
                if (!isControllerClass(owner)) {
                    return false;
                }
                return method.isMetaAnnotatedWith(RequestMapping.class);
            }
        };
    }

    /** クラスに {@code @RestController} または {@code @Controller} が付いているか。 */
    static boolean isControllerClass(JavaClass clazz) {
        return clazz.isAnnotatedWith(RestController.class)
            || clazz.isAnnotatedWith(Controller.class);
    }
}
