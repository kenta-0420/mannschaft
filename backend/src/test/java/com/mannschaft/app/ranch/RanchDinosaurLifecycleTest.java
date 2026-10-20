package com.mannschaft.app.ranch;

import com.mannschaft.app.ranch.entity.RanchDinosaurEntity;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC04/61: 孵化と永久名は原子確定し、三段階成長でも同個体・同名。 */
class RanchDinosaurLifecycleTest {
    private final Instant now = Instant.parse("2026-10-08T00:00:00Z");
    private RanchDinosaurEntity readyEgg() {
        return RanchDinosaurEntity.builder().stage(DinosaurStage.EGG).eggReadyAt(now)
                .selectionConfirmedAt(now.minusSeconds(1)).xp(0).version(0).build();
    }

    @Test
    void 命名と孵化時刻を同じ瞬間で確定しXPは零() {
        var dinosaur = readyEgg();
        dinosaur.hatch("ひかり", now);
        assertThat(dinosaur.getStage()).isEqualTo(DinosaurStage.BABY);
        assertThat(dinosaur.getName()).isEqualTo("ひかり");
        assertThat(dinosaur.getNamedAt()).isEqualTo(now).isEqualTo(dinosaur.getHatchedAt());
        assertThat(dinosaur.getXp()).isZero();
    }

    @Test
    void 孵化後の別名は拒否して元名を保持する() {
        var dinosaur = readyEgg();
        dinosaur.hatch("ひかり", now);
        assertThatThrownBy(() -> dinosaur.hatch("別名", now.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThat(dinosaur.getName()).isEqualTo("ひかり");
    }

    @Test
    void 期限直前と未選定は卵のまま拒否する() {
        var dinosaur = readyEgg();
        assertThatThrownBy(() -> dinosaur.hatch("ひかり", now.minusNanos(1000))).isInstanceOf(IllegalStateException.class);
        assertThat(dinosaur.getStage()).isEqualTo(DinosaurStage.EGG);
        var unselected = RanchDinosaurEntity.builder().stage(DinosaurStage.EGG).eggReadyAt(now).build();
        assertThatThrownBy(() -> unselected.hatch("ひかり", now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 累積ケアXPの閾値で成長期と成体になり名前を維持する() {
        var dinosaur = readyEgg();
        dinosaur.hatch("ひかり", now);
        dinosaur.applyCareXp(4, 5, 10);
        assertThat(dinosaur.getStage()).isEqualTo(DinosaurStage.BABY);
        dinosaur.applyCareXp(1, 5, 10);
        assertThat(dinosaur.getStage()).isEqualTo(DinosaurStage.JUVENILE);
        dinosaur.applyCareXp(5, 5, 10);
        assertThat(dinosaur.getStage()).isEqualTo(DinosaurStage.ADULT);
        assertThat(dinosaur.getName()).isEqualTo("ひかり");
    }

    @Test
    void 卵XPと負のXPを拒否する() {
        var dinosaur = readyEgg();
        assertThatThrownBy(() -> dinosaur.applyCareXp(1, 5, 10)).isInstanceOf(IllegalStateException.class);
        dinosaur.hatch("ひかり", now);
        assertThatThrownBy(() -> dinosaur.applyCareXp(-1, 5, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThat(dinosaur.getXp()).isZero();
    }
}
