package com.epi.validator.engine;

import com.epi.validator.model.EpiType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypeResolutionTest {

    @Test
    void autoFollowsDetection() {
        TypeResolution r = TypeResolution.resolve("auto", EpiType.TYPE_2);
        assertThat(r.isAuto()).isTrue();
        assertThat(r.effective()).isEqualTo(EpiType.TYPE_2);
        assertThat(r.detected()).isEqualTo(EpiType.TYPE_2);
    }

    @Test
    void nullOrBlankMeansAuto() {
        assertThat(TypeResolution.resolve(null, EpiType.TYPE_1).isAuto()).isTrue();
        assertThat(TypeResolution.resolve("  ", EpiType.TYPE_1).isAuto()).isTrue();
    }

    @Test
    void explicitRequestOverridesDetection_neverDowngrades() {
        // The catastrophic case this model prevents: content detected as Type 2 (missing
        // clinical resources) must still be judged as the requested Type 3.
        TypeResolution r = TypeResolution.resolve("3", EpiType.TYPE_2);
        assertThat(r.isAuto()).isFalse();
        assertThat(r.detected()).isEqualTo(EpiType.TYPE_2);
        assertThat(r.effective()).isEqualTo(EpiType.TYPE_3);
    }

    @Test
    void invalidRequestRejected() {
        assertThatThrownBy(() -> TypeResolution.resolve("4", EpiType.TYPE_1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
