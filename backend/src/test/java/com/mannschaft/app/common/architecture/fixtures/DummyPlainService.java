package com.mannschaft.app.common.architecture.fixtures;

/**
 * 認可を一切行わないダミーサービス（メタテスト用 fixture）。
 *
 * <p>クラス名は認可 suffix を持たず、内部でも認可クラスを一切呼ばない。
 * これを呼ぶだけの Controller エンドポイントは「認可シグナルなし」＝違反として
 * 検出されるべき（偽陰性ゼロの担保）。
 */
public class DummyPlainService {

    /** 認可と無関係の業務処理。認可シグナルにならない。 */
    public String loadData(Long id) {
        return "data-" + id;
    }
}
