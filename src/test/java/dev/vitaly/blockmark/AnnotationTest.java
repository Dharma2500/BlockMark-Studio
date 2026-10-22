package dev.vitaly.blockmark;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnnotationTest {
    @Test
    void countsBothEndBlocksForStraightMeasurement() {
        Annotation annotation = Annotation.arrow(
                new Vec3d(0.5, 64.5, 0.5),
                new Vec3d(15.5, 64.5, 0.5),
                "minecraft:overworld"
        );

        assertEquals("16 blocks", annotation.displayText());
    }

    @Test
    void measuresAreaDimensionsInclusively() {
        Annotation annotation = Annotation.area(
                0, 60, 0,
                15, 67, 11,
                "minecraft:overworld"
        );

        assertEquals("16 × 12 × 8 blocks", annotation.displayText());
    }

    @Test
    void labelsUseCustomText() {
        Annotation annotation = Annotation.label(
                new Vec3d(0.5, 65.25, 0.5),
                "ВХОД ВОДЫ",
                "minecraft:overworld"
        );

        assertEquals("ВХОД ВОДЫ", annotation.displayText());
    }
}
