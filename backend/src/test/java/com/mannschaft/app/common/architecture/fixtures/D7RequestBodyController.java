package com.mannschaft.app.common.architecture.fixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * D-7 番人メタテスト用の fixture Controller。
 *
 * <p>{@code @RequestBody}/{@code @RequestPart}（JSON 経路）と {@code @ModelAttribute}
 * （フォーム経路）で各 fixture DTO を受け、番人の「バインドされる型」の根を提供する。
 * 本 fixture は test 配下にあり、番人本体は {@code DoNotIncludeTests} で除外しているため、
 * 本番の D-7 解析へは混入しない。
 */
@RestController
@RequestMapping("/fixtures/d7")
public class D7RequestBodyController {

    /** 違反: 是正前 {@code CreateThreadRequest} と同型。 */
    @PostMapping("/broken")
    public String broken(@RequestBody D7PreFixCreateThreadRequestReplica request) {
        return request.getTitle();
    }

    /** 合格: {@code @JsonCreator} 付きコンストラクタあり。 */
    @PostMapping("/creator")
    public String creator(@RequestBody D7JsonCreatorRequest request) {
        return request.getTitle();
    }

    /** 合格: 引数無しコンストラクタ + setter（Lombok {@code @Data} 様式）。 */
    @PostMapping("/no-args")
    public String noArgs(@RequestBody D7NoArgsAndSettersRequest request) {
        return request.getTitle();
    }

    /** 合格: コンストラクタ 1 本（{@code -parameters} で暗黙 creator になる）。 */
    @PostMapping("/single")
    public String single(@RequestBody D7SingleConstructorRequest request) {
        return request.getTitle();
    }

    /** 合格: {@code @JsonCreator} 付き static ファクトリあり。 */
    @PostMapping("/static-factory")
    public String staticFactory(@RequestBody D7StaticFactoryCreatorRequest request) {
        return request.getTitle();
    }

    /** 合格: {@code @JsonDeserialize(using = ...)} でクラス自身の生成手段が与えられている。 */
    @PostMapping("/custom-deserializer")
    public String customDeserializer(@RequestBody D7CustomDeserializerRequest request) {
        return request.getTitle();
    }

    /** 違反: {@code @JsonDeserialize} は付くが {@code contentUsing} のみで自身の生成手段が無い。 */
    @PostMapping("/content-deserialize")
    public String contentDeserialize(@RequestBody D7ContentDeserializeBrokenRequest request) {
        return request.getTitle();
    }

    /**
     * 入れ子検出: {@code List<D7RootRequest>} 経由で、さらに
     * {@code List<...>} と<b>配列</b>の 2 経路で壊れた入れ子 DTO へ到達する。
     */
    @PutMapping("/bulk")
    public int bulk(@RequestBody List<D7RootRequest> requests) {
        return requests.size();
    }

    /** {@code @RequestPart} も JSON デシリアライズ経路であることの担保。 */
    @PostMapping("/multipart")
    public String multipart(@RequestPart("meta") D7JsonCreatorRequest request) {
        return request.getTitle();
    }

    /** 違反（フォーム経路）: {@code @JsonCreator} があっても Spring バインダは救われない。 */
    @GetMapping("/search-broken")
    public String searchBroken(@ModelAttribute D7FormBrokenSearchRequest request) {
        return request.getKeyword();
    }

    /** 合格（フォーム経路）: 引数無しコンストラクタ + setter。 */
    @GetMapping("/search-ok")
    public String searchOk(@ModelAttribute D7FormOkSearchRequest request) {
        return request.getKeyword();
    }
}
