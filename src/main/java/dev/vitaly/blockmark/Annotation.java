package dev.vitaly.blockmark;

import net.minecraft.util.math.Vec3d;

import java.util.Locale;

public final class Annotation {
    public enum Type {
        ARROW,
        AREA,
        LABEL
    }

    public Type type;
    public String dimension;
    public double x1;
    public double y1;
    public double z1;
    public double x2;
    public double y2;
    public double z2;
    public String text;

    public Annotation() {
    }

    public static Annotation arrow(Vec3d start, Vec3d end, String dimension) {
        Annotation value = new Annotation();
        value.type = Type.ARROW;
        value.dimension = dimension;
        value.x1 = start.x;
        value.y1 = start.y;
        value.z1 = start.z;
        value.x2 = end.x;
        value.y2 = end.y;
        value.z2 = end.z;
        value.text = measurementLabel(start, end);
        return value;
    }

    public static Annotation area(int x1, int y1, int z1, int x2, int y2, int z2, String dimension) {
        Annotation value = new Annotation();
        value.type = Type.AREA;
        value.dimension = dimension;
        value.x1 = x1;
        value.y1 = y1;
        value.z1 = z1;
        value.x2 = x2;
        value.y2 = y2;
        value.z2 = z2;
        value.text = "";
        return value;
    }

    public static Annotation label(Vec3d position, String text, String dimension) {
        Annotation value = new Annotation();
        value.type = Type.LABEL;
        value.dimension = dimension;
        value.x1 = position.x;
        value.y1 = position.y;
        value.z1 = position.z;
        value.x2 = position.x;
        value.y2 = position.y;
        value.z2 = position.z;
        value.text = text;
        return value;
    }

    public Vec3d start() {
        return new Vec3d(x1, y1, z1);
    }

    public Vec3d end() {
        return new Vec3d(x2, y2, z2);
    }

    public String displayText() {
        if (type == Type.ARROW) {
            return measurementLabel(start(), end());
        }
        if (type == Type.AREA) {
            long width = Math.abs(Math.round(x2 - x1)) + 1L;
            long height = Math.abs(Math.round(y2 - y1)) + 1L;
            long depth = Math.abs(Math.round(z2 - z1)) + 1L;
            return width + " × " + depth + " × " + height + " блоков";
        }
        return text == null || text.isBlank() ? "LABEL" : text;
    }

    private static String russianBlocksWord(long count) {
        long n = Math.abs(count) % 100;
        if (n >= 11 && n <= 14) {
            return "блоков";
        }
        return switch ((int) (Math.abs(count) % 10)) {
            case 1 -> "блок";
            case 2, 3, 4 -> "блока";
            default -> "блоков";
        };
    }

    private static String measurementLabel(Vec3d start, Vec3d end) {
        double dx = Math.abs(end.x - start.x);
        double dy = Math.abs(end.y - start.y);
        double dz = Math.abs(end.z - start.z);
        int nonZeroAxes = (dx > 0.0001 ? 1 : 0) + (dy > 0.0001 ? 1 : 0) + (dz > 0.0001 ? 1 : 0);

        // On a single axis, include both selected blocks: centers 15 blocks apart make a 16-block row.
        if (nonZeroAxes <= 1) {
            long count = Math.round(dx + dy + dz) + 1L;
            return count + " " + russianBlocksWord(count) + "";
        }

        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return String.format(Locale.ROOT, "%.1f блоков", distance);
    }
}
