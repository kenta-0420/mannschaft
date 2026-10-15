package com.mannschaft.app.common.architecture.fixtures;

import com.mannschaft.app.common.security.SelfScopedEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * fixture: 公開エンドポイントの Mapping メソッドに {@link SelfScopedEndpoint} 監査済マーカー
 * を付与した Controller。
 *
 * <p>{@code @PreAuthorize} も白名簿クラス（{@code *AccessGuard} 等）への呼び出しも一切無い。
 * マーカーが無ければ {@link UnauthorizedController} と同様に「認可シグナルなし」＝違反として
 * 検出されるはずだが、<b>メソッドレベルのマーカー付与により認可シグナルあり（合格）</b>と
 * 判定されるべきケース。
 *
 * <p>{@link SelfScopedEndpoint} は {@code @Target(METHOD)} のためクラス単位の付与ができない。
 * よって他の 3 マーカーにある {@code *MarkerClassAnnotatedController} 相当の対クラス fixture は
 * 意図的に存在しない（クラス単位で全 EP をまとめて承認扱いにできない設計であることの表現）。
 */
@RestController
@RequestMapping("/fixtures/self-scoped-marker-annotated")
public class SelfScopedMarkerAnnotatedController {

    private final DummyPlainService plainService = new DummyPlainService();

    /** メソッドに自己スコープ監査済マーカーを付与した公開エンドポイント（合格すべき）。 */
    @SelfScopedEndpoint("fixture: 検索条件が認証主体に束縛される想定のダミー EP")
    @GetMapping
    public String markedMethod(Long id) {
        return plainService.loadData(id);
    }
}
