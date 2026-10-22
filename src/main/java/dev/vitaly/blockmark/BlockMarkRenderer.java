package dev.vitaly.blockmark;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public final class BlockMarkRenderer {
    private static final int CYAN_R = 49;
    private static final int CYAN_G = 230;
    private static final int CYAN_B = 245;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int LABEL_BACKGROUND = 0xE8101924;

    // Reuse Minecraft's native line shader/vertex format, but disable the depth test
    // for the secondary low-alpha pass. The normal pass remains depth-tested.
    private static final RenderPipeline THROUGH_WALL_PIPELINE = RenderPipeline
            .builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
            .withLocation(Identifier.of("blockmark", "pipeline/through_wall_lines"))
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .build();
    private static final RenderLayer THROUGH_WALL_LAYER = RenderLayer.of(
            "blockmark:through_wall_lines",
            RenderSetup.builder(THROUGH_WALL_PIPELINE).build()
    );
    private static final BufferAllocator THROUGH_WALL_BUFFER = new BufferAllocator(4096);
    private static final VertexConsumerProvider.Immediate THROUGH_WALL_CONSUMERS =
            VertexConsumerProvider.immediate(THROUGH_WALL_BUFFER);

    private BlockMarkRenderer() {
    }

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!BlockMarkStudioClient.isEnabled() || client.world == null || client.player == null) {
            return;
        }

        List<Annotation> annotations = BlockMarkStudioClient.annotationsForCurrentDimension();
        if (annotations.isEmpty() || context.matrices() == null || context.consumers() == null) {
            return;
        }

        Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        VertexConsumer lines = consumers.getBuffer(RenderLayers.lines());

        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        MatrixStack.Entry matrix = matrices.peek();

        for (Annotation annotation : annotations) {
            if (annotation == null || annotation.type == null) {
                continue;
            }
            switch (annotation.type) {
                case ARROW -> {
                    drawArrow(lines, matrix, annotation);
                    drawThroughWallArrow(matrix, annotation);
                }
                case AREA -> {
                    drawArea(lines, matrix, annotation);
                    drawThroughWallArea(matrix, annotation);
                }
                case LABEL -> { }
            }
        }
        matrices.pop();

        for (Annotation annotation : annotations) {
            if (annotation == null || annotation.type == null) {
                continue;
            }
            if (annotation.type == Annotation.Type.LABEL) {
                drawBillboardText(client, consumers, matrices, camera, annotation.start(), annotation.displayText());
            } else if (annotation.type == Annotation.Type.ARROW) {
                Vec3d middle = annotation.start().add(annotation.end()).multiply(0.5);
                drawBillboardText(client, consumers, matrices, camera, middle.add(0, 0.2, 0), annotation.displayText());
            } else if (annotation.type == Annotation.Type.AREA) {
                double minX = Math.min(annotation.x1, annotation.x2);
                double minY = Math.min(annotation.y1, annotation.y2);
                double minZ = Math.min(annotation.z1, annotation.z2);
                double maxX = Math.max(annotation.x1, annotation.x2) + 1.0;
                double maxY = Math.max(annotation.y1, annotation.y2) + 1.0;
                double maxZ = Math.max(annotation.z1, annotation.z2) + 1.0;
                Vec3d center = new Vec3d((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
                drawBillboardText(client, consumers, matrices, camera, center.add(0, 0.2, 0), annotation.displayText());
            }
        }
    }

    private static void drawArrow(VertexConsumer consumer, MatrixStack.Entry matrix, Annotation annotation) {
        Vec3d start = annotation.start();
        Vec3d end = annotation.end();
        drawGlowSegment(consumer, matrix, start, end);

        Vec3d direction = end.subtract(start);
        if (direction.lengthSquared() < 0.0001) {
            drawPointMarker(consumer, matrix, end);
            return;
        }

        direction = direction.normalize();
        Vec3d reference = Math.abs(direction.y) > 0.92 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d side = direction.crossProduct(reference).normalize();
        Vec3d secondSide = direction.crossProduct(side).normalize();
        double headLength = Math.min(0.65, Math.max(0.28, start.distanceTo(end) * 0.12));
        double headWidth = headLength * 0.48;
        Vec3d base = end.subtract(direction.multiply(headLength));

        drawGlowSegment(consumer, matrix, end, base.add(side.multiply(headWidth)));
        drawGlowSegment(consumer, matrix, end, base.subtract(side.multiply(headWidth)));
        drawGlowSegment(consumer, matrix, end, base.add(secondSide.multiply(headWidth)));
        drawGlowSegment(consumer, matrix, end, base.subtract(secondSide.multiply(headWidth)));
    }


    /**
     * Draw a dim, blended secondary pass with depth testing disabled.
     * The bright primary line is still depth-tested, so hidden geometry stays subtle.
     */
    private static void drawThroughWallArrow(MatrixStack.Entry matrix, Annotation annotation) {
        Vec3d start = annotation.start();
        Vec3d end = annotation.end();
        Vec3d direction = end.subtract(start);
        List<Vec3d> points = new java.util.ArrayList<>();
        points.add(start);
        points.add(end);

        if (direction.lengthSquared() < 0.0001) {
            double r = 0.18;
            points.add(end.add(r, 0, 0));
            points.add(end.add(-r, 0, 0));
            points.add(end.add(0, 0, r));
            points.add(end.add(0, 0, -r));
        } else {
            direction = direction.normalize();
            Vec3d reference = Math.abs(direction.y) > 0.92 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
            Vec3d side = direction.crossProduct(reference).normalize();
            Vec3d secondSide = direction.crossProduct(side).normalize();
            double headLength = Math.min(0.65, Math.max(0.28, start.distanceTo(end) * 0.12));
            double headWidth = headLength * 0.48;
            Vec3d base = end.subtract(direction.multiply(headLength));
            points.add(base.add(side.multiply(headWidth)));
            points.add(end);
            points.add(base.subtract(side.multiply(headWidth)));
            points.add(end);
            points.add(base.add(secondSide.multiply(headWidth)));
            points.add(end);
            points.add(base.subtract(secondSide.multiply(headWidth)));
        }
        drawThroughWallLines(matrix, points);
    }

    private static void drawThroughWallArea(MatrixStack.Entry matrix, Annotation annotation) {
        double minX = Math.min(annotation.x1, annotation.x2);
        double minY = Math.min(annotation.y1, annotation.y2);
        double minZ = Math.min(annotation.z1, annotation.z2);
        double maxX = Math.max(annotation.x1, annotation.x2) + 1.0;
        double maxY = Math.max(annotation.y1, annotation.y2) + 1.0;
        double maxZ = Math.max(annotation.z1, annotation.z2) + 1.0;

        Vec3d[] c = {
                new Vec3d(minX, minY, minZ), new Vec3d(maxX, minY, minZ),
                new Vec3d(maxX, minY, maxZ), new Vec3d(minX, minY, maxZ),
                new Vec3d(minX, maxY, minZ), new Vec3d(maxX, maxY, minZ),
                new Vec3d(maxX, maxY, maxZ), new Vec3d(minX, maxY, maxZ)
        };

        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        List<Vec3d> points = new java.util.ArrayList<>(edges.length * 2);
        for (int[] edge : edges) {
            points.add(c[edge[0]]);
            points.add(c[edge[1]]);
        }
        drawThroughWallLines(matrix, points);
    }

    private static void drawThroughWallLines(MatrixStack.Entry matrix, List<Vec3d> points) {
        if (points.size() < 2) {
            return;
        }

        VertexConsumer hidden = THROUGH_WALL_CONSUMERS.getBuffer(THROUGH_WALL_LAYER);
        // The custom line pipeline uses the same position/color/normal vertex format
        // as Minecraft's regular line layer. Keep a consistent normal for each pair.
        for (int i = 0; i + 1 < points.size(); i += 2) {
            Vec3d from = points.get(i);
            Vec3d to = points.get(i + 1);
            Vec3d delta = to.subtract(from);
            Vec3d normal = delta.lengthSquared() < 0.000001
                    ? new Vec3d(0, 1, 0)
                    : delta.normalize();

            hidden.vertex(matrix, (float) from.x, (float) from.y, (float) from.z)
                    .color(35, 215, 240, 105)
                    .normal(matrix, (float) normal.x, (float) normal.y, (float) normal.z)
                    .lineWidth(5.0f);
            hidden.vertex(matrix, (float) to.x, (float) to.y, (float) to.z)
                    .color(35, 205, 225, 58)
                    .normal(matrix, (float) normal.x, (float) normal.y, (float) normal.z)
                    .lineWidth(1.0f);
        }
        THROUGH_WALL_CONSUMERS.draw();
    }

    private static void drawArea(VertexConsumer consumer, MatrixStack.Entry matrix, Annotation annotation) {
        double minX = Math.min(annotation.x1, annotation.x2);
        double minY = Math.min(annotation.y1, annotation.y2);
        double minZ = Math.min(annotation.z1, annotation.z2);
        double maxX = Math.max(annotation.x1, annotation.x2) + 1.0;
        double maxY = Math.max(annotation.y1, annotation.y2) + 1.0;
        double maxZ = Math.max(annotation.z1, annotation.z2) + 1.0;

        Vec3d[] corners = {
                new Vec3d(minX, minY, minZ), new Vec3d(maxX, minY, minZ),
                new Vec3d(maxX, minY, maxZ), new Vec3d(minX, minY, maxZ),
                new Vec3d(minX, maxY, minZ), new Vec3d(maxX, maxY, minZ),
                new Vec3d(maxX, maxY, maxZ), new Vec3d(minX, maxY, maxZ)
        };
        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int[] edge : edges) {
            drawGlowSegment(consumer, matrix, corners[edge[0]], corners[edge[1]]);
        }
    }

    private static void drawPointMarker(VertexConsumer consumer, MatrixStack.Entry matrix, Vec3d point) {
        double radius = 0.18;
        drawGlowSegment(consumer, matrix, point.add(-radius, 0, 0), point.add(radius, 0, 0));
        drawGlowSegment(consumer, matrix, point.add(0, -radius, 0), point.add(0, radius, 0));
        drawGlowSegment(consumer, matrix, point.add(0, 0, -radius), point.add(0, 0, radius));
    }

    private static void drawGlowSegment(VertexConsumer consumer, MatrixStack.Entry matrix, Vec3d from, Vec3d to) {
        Vec3d direction = to.subtract(from);
        if (direction.lengthSquared() < 0.000001) {
            return;
        }

        // Three screen-space line widths keep annotations readable at a distance:
        // a broad cyan halo, a saturated cyan body, and a crisp white core.
        Vec3d axis = direction.normalize();
        addSegment(consumer, matrix, from, to, 0, 190, 255, 120, axis, 8.0f);
        addSegment(consumer, matrix, from, to, CYAN_R, CYAN_G, CYAN_B, 255, axis, 5.0f);
        addSegment(consumer, matrix, from, to, 245, 255, 255, 255, axis, 2.25f);
    }

    private static void addSegment(VertexConsumer consumer, MatrixStack.Entry matrix,
                                   Vec3d from, Vec3d to, int red, int green, int blue, int alpha,
                                   Vec3d normal, float lineWidth) {
        consumer.vertex(matrix, (float) from.x, (float) from.y, (float) from.z)
                .color(red, green, blue, alpha)
                .normal(matrix, (float) normal.x, (float) normal.y, (float) normal.z)
                .lineWidth(lineWidth);
        consumer.vertex(matrix, (float) to.x, (float) to.y, (float) to.z)
                .color(red, green, blue, alpha)
                .normal(matrix, (float) normal.x, (float) normal.y, (float) normal.z)
                .lineWidth(lineWidth);
    }

    private static void drawBillboardText(MinecraftClient client, VertexConsumerProvider consumers,
                                          MatrixStack matrices, Vec3d camera, Vec3d worldPosition, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        matrices.push();
        matrices.translate(worldPosition.x - camera.x, worldPosition.y - camera.y, worldPosition.z - camera.z);
        matrices.multiply(client.gameRenderer.getCamera().getRotation());
                // Grow world-space text with distance so its apparent on-screen size stays readable.
        float textScale = (float) Math.max(0.025, Math.min(0.12, camera.distanceTo(worldPosition) * 0.0024));
        matrices.scale(-textScale, -textScale, textScale);

        TextRenderer renderer = client.textRenderer;
        float x = -renderer.getWidth(text) / 2.0f;
        renderer.draw(text, x, 0, WHITE, true, matrices.peek().getPositionMatrix(),
                consumers, TextRenderer.TextLayerType.SEE_THROUGH, LABEL_BACKGROUND, 15728880);
        matrices.pop();
    }
}
