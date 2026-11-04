package com.mannschaft.app.common.architecture.plaintexttoken;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * fixture（番人3）: 乱数の値の流れが「メソッド内の分岐の合流・配列の別名」を通る形と、「メソッドをまたぐ」既知の限界の形。
 *
 * <p>陽性（違反として検出されるべき。メソッド内の流れ）:</p>
 * <ul>
 *   <li>{@link #issueWithBranchMerge(boolean)}: if/else の一方で乱数、他方で固定値を代入して合流</li>
 *   <li>{@link #issueWithBranchMergeOverwrite(boolean)}: 乱数を代入したあと、分岐の中で固定値に上書きする枝がある</li>
 *   <li>{@link #issueWithTernary(boolean)}: 三項演算子で乱数と固定値が合流</li>
 *   <li>{@link #issueWithArrayAlias()}: 乱数を詰める配列の別名から値を取り出す</li>
 *   <li>{@link #issueWithAliasKeptInBranch(boolean)}: 別名のあと、分岐の中で別名の変数に新しい配列を代入する枝がある</li>
 *   <li>{@link #issueWithAliasKeptByTernary(boolean)}: 別名のあと、三項演算子で別名の変数へ新しい配列を代入する</li>
 * </ul>
 * <p>陰性（検出されてはならない）:</p>
 * <ul>
 *   <li>{@link #ternaryCleanTokenWithCorrelationUuid(boolean)}: UUID は相関 ID にだけ届き、token 系は固定値同士の三項演算子</li>
 * </ul>
 * <p>既知の偽陰性（<b>検出されないことを固定</b>する。メソッドをまたぐ流れは追えない。
 * 将来検出できるようになったら、試験の期待値を反転する）:</p>
 * <ul>
 *   <li>{@link #issueViaGeneratorHelper()}: 乱数の生成ヘルパ（{@link #newRandomToken()}）の戻り値を token 系に詰める</li>
 *   <li>{@link #issueViaWriterHelper()}: 乱数を書込みヘルパ（{@link #writeToken}）へ渡し、そちらで token 系に詰める</li>
 * </ul>
 */
public class FlowSelfIssuingFixtureService {

    private static final int RANDOM_BYTE_LENGTH = 32;

    private static final String FIXED_VALUE = "fixed";

    private static final String OTHER_FIXED_VALUE = "other";

    private final SecureRandom secureRandom = new SecureRandom();

    public PlaintextTokenFixtureEntity issueWithBranchMerge(boolean external) {
        String value;
        if (external) {
            value = UUID.randomUUID().toString();
        } else {
            value = FIXED_VALUE;
        }
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(value);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithBranchMergeOverwrite(boolean fixed) {
        String value = UUID.randomUUID().toString();
        if (fixed) {
            value = FIXED_VALUE;
        }
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(value);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithTernary(boolean external) {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(external ? UUID.randomUUID().toString() : FIXED_VALUE);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithArrayAlias() {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        byte[] alias = bytes;
        secureRandom.nextBytes(bytes);
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(HexFormat.of().formatHex(alias));
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithAliasKeptInBranch(boolean flag) {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        byte[] alias = bytes;
        if (flag) {
            alias = new byte[RANDOM_BYTE_LENGTH];
        }
        secureRandom.nextBytes(bytes);
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(HexFormat.of().formatHex(alias));
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithAliasKeptByTernary(boolean flag) {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        byte[] alias = bytes;
        alias = flag ? new byte[RANDOM_BYTE_LENGTH] : alias;
        secureRandom.nextBytes(bytes);
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(HexFormat.of().formatHex(alias));
        return entity;
    }

    public PlaintextTokenFixtureEntity ternaryCleanTokenWithCorrelationUuid(boolean flag) {
        HashedTokenFixtureEntity correlated = new HashedTokenFixtureEntity();
        correlated.setCorrelationId(UUID.randomUUID().toString());
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(flag ? FIXED_VALUE : OTHER_FIXED_VALUE);
        return entity;
    }

    /** 乱数の生成ヘルパ。Entity に書かないので、それ自体は違反でない。 */
    public String newRandomToken() {
        return UUID.randomUUID().toString();
    }

    /** 既知の偽陰性: 乱数を別メソッドで作り、戻り値を token 系に詰める（メソッドをまたぐ流れ）。 */
    public PlaintextTokenFixtureEntity issueViaGeneratorHelper() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(newRandomToken());
        return entity;
    }

    /** 書込みヘルパ。乱数かどうかを知らないので、それ自体は違反でない。 */
    public void writeToken(PlaintextTokenFixtureEntity entity, String value) {
        entity.setToken(value);
    }

    /** 既知の偽陰性: 乱数を書込みヘルパへ渡し、そちらで token 系に詰める（メソッドをまたぐ流れ）。 */
    public PlaintextTokenFixtureEntity issueViaWriterHelper() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        writeToken(entity, UUID.randomUUID().toString());
        return entity;
    }
}
