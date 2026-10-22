package dev.vitaly.blockmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.network.ServerInfo;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class BlockMarkStudioClient implements ClientModInitializer {
    public static final String MOD_ID = "blockmark";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.lang.reflect.Type LIST_TYPE = new TypeToken<List<Annotation>>() { }.getType();
    private static final Path STORAGE_DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("blockmark-studio");
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "controls"));

    private static final KeyBinding ARROW_KEY = register("key.blockmark.arrow", GLFW.GLFW_KEY_K);
    private static final KeyBinding AREA_KEY = register("key.blockmark.area", GLFW.GLFW_KEY_J);
    private static final KeyBinding LABEL_KEY = register("key.blockmark.label", GLFW.GLFW_KEY_M);
    private static final KeyBinding UNDO_KEY = register("key.blockmark.undo", GLFW.GLFW_KEY_BACKSPACE);
    private static final KeyBinding TOGGLE_KEY = register("key.blockmark.toggle", GLFW.GLFW_KEY_B);

    private static final List<Annotation> ANNOTATIONS = new ArrayList<>();
    private static Vec3d pendingArrowStart;
    private static BlockPos pendingAreaStart;
    private static String activeWorldKey;
    private static String activeDimension;
    private static boolean enabled = true;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(BlockMarkStudioClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(BlockMarkRenderer::render);
        registerCommands();
        LOGGER.info("BlockMark Studio initialized for Minecraft 1.21.11.");
    }

    private static KeyBinding register(String translationKey, int keyCode) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding(translationKey, InputUtil.Type.KEYSYM, keyCode, CATEGORY)
        );
    }

    private static void tick(MinecraftClient client) {
        refreshWorldStore(client);
        if (client.world == null || client.player == null || client.currentScreen != null) {
            return;
        }

        while (ARROW_KEY.wasPressed()) {
            handleArrowKey(client);
        }
        while (AREA_KEY.wasPressed()) {
            handleAreaKey(client);
        }
        while (LABEL_KEY.wasPressed()) {
            addLabelAtTarget(client, "LABEL");
        }
        while (UNDO_KEY.wasPressed()) {
            undo(client);
        }
        while (TOGGLE_KEY.wasPressed()) {
            enabled = !enabled;
            tell(client, "Annotations " + (enabled ? "shown" : "hidden") + ".");
        }
    }

    private static void handleArrowKey(MinecraftClient client) {
        BlockPos target = getTargetedBlock(client);
        if (target == null) {
            tell(client, "Point at a block first.");
            return;
        }

        Vec3d point = Vec3d.ofCenter(target);
        if (pendingArrowStart == null) {
            pendingArrowStart = point;
            tell(client, "Start point set. Aim at the end block and press K again.");
            return;
        }

        Annotation annotation = Annotation.arrow(pendingArrowStart, point, currentDimension(client));
        ANNOTATIONS.add(annotation);
        pendingArrowStart = null;
        saveStore();
        tell(client, "Arrow added: " + annotation.displayText() + ".");
    }

    private static void handleAreaKey(MinecraftClient client) {
        BlockPos target = getTargetedBlock(client);
        if (target == null) {
            tell(client, "Point at a block first.");
            return;
        }

        if (pendingAreaStart == null) {
            pendingAreaStart = target.toImmutable();
            tell(client, "First corner set. Aim at the opposite corner and press J again.");
            return;
        }

        ANNOTATIONS.add(Annotation.area(
                pendingAreaStart.getX(), pendingAreaStart.getY(), pendingAreaStart.getZ(),
                target.getX(), target.getY(), target.getZ(),
                currentDimension(client)
        ));
        pendingAreaStart = null;
        saveStore();
        tell(client, "Area outline added.");
    }

    private static void addLabelAtTarget(MinecraftClient client, String text) {
        if (client.world == null) {
            tell(client, "Enter a world first.");
            return;
        }
        BlockPos target = getTargetedBlock(client);
        if (target == null) {
            tell(client, "Point at a block first.");
            return;
        }

        Vec3d anchor = new Vec3d(target.getX() + 0.5, target.getY() + 1.25, target.getZ() + 0.5);
        ANNOTATIONS.add(Annotation.label(anchor, text, currentDimension(client)));
        saveStore();
        tell(client, "Label added: " + text + ".");
    }

    private static BlockPos getTargetedBlock(MinecraftClient client) {
        if (client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().toImmutable();
        }
        return null;
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("blockmark")
                        .then(ClientCommandManager.literal("label")
                                .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            addLabelAtTarget(MinecraftClient.getInstance(),
                                                    StringArgumentType.getString(context, "text"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("rename")
                                .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String text = StringArgumentType.getString(context, "text");
                                            for (int i = ANNOTATIONS.size() - 1; i >= 0; i--) {
                                                Annotation annotation = ANNOTATIONS.get(i);
                                                if (annotation.type == Annotation.Type.LABEL) {
                                                    annotation.text = text;
                                                    saveStore();
                                                    tell(MinecraftClient.getInstance(), "Last label renamed.");
                                                    return 1;
                                                }
                                            }
                                            tell(MinecraftClient.getInstance(), "There is no label to rename.");
                                            return 0;
                                        })))
                        .then(ClientCommandManager.literal("undo").executes(context -> {
                            undo(MinecraftClient.getInstance());
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("clear").executes(context -> {
                            ANNOTATIONS.clear();
                            pendingArrowStart = null;
                            pendingAreaStart = null;
                            saveStore();
                            tell(MinecraftClient.getInstance(), "Annotations cleared for this world.");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("toggle").executes(context -> {
                            enabled = !enabled;
                            tell(MinecraftClient.getInstance(), "Annotations " + (enabled ? "shown" : "hidden") + ".");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("list").executes(context -> {
                            tell(MinecraftClient.getInstance(), "Saved annotations: " + ANNOTATIONS.size() + ".");
                            return 1;
                        }))
                )
        );
    }

    private static void undo(MinecraftClient client) {
        if (ANNOTATIONS.isEmpty()) {
            pendingArrowStart = null;
            pendingAreaStart = null;
            tell(client, "Nothing to undo.");
            return;
        }
        ANNOTATIONS.remove(ANNOTATIONS.size() - 1);
        saveStore();
        tell(client, "Last annotation removed.");
    }

    private static void refreshWorldStore(MinecraftClient client) {
        if (client.world == null) {
            if (activeWorldKey != null) {
                saveStore();
                ANNOTATIONS.clear();
                activeWorldKey = null;
                activeDimension = null;
                pendingArrowStart = null;
                pendingAreaStart = null;
            }
            return;
        }

        String key = resolveWorldKey(client);
        String dimension = currentDimension(client);
        if (key.equals(activeWorldKey)) {
            activeDimension = dimension;
            return;
        }

        saveStore();
        ANNOTATIONS.clear();
        pendingArrowStart = null;
        pendingAreaStart = null;
        activeWorldKey = key;
        activeDimension = dimension;

        Path file = storageFile(key);
        if (Files.isRegularFile(file)) {
            try {
                List<Annotation> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
                if (loaded != null) {
                    for (Annotation annotation : loaded) {
                        if (annotation != null && annotation.type != null && annotation.dimension != null) {
                            ANNOTATIONS.add(annotation);
                        }
                    }
                }
                LOGGER.info("Loaded {} annotations for {}", ANNOTATIONS.size(), key);
            } catch (IOException | RuntimeException exception) {
                LOGGER.error("Could not load BlockMark data from {}", file, exception);
                tell(client, "Could not load saved annotations. Check latest.log.");
            }
        }
    }

    private static String resolveWorldKey(MinecraftClient client) {
        String identity;
        if (client.getServer() != null) {
            identity = "singleplayer:" + client.getServer().getSaveProperties().getLevelName();
        } else {
            ServerInfo entry = client.getCurrentServerEntry();
            identity = entry != null && entry.address != null
                    ? "multiplayer:" + entry.address.trim().toLowerCase(Locale.ROOT)
                    : "multiplayer:unknown";
        }
        return sha256(identity);
    }

    private static String currentDimension(MinecraftClient client) {
        return client.world.getRegistryKey().getValue().toString();
    }

    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Path storageFile(String key) {
        return STORAGE_DIRECTORY.resolve(key + ".json");
    }

    public static void saveStore() {
        if (activeWorldKey == null) {
            return;
        }

        Path file = storageFile(activeWorldKey);
        Path temporaryFile = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(STORAGE_DIRECTORY);
            Files.writeString(temporaryFile, GSON.toJson(ANNOTATIONS, LIST_TYPE), StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.error("Could not save BlockMark annotations to {}", file, exception);
            tell(MinecraftClient.getInstance(), "Could not save annotations. Check latest.log.");
        }
    }

    public static List<Annotation> annotationsForCurrentDimension() {
        if (activeDimension == null) {
            return List.of();
        }
        return ANNOTATIONS.stream()
                .filter(annotation -> activeDimension.equals(annotation.dimension))
                .toList();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private static void tell(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[BlockMark] " + message), false);
        }
    }
}
