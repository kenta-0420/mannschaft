package com.mannschaft.app.common.architecture.fixtures;

import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * fixture: 公開エンドポイントの Mapping メソッドに {@link AuthorizedByPathConfig} 監査済マーカー
 * を付与した Controller。
 *
 * <p>{@code @PreAuthorize} も白名簿クラス（{@code *AccessGuard} 等）への呼び出しも一切無い。
 * マーカーが無ければ {@link UnauthorizedController} と同様に「認可シグナルなし」＝違反として
 * 検出されるはずだが、<b>メソッドレベルのマーカー付与により認可シグナルあり（合格）</b>と
 * 判定されるべきケース（メソッド {@code isAnnotatedWith} 経路の担保）。
 */
@RestController
@RequestMapping("/fixtures/path-config-marker-annotated")
public class PathConfigMarkerAnnotatedController {

    private final DummyPlainService plainService = new DummyPlainService();

    /** メソッドに監査済マーカーを付与した公開エンドポイント（合格すべき）。 */
    @AuthorizedByPathConfig
    @GetMapping
    public String markedMethod(Long id) {
        return plainService.loadData(id);
    }
}
