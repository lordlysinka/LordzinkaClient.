package com.lordzinka.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * Lordzinka Client (Fabric, Minecraft 1.21.11, Yarn mappings)
 *
 * Modules: HUD, Chunk Finder, Block ESP, Entity ESP, Player Radar (60m alert), Nametags,
 *          Amethyst ESP (geode analysis), Vanish Detector, Auto Totem.
 * Control everything with /lz (or /lordzinka).
 *
 * Rendering is done by projecting world coordinates onto the HUD layer, so no mixins or
 * custom render pipelines are needed.
 */
public class LordzinkaClient implements ClientModInitializer {

    static final String NAME = "Lordzinka Client";
    static final String PREFIX = "[Lordzinka] ";

    // ------------------------------------------------------------------ config
    static boolean hudOn = true, espOn = true, blocksOn = true, chunksOn = true, amethystOn = true;
    static boolean radarOn = true, nametagsOn = true, vanishOn = true, totemOn = false;
    static boolean tracers = false, notify = true;
    static boolean fPlayers = true, fHostile = false, fPassive = false, fItems = false, fVehicles = true;
    static int threshold = 15;       // chunk score needed to flag
    static int blockRange = 128;     // block ESP draw distance
    static int entityRange = 256;    // entity ESP draw distance
    static int alertRadius = 60;     // player radar radius
    static int totemDelay = 2;       // ticks between totem swaps
    // HUD
    static boolean hudInfo = true, hudCoords = true, hudFps = true, hudList = true, hudBanner = true, rainbow = true;
    static int accentHue = 200;
    // Chunk Finder
    static int chunkMax = 40, chunkRange = 512, chunkSpeed = 2, chunkRescan = 100;
    static boolean chunkLabels = true;
    // Block ESP
    static int blockMax = 800, blockFillAlpha = 25;
    static boolean blockFill = false, blockLabels = false;
    // Amethyst ESP
    static int amRange = 256, amMax = 12, amMinPct = 0;
    static boolean amBudding = true;
    // Entity ESP
    static int espStyle = 0;
    static boolean espLabels = true, espDistance = true;
    static int huePlayer = 0, hueHostile = 30, huePassive = 120, hueItem = 195, hueVehicle = 50;
    // Nametags
    static int ntRange = 160;
    static boolean ntHealth = true, ntDistance = true, ntArmor = true, ntItem = true, ntPing = false, ntBackground = true;
    // Tracers
    static boolean trChunks = true, trEntities = true, trGeodes = true;
    static int trOrigin = 0;
    // Player Radar
    static int radarList = 5;
    static boolean radarSound = true, radarChat = true;
    // Vanish Detect
    static boolean vanTab = true, vanSpec = true, vanChat = true;
    // Auto Totem
    static int totemHealth = 0;
    static boolean totemHotbar = true, totemWarn = true;
    // Chunk Finder style
    static int chunkStyle = 0;        // 0 square, 1 column, 2 box
    static int chunkYMode = 0;        // 0 detected, 1 fixed
    static int chunkY = 60, chunkHeight = 16, chunkColorMode = 0, chunkHue = 20, chunkAlpha = 30;
    static boolean chunkFill = true, chunkOutline = true;
    // Hole ESP
    static boolean holesOn = true, holeFill = true, holeOutline = true, holeFloor = false, holeLabels = true;
    static int holeRange = 16, holeVRange = 32, holeMin = 3, holeMaxLen = 128, holeCap = 60;
    static int holeHue = 28, holeHue2 = 350, holeMode = 0, holeAlpha = 35;
    // extras
    static boolean espFill = false, espHealthBar = true, amFill = true, trHoles = true, hudNether = true;
    static int espFillAlpha = 15;
    // v3 additions
    static int amClusterMin = 10;                       // fully grown clusters (3x3 chunks) that count as "a player was here"
    static boolean chunkBeams = true, chunkPulse = true;
    static int blockStyle = 2, blockColorMode = 0, blockHue = 200;   // style: 0 outline, 1 fill, 2 both
    static boolean radarWidget = true, radarCards = true, toastsOn = true;
    static int radarSize = 76, radarPos = 0;
    static final Set<Block> espBlocks = new HashSet<>();

    // ------------------------------------------------------------------ data
    static final class ChunkInfo {
        int cx, cz, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        float score;
        boolean notified, amNotified;
        int cMinY = Integer.MAX_VALUE, cMaxY = Integer.MIN_VALUE;   // y-range of grown amethyst clusters
        final LongArrayList esp = new LongArrayList();
        final LongArrayList amBlocks = new LongArrayList();
        final LongArrayList amBudding = new LongArrayList();
        final LongArrayList amClusters = new LongArrayList();
    }

    static final class Geode {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int blocks, budding, clusters;
        double likelihood;
        double cx() { return (minX + maxX + 1) / 2.0; }
        double cy() { return (minY + maxY + 1) / 2.0; }
        double cz() { return (minZ + maxZ + 1) / 2.0; }
    }

    record Nearby(UUID id, String name, float dist, float hp, BlockPos pos) { }

    static final Map<String, Map<Long, ChunkInfo>> data = new HashMap<>();
    static final LinkedHashSet<Long> queue = new LinkedHashSet<>();
    static final Map<Block, Float> weightCache = new HashMap<>();
    static final Map<Block, Integer> colorCache = new HashMap<>();
    static List<Geode> geodes = new ArrayList<>();
    static final List<Nearby> nearby = new ArrayList<>();
    static final Set<UUID> inRange = new HashSet<>();
    static final Map<UUID, String> inRangeNames = new HashMap<>();
    static int lastTotems = 0;
    static final Set<UUID> vanishWarned = new HashSet<>();
    static final Set<UUID> spectatorWarned = new HashSet<>();
    static final Map<UUID, String> vanishNow = new LinkedHashMap<>();
    static int tickCounter = 0, totemCooldown = 0;

    // keyword -> weight (first match wins). Tuned to favour player-built things.
    static final Object[][] RULES = {
            {"torchflower", 0f},
            {"beacon", 15f}, {"netherite_block", 8f}, {"shulker_box", 6f},
            {"chest", 3f}, {"barrel", 3f}, {"enchanting_table", 3f},
            {"iron_block", 3f}, {"gold_block", 3f}, {"diamond_block", 3f}, {"emerald_block", 3f},
            {"lapis_block", 3f}, {"copper_block", 3f}, {"redstone_block", 3f},
            {"furnace", 2f}, {"smoker", 2f}, {"hopper", 2f}, {"brewing_stand", 2f}, {"jukebox", 2f},
            {"_bed", 2f}, {"tnt", 2f}, {"slime_block", 2f}, {"honey_block", 2f},
            {"dispenser", 1.5f}, {"dropper", 1.5f}, {"piston", 1.5f}, {"observer", 1.5f},
            {"repeater", 1.5f}, {"comparator", 1.5f}, {"redstone_lamp", 1.5f}, {"anvil", 1.5f},
            {"crafting_table", 1.5f}, {"carved_pumpkin", 1.5f}, {"jack_o_lantern", 1.5f},
            {"lantern", 1f}, {"concrete", 1f}, {"glazed_terracotta", 1f}, {"note_block", 1f},
            {"campfire", 0.8f}, {"sign", 0.8f},
            {"wool", 0.5f}, {"bookshelf", 0.5f}, {"quartz_block", 0.5f}, {"scaffolding", 0.5f},
            {"obsidian", 0.4f}, {"glass", 0.4f}, {"farmland", 0.4f}, {"door", 0.4f},
            {"torch", 0.3f}, {"planks", 0.3f}, {"fence", 0.3f},
    };

    static float weight(Block b) {
        return weightCache.computeIfAbsent(b, k -> {
            String p = Registries.BLOCK.getId(k).getPath();
            for (Object[] r : RULES) if (p.contains((String) r[0])) return (Float) r[1];
            return 0f;
        });
    }

    static boolean isAmethyst(Block b) {
        return b == Blocks.AMETHYST_BLOCK || b == Blocks.BUDDING_AMETHYST || b == Blocks.AMETHYST_CLUSTER;
    }

    static int blockColor(Block b) {
        return colorCache.computeIfAbsent(b, k -> {
            String p = Registries.BLOCK.getId(k).getPath();
            if (p.equals("ender_chest")) return 0xFFAA55FF;
            if (p.contains("shulker_box")) return 0xFFFF66FF;
            if (p.contains("chest")) return 0xFFFFAA33;
            if (p.equals("barrel")) return 0xFFC08A40;
            if (p.equals("spawner")) return 0xFF33DDFF;
            if (p.equals("beacon")) return 0xFFFFFFFF;
            int h = p.hashCode();
            return 0xFF000000 | (0x60 + (h & 0x9F)) << 16 | (0x60 + ((h >> 8) & 0x9F)) << 8 | (0x60 + ((h >> 16) & 0x9F));
        });
    }

    static String dimKey(MinecraftClient mc) { return mc.world.getRegistryKey().getValue().toString(); }

    static Map<Long, ChunkInfo> chunks(MinecraftClient mc) {
        return data.computeIfAbsent(dimKey(mc), k -> new HashMap<>());
    }

    static void chat(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal(PREFIX + s), false);
    }

    // ------------------------------------------------------------------ init
    @Override
    public void onInitializeClient() {
        buildGroups();
        buildModules();
        loadConfig();
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> queue.add(chunk.getPos().toLong()));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            data.clear(); queue.clear(); geodes = new ArrayList<>(); nearby.clear();
            inRange.clear(); inRangeNames.clear(); vanishWarned.clear(); spectatorWarned.clear(); vanishNow.clear();
            lastTotems = 0;
            holes = new ArrayList<>();
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
                Identifier.of("lordzinka", "overlay"), this::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
    }

    // ------------------------------------------------------------------ tick
    void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        tickCounter++;
        handleGuiKey(mc);

        scanQueue(mc);
        if (radarOn) updateRadar(mc); else { nearby.clear(); inRange.clear(); inRangeNames.clear(); }
        if (vanishOn && tickCounter % 20 == 0) updateVanish(mc);
        if (amethystOn && tickCounter % 40 == 0) computeGeodes(mc);
        if (holesOn) { if (tickCounter % 10 == 0) scanHoles(mc); } else if (!holes.isEmpty()) holes = new ArrayList<>();
        if (totemOn) {
            autoTotem(mc);
            if (totemWarn && tickCounter % 10 == 0) {
                int n = countTotems(mc);
                if (n == 0 && lastTotems > 0) chat(mc, "Out of totems!");
                lastTotems = n;
            }
        } else {
            lastTotems = 0;
        }
    }

    // ---- chunk scanning
    void scanQueue(MinecraftClient mc) {
        if (chunkRescan > 0 && tickCounter % chunkRescan == 0) {
            ChunkPos pc = mc.player.getChunkPos();
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++)
                    queue.add(ChunkPos.toLong(pc.x + dx, pc.z + dz));
        }
        Iterator<Long> it = queue.iterator();
        int budget = chunkSpeed;
        while (it.hasNext() && budget-- > 0) {
            long k = it.next();
            it.remove();
            WorldChunk c = mc.world.getChunkManager().getWorldChunk(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k));
            if (c != null) scan(mc, mc.world, c);
        }
    }

    static void rescanAll(MinecraftClient mc) {
        if (mc.world == null) return;
        queue.addAll(chunks(mc).keySet());
        ChunkPos pc = mc.player.getChunkPos();
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -8; dz <= 8; dz++)
                queue.add(ChunkPos.toLong(pc.x + dx, pc.z + dz));
    }

    void scan(MinecraftClient mc, ClientWorld world, WorldChunk chunk) {
        ChunkPos cp = chunk.getPos();
        Map<Long, ChunkInfo> map = chunks(mc);
        ChunkInfo old = map.get(cp.toLong());
        ChunkInfo info = new ChunkInfo();
        info.cx = cp.x;
        info.cz = cp.z;
        info.notified = old != null && old.notified;
        info.amNotified = old != null && old.amNotified;

        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = world.getBottomY();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection s = sections[i];
            if (s == null || s.isEmpty()) continue;
            if (!s.hasAny(st -> weight(st.getBlock()) > 0 || espBlocks.contains(st.getBlock()) || isAmethyst(st.getBlock()))) continue;
            int baseY = bottom + i * 16;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        Block b = s.getBlockState(x, y, z).getBlock();
                        int wy = baseY + y;
                        if (isAmethyst(b)) {
                            long p = BlockPos.asLong(cp.getStartX() + x, wy, cp.getStartZ() + z);
                            if (b == Blocks.AMETHYST_CLUSTER) {
                                if (info.amClusters.size() < 3000) info.amClusters.add(p);
                                if (wy < info.cMinY) info.cMinY = wy;
                                if (wy > info.cMaxY) info.cMaxY = wy;
                            }
                            else if (b == Blocks.BUDDING_AMETHYST) { if (info.amBudding.size() < 3000) info.amBudding.add(p); }
                            else if (info.amBlocks.size() < 6000) info.amBlocks.add(p);
                            continue;
                        }
                        float w = weight(b);
                        if (w > 0) {
                            info.score += w;
                            if (wy < info.minY) info.minY = wy;
                            if (wy > info.maxY) info.maxY = wy;
                        }
                        if (info.esp.size() < 2000 && espBlocks.contains(b))
                            info.esp.add(BlockPos.asLong(cp.getStartX() + x, wy, cp.getStartZ() + z));
                    }
        }

        boolean hasAm = !(info.amBlocks.isEmpty() && info.amBudding.isEmpty() && info.amClusters.isEmpty());
        if (info.score <= 0 && info.esp.isEmpty() && !hasAm) {
            map.remove(cp.toLong());
            return;
        }
        if (info.minY == Integer.MAX_VALUE) info.minY = info.maxY = 64;
        if (info.score >= threshold && !info.notified && notify) {
            info.notified = true;
            chat(mc, String.format("Suspicious chunk (score %.0f) near %d, %d", info.score, cp.getStartX() + 8, cp.getStartZ() + 8));
            toast("Player base?", String.format("score %.0f  |  %d, %d", info.score, cp.getStartX() + 8, cp.getStartZ() + 8), 0xFFFF7755);
        }
        if (info.score < threshold) info.notified = false;
        map.put(cp.toLong(), info);

        boolean amAct = isAmActive(map, info);
        if (amAct && !info.amNotified && notify) {
            info.amNotified = true;
            int cl = clustersNear(map, info);
            chat(mc, String.format("Amethyst activity near %d, %d (%d grown clusters) - a player was probably here",
                    cp.getStartX() + 8, cp.getStartZ() + 8, cl));
            toast("Amethyst activity", String.format("%d clusters  |  %d, %d", cl, cp.getStartX() + 8, cp.getStartZ() + 8), AM_COLOR);
        }
        if (!amAct) info.amNotified = false;
    }

    // ---- player radar
    void updateRadar(MinecraftClient mc) {
        nearby.clear();
        Set<UUID> now = new HashSet<>();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            float d = mc.player.distanceTo(p);
            if (d > alertRadius) continue;
            nearby.add(new Nearby(p.getUuid(), p.getName().getString(), d, p.getHealth() + p.getAbsorptionAmount(), p.getBlockPos()));
            now.add(p.getUuid());
            inRangeNames.put(p.getUuid(), p.getName().getString());
            if (inRange.add(p.getUuid())) {
                if (radarChat)
                    chat(mc, String.format("%s entered %dm range (%d away) at %d, %d, %d", p.getName().getString(),
                            alertRadius, (int) d, p.getBlockX(), p.getBlockY(), p.getBlockZ()));
                if (radarSound) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.5f);
                toast("Player detected", p.getName().getString() + "  |  " + (int) d + "m", 0xFFFF5555);
            }
        }
        for (Iterator<UUID> it = inRange.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (!now.contains(id)) {
                it.remove();
                String nm = inRangeNames.remove(id);
                if (radarChat) chat(mc, (nm != null ? nm : "A player") + " left your " + alertRadius + "m range");
            }
        }
        nearby.sort(Comparator.comparingDouble(Nearby::dist));
    }

    // ---- vanish detection
    // Heuristic 1: a player entity exists in the world but has no tab-list entry (classic vanish leak,
    //              also triggers for some NPC plugins).
    // Heuristic 2: a tab-list entry is in SPECTATOR mode (often staff).
    void updateVanish(MinecraftClient mc) {
        ClientPlayNetworkHandler nh = mc.getNetworkHandler();
        if (nh == null) return;
        vanishNow.clear();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (vanTab && nh.getPlayerListEntry(p.getUuid()) == null) {
                vanishNow.put(p.getUuid(), p.getName().getString());
                if (vanishWarned.add(p.getUuid()) && vanChat)
                    chat(mc, "Possible VANISHED player (no tab entry): " + p.getName().getString()
                            + " at " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ());
            }
        }
        if (!vanSpec) return;
        for (PlayerListEntry e : nh.getPlayerList()) {
            UUID id = e.getProfile().id();
            if (mc.player.getUuid().equals(id)) continue;
            if (e.getGameMode() == GameMode.SPECTATOR) {
                vanishNow.put(id, e.getProfile().name() + " (spectator)");
                if (spectatorWarned.add(id) && vanChat)
                    chat(mc, "Player in SPECTATOR mode (possible staff): " + e.getProfile().name());
            }
        }
    }

    // ---- auto totem
    void autoTotem(MinecraftClient mc) {
        if (totemCooldown > 0) { totemCooldown--; return; }
        if (mc.interactionManager == null) return;
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;
        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
        if (totemHealth > 0 && mc.player.getHealth() + mc.player.getAbsorptionAmount() > totemHealth) return;
        // screen-handler slots: 9-35 main inventory, 36-44 hotbar
        for (int i = 9; i <= (totemHotbar ? 44 : 35); i++) {
            ItemStack s = mc.player.playerScreenHandler.getSlot(i).getStack();
            if (s.isOf(Items.TOTEM_OF_UNDYING)) {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, i, 40, SlotActionType.SWAP, mc.player);
                totemCooldown = totemDelay;
                return;
            }
        }
    }

    static int countTotems(MinecraftClient mc) {
        int n = 0;
        for (int i = 9; i <= 45; i++) {
            ItemStack s = mc.player.playerScreenHandler.getSlot(i).getStack();
            if (s.isOf(Items.TOTEM_OF_UNDYING)) n += s.getCount();
        }
        return n;
    }

    // ---- amethyst / geode analysis
    static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    void computeGeodes(MinecraftClient mc) {
        Map<Long, ChunkInfo> map = chunks(mc);
        ChunkPos pc = mc.player.getChunkPos();
        List<Long> pos = new ArrayList<>();
        List<Byte> type = new ArrayList<>();
        for (ChunkInfo ci : map.values()) {
            if (Math.abs(ci.cx - pc.x) > 12 || Math.abs(ci.cz - pc.z) > 12) continue;
            for (long l : ci.amBlocks) { pos.add(l); type.add((byte) 0); }
            for (long l : ci.amBudding) { pos.add(l); type.add((byte) 1); }
            for (long l : ci.amClusters) { pos.add(l); type.add((byte) 2); }
        }
        int n = pos.size();
        Map<Long, Integer> idx = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) idx.put(pos.get(i), i);
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            long l = pos.get(i);
            int x = BlockPos.unpackLongX(l), y = BlockPos.unpackLongY(l), z = BlockPos.unpackLongZ(l);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Integer j = idx.get(BlockPos.asLong(x + dx, y + dy, z + dz));
                        if (j != null) {
                            int a = find(parent, i), b = find(parent, j);
                            if (a != b) parent[a] = b;
                        }
                    }
        }
        Map<Integer, Geode> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Geode g = groups.computeIfAbsent(find(parent, i), k -> new Geode());
            long l = pos.get(i);
            int x = BlockPos.unpackLongX(l), y = BlockPos.unpackLongY(l), z = BlockPos.unpackLongZ(l);
            g.minX = Math.min(g.minX, x); g.maxX = Math.max(g.maxX, x);
            g.minY = Math.min(g.minY, y); g.maxY = Math.max(g.maxY, y);
            g.minZ = Math.min(g.minZ, z); g.maxZ = Math.max(g.maxZ, z);
            byte t = type.get(i);
            if (t == 2) g.clusters++; else if (t == 1) g.budding++; else g.blocks++;
        }
        List<Geode> out = new ArrayList<>();
        for (Geode g : groups.values()) {
            // budding amethyst can't be obtained by players, so a group containing it is a real geode
            if (g.budding < 1 || g.blocks + g.budding < 6) continue;
            g.likelihood = estimate(g, map);
            out.add(g);
        }
        out.sort(Comparator.comparingDouble(g -> Math.pow(g.cx() - mc.player.getX(), 2) + Math.pow(g.cz() - mc.player.getZ(), 2)));
        geodes = out;
    }

    /**
     * Heuristic 0-99 "a player has been here" estimate. Tunable constants:
     *  - harvest signal (max 45): few fully grown clusters per budding block => the geode was probably mined
     *  - base signal   (max 35): player-made block score of chunks within ~40 blocks of the geode
     *  - storage signal(max 20): chests/barrels/shulkers etc. within ~40 blocks
     */
    static double estimate(Geode g, Map<Long, ChunkInfo> map) {
        double density = (double) g.clusters / Math.max(1, g.budding);
        double harvest = Math.max(0, 1 - density / 0.6) * 45;
        double near = 0;
        int storage = 0;
        for (ChunkInfo ci : map.values()) {
            double dx = ci.cx * 16 + 8 - g.cx(), dz = ci.cz * 16 + 8 - g.cz();
            if (dx * dx + dz * dz <= 40 * 40) { near += ci.score; storage += ci.esp.size(); }
        }
        double base = Math.min(35, near / Math.max(1, threshold) * 15);
        double stor = Math.min(20, storage * 5.0);
        return Math.min(99, 8 + harvest + base + stor);
    }

    // ------------------------------------------------------------------ projection
    static double ex, ey, ez, rx, rz, ux, uy, uz, fx, fy, fz, focal, sw, sh;
    static final double NEAR = 0.05;

    static void setupCamera(MinecraftClient mc, float tp) {
        // Use the real render camera so the overlay stays correct in Free Cam, third person, etc.
        Camera camera = mc.gameRenderer.getCamera();
        double yaw, pitch;
        if (camera != null && camera.isReady()) {
            Vec3d cp0 = camera.getCameraPos();
            ex = cp0.x;
            ey = cp0.y;
            ez = cp0.z;
            yaw = Math.toRadians(camera.getYaw());
            pitch = Math.toRadians(camera.getPitch());
        } else {
            ClientPlayerEntity p = mc.player;
            Vec3d lp = p.getLerpedPos(tp);
            ex = lp.x;
            ey = lp.y + (p.getEyeY() - p.getY());
            ez = lp.z;
            yaw = Math.toRadians(p.getYaw(tp));
            pitch = Math.toRadians(p.getPitch(tp));
        }
        double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
        fx = -sy * cp; fy = -sp; fz = cy * cp;
        rx = -cy; rz = -sy;
        ux = -sy * sp; uy = cp; uz = cy * sp;
        focal = 1.0 / Math.tan(Math.toRadians(mc.options.getFov().getValue()) / 2.0);
        sw = mc.getWindow().getScaledWidth();
        sh = mc.getWindow().getScaledHeight();
    }

    static double[] cam(double x, double y, double z) {
        double dx = x - ex, dy = y - ey, dz = z - ez;
        return new double[]{dx * rx + dz * rz, dx * ux + dy * uy + dz * uz, dx * fx + dy * fy + dz * fz};
    }

    static double[] scr(double[] c) {
        return new double[]{sw / 2 * (1 + c[0] / c[2] * focal / (sw / sh)), sh / 2 * (1 - c[1] / c[2] * focal)};
    }

    static double[] clipNear(double[] out, double[] in) {
        double t = (NEAR - out[2]) / (in[2] - out[2]);
        return new double[]{out[0] + (in[0] - out[0]) * t, out[1] + (in[1] - out[1]) * t, NEAR};
    }

    static void line3(DrawContext g, double[] a, double[] b, int col) {
        if (a[2] < NEAR && b[2] < NEAR) return;
        if (a[2] < NEAR) a = clipNear(a, b);
        else if (b[2] < NEAR) b = clipNear(b, a);
        double[] p = scr(a), q = scr(b);
        line2(g, p[0], p[1], q[0], q[1], col);
    }

    static void line2(DrawContext g, double x0, double y0, double x1, double y1, int col) {
        double dx = x1 - x0, dy = y1 - y0, t0 = 0, t1 = 1;
        double[] p = {-dx, dx, -dy, dy}, q = {x0, sw - x0, y0, sh - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return;
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) { if (t > t1) return; if (t > t0) t0 = t; }
                else { if (t < t0) return; if (t < t1) t1 = t; }
            }
        }
        double ax = x0 + t0 * dx, ay = y0 + t0 * dy, bx = x0 + t1 * dx, by = y0 + t1 * dy;
        if (Math.abs(bx - ax) >= Math.abs(by - ay)) runs(g, ax, ay, bx, by, col, false);
        else runs(g, ay, ax, by, bx, col, true);
    }

    static void runs(DrawContext g, double a0, double b0, double a1, double b1, int col, boolean swap) {
        if (a0 > a1) { double t = a0; a0 = a1; a1 = t; t = b0; b0 = b1; b1 = t; }
        double slope = a1 == a0 ? 0 : (b1 - b0) / (a1 - a0);
        int as = (int) a0, ae = (int) a1;
        int start = as, runB = (int) Math.round(b0 + (as - a0) * slope);
        for (int a = as + 1; a <= ae + 1; a++) {
            int bb = a <= ae ? (int) Math.round(b0 + (a - a0) * slope) : Integer.MIN_VALUE;
            if (bb != runB) {
                if (swap) g.fill(runB, start, runB + 1, a, col);
                else g.fill(start, runB, a, runB + 1, col);
                start = a;
                runB = bb;
            }
        }
    }

    static double[][] corners(double x0, double y0, double z0, double x1, double y1, double z1) {
        double[][] c = new double[8][];
        for (int i = 0; i < 8; i++)
            c[i] = cam((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1);
        return c;
    }

    static void box3(DrawContext g, double x0, double y0, double z0, double x1, double y1, double z1, int col) {
        double[][] c = corners(x0, y0, z0, x1, y1, z1);
        for (int i = 0; i < 8; i++)
            for (int bit : new int[]{1, 2, 4})
                if ((i & bit) == 0) line3(g, c[i], c[i | bit], col);
    }

    static double[] box2(DrawContext g, Box b, int col) { return box2(g, b, col, 0); }

    static double[] box2(DrawContext g, Box b, int col, int fill) {
        double[][] c = corners(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
        for (double[] v : c) {
            if (v[2] < NEAR) return null;
            double[] s = scr(v);
            minX = Math.min(minX, s[0]); maxX = Math.max(maxX, s[0]);
            minY = Math.min(minY, s[1]); maxY = Math.max(maxY, s[1]);
        }
        if (maxX < 0 || maxY < 0 || minX > sw || minY > sh) return null;
        int x0 = (int) Math.max(-50, minX), y0 = (int) Math.max(-50, minY);
        int x1 = (int) Math.min(sw + 50, maxX), y1 = (int) Math.min(sh + 50, maxY);
        if ((fill >>> 24) != 0) g.fill(x0, y0, x1 + 1, y1 + 1, fill);
        g.fill(x0, y0, x1 + 1, y0 + 1, col);
        g.fill(x0, y1, x1 + 1, y1 + 1, col);
        g.fill(x0, y0, x0 + 1, y1 + 1, col);
        g.fill(x1, y0, x1 + 1, y1 + 1, col);
        return new double[]{minX, minY, maxX, maxY};
    }

    static void tracerTo(DrawContext g, double x, double y, double z, int col) {
        double[] c = cam(x, y, z);
        if (c[2] < NEAR) return;
        double[] s = scr(c);
        line2(g, sw / 2, trOrigin == 1 ? sh / 2 : sh, s[0], s[1], col);
    }

    static int hsv(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        int i = (int) (h * 6);
        float f = h * 6 - i, p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    static int riskColor(double pct) { // green -> yellow -> red
        float t = (float) Math.min(1, pct / 100.0);
        return 0xFF000000 | ((int) (80 + 175 * t) << 16) | ((int) (230 - 190 * t) << 8) | 0x40;
    }

    // ------------------------------------------------------------------ drawing helpers
    static int withAlpha(int rgb, int pct) {
        return (Math.max(0, Math.min(255, pct * 255 / 100)) << 24) | (rgb & 0xFFFFFF);
    }

    static int mulAlpha(int argb, double k) {
        int a = (int) Math.max(0, Math.min(255, (argb >>> 24) * k));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int gr = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (gr << 8) | bl;
    }

    static int hpColor(double ratio) { return ratio > 0.66 ? 0xFF55FF55 : ratio > 0.33 ? 0xFFFFDD44 : 0xFFFF5555; }

    /** Fills a convex polygon given in camera space (clipped against the near plane). */
    static void fillPoly(DrawContext g, double[][] pts, int argb) {
        List<double[]> clipped = new ArrayList<>();
        int n0 = pts.length;
        for (int i = 0; i < n0; i++) {
            double[] a = pts[i], b = pts[(i + 1) % n0];
            boolean ai = a[2] >= NEAR, bi = b[2] >= NEAR;
            if (ai) clipped.add(a);
            if (ai != bi) clipped.add(ai ? clipNear(b, a) : clipNear(a, b));
        }
        int n = clipped.size();
        if (n < 3) return;
        double[][] sp = new double[n][];
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            sp[i] = scr(clipped.get(i));
            minY = Math.min(minY, sp[i][1]);
            maxY = Math.max(maxY, sp[i][1]);
        }
        int y0 = (int) Math.max(0, Math.floor(minY));
        int y1 = (int) Math.min(sh - 1, Math.ceil(maxY));
        for (int y = y0; y <= y1; y++) {
            double yc = y + 0.5, xl = Double.MAX_VALUE, xr = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double[] p = sp[i], q = sp[(i + 1) % n];
                if ((p[1] <= yc && q[1] > yc) || (q[1] <= yc && p[1] > yc)) {
                    double x = p[0] + (yc - p[1]) / (q[1] - p[1]) * (q[0] - p[0]);
                    xl = Math.min(xl, x);
                    xr = Math.max(xr, x);
                }
            }
            if (xl > xr) continue;
            int a = (int) Math.max(0, Math.round(xl)), b = (int) Math.min(sw, Math.round(xr));
            if (b > a) g.fill(a, y, b, y + 1, argb);
        }
    }

    static final int[][] FACES = {{0, 2, 6, 4}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 5, 7, 6}};
    static final double[] FACE_K = {1.0, 0.85, 0.5, 0.75, 0.9, 0.7};

    /** Translucent 3D box (each face shaded a bit differently so it reads as a solid). */
    static void fillBox3(DrawContext g, double x0, double y0, double z0, double x1, double y1, double z1, int argb) {
        double[][] c = corners(x0, y0, z0, x1, y1, z1);
        for (int f = 0; f < 6; f++) {
            int[] ix = FACES[f];
            fillPoly(g, new double[][]{c[ix[0]], c[ix[1]], c[ix[2]], c[ix[3]]}, mulAlpha(argb, FACE_K[f]));
        }
    }

    static double[] screenRect(Box b) {
        double[][] c = corners(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
        for (double[] v : c) {
            if (v[2] < NEAR) return null;
            double[] s = scr(v);
            minX = Math.min(minX, s[0]); maxX = Math.max(maxX, s[0]);
            minY = Math.min(minY, s[1]); maxY = Math.max(maxY, s[1]);
        }
        if (maxX < 0 || maxY < 0 || minX > sw || minY > sh) return null;
        return new double[]{minX, minY, maxX, maxY};
    }

    static void rectOutline(DrawContext g, int x0, int y0, int x1, int y1, int col) {
        g.fill(x0, y0, x1 + 1, y0 + 1, col);
        g.fill(x0, y1, x1 + 1, y1 + 1, col);
        g.fill(x0, y0, x0 + 1, y1 + 1, col);
        g.fill(x1, y0, x1 + 1, y1 + 1, col);
    }

    static void rectCorners(DrawContext g, int x0, int y0, int x1, int y1, int col) {
        int lw = Math.max(3, (x1 - x0) / 4), lh = Math.max(3, (y1 - y0) / 4);
        g.fill(x0, y0, x0 + lw, y0 + 1, col);
        g.fill(x0, y0, x0 + 1, y0 + lh, col);
        g.fill(x1 - lw + 1, y0, x1 + 1, y0 + 1, col);
        g.fill(x1, y0, x1 + 1, y0 + lh, col);
        g.fill(x0, y1, x0 + lw, y1 + 1, col);
        g.fill(x0, y1 - lh + 1, x0 + 1, y1 + 1, col);
        g.fill(x1 - lw + 1, y1, x1 + 1, y1 + 1, col);
        g.fill(x1, y1 - lh + 1, x1 + 1, y1 + 1, col);
    }

    // ------------------------------------------------------------------ hole detection
    static final class Hole {
        final int x, z, y0, y1;   // y0 = bottom, y1 = top (inclusive)
        final double dist;
        Hole(int x, int z, int y0, int y1, double dist) { this.x = x; this.z = z; this.y0 = y0; this.y1 = y1; this.dist = dist; }
    }

    static List<Hole> holes = new ArrayList<>();

    static boolean solidAt(ClientWorld w, BlockPos.Mutable m, int x, int y, int z) {
        m.set(x, y, z);
        return w.getBlockState(m).isSolidBlock(w, m);
    }

    static boolean holeCell(ClientWorld w, BlockPos.Mutable m, int x, int y, int z) {
        m.set(x, y, z);
        BlockState s = w.getBlockState(m);
        if (!s.isAir()) return false;
        return solidAt(w, m, x + 1, y, z) && solidAt(w, m, x - 1, y, z)
                && solidAt(w, m, x, y, z + 1) && solidAt(w, m, x, y, z - 1);
    }

    /** A hole is a 1x1 vertical shaft of air with solid blocks on all four sides. */
    void scanHoles(MinecraftClient mc) {
        ClientWorld w = mc.world;
        BlockPos pp = mc.player.getBlockPos();
        int r = holeRange;
        int yMin = Math.max(w.getBottomY() + 1, pp.getY() - holeVRange);
        int yMax = Math.min(w.getBottomY() + w.getHeight() - 2, pp.getY() + holeVRange);
        BlockPos.Mutable m = new BlockPos.Mutable();
        List<Hole> out = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = pp.getX() + dx, z = pp.getZ() + dz;
                if (w.getChunkManager().getWorldChunk(x >> 4, z >> 4) == null) continue;
                int runStart = Integer.MIN_VALUE;
                for (int y = yMin; y <= yMax + 1; y++) {
                    boolean hole = y <= yMax && holeCell(w, m, x, y, z);
                    if (hole) {
                        if (runStart == Integer.MIN_VALUE) runStart = y;
                    } else if (runStart != Integer.MIN_VALUE) {
                        int len = y - runStart;
                        if (len >= holeMin && len <= holeMaxLen && (!holeFloor || solidAt(w, m, x, runStart - 1, z))) {
                            double ddx = x + 0.5 - mc.player.getX(), ddz = z + 0.5 - mc.player.getZ();
                            out.add(new Hole(x, z, runStart, y - 1, ddx * ddx + ddz * ddz));
                        }
                        runStart = Integer.MIN_VALUE;
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(h -> h.dist));
        holes = out.size() > holeCap ? new ArrayList<>(out.subList(0, holeCap)) : out;
    }

    void renderHoles(DrawContext g, MinecraftClient mc) {
        var tr = mc.textRenderer;
        for (Hole h : holes) {
            int len = h.y1 - h.y0 + 1;
            int col = holeMode == 1
                    ? lerpColor(hueColor(holeHue), hueColor(holeHue2), Math.min(1f, (len - holeMin) / 12f))
                    : hueColor(holeHue);
            double x0 = h.x, z0 = h.z;
            double[] cc = cam(x0 + 0.5, (h.y0 + h.y1 + 1) / 2.0, z0 + 0.5);
            if (cc[2] < -len) continue;
            if (holeFill) fillBox3(g, x0, h.y0, z0, x0 + 1, h.y1 + 1, z0 + 1, withAlpha(col, holeAlpha));
            if (holeOutline) box3(g, x0, h.y0, z0, x0 + 1, h.y1 + 1, z0 + 1, col);
            if (tracers && trHoles) tracerTo(g, x0 + 0.5, (h.y0 + h.y1 + 1) / 2.0, z0 + 0.5, col);
            if (holeLabels) {
                double[] c = cam(x0 + 0.5, h.y1 + 1.6, z0 + 0.5);
                if (c[2] > NEAR) {
                    double[] s = scr(c);
                    String t = len + "m hole";
                    int tw = tr.getWidth(t);
                    g.fill((int) s[0] - tw / 2 - 2, (int) s[1] - 1, (int) s[0] + tw / 2 + 2, (int) s[1] + 9, 0x80000000);
                    g.drawTextWithShadow(tr, t, (int) s[0] - tw / 2, (int) s[1], col);
                }
            }
        }
    }

    // ------------------------------------------------------------------ rendering
    void render(DrawContext g, RenderTickCounter rtc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || mc.options.hudHidden) return;
        if (mc.currentScreen != null && !(mc.currentScreen instanceof ChatScreen)) return;
        float tp = rtc.getTickProgress(false);
        setupCamera(mc, tp);
        var tr = mc.textRenderer;
        Map<Long, ChunkInfo> map = chunks(mc);

        if (chunksOn) renderChunks(g, mc, map);
        if (holesOn) renderHoles(g, mc);
        if (blocksOn) renderBlocks(g, mc, map);
        if (amethystOn) renderGeodes(g, tr);
        if (espOn) renderEntities(g, mc, tp);
        if (nametagsOn) renderNametags(g, mc, tp);
        if (hudOn) renderHud(g, mc);
        if (radarOn && radarWidget) renderRadarWidget(g, mc);
        renderToasts(g, mc);
    }

    // ---- amethyst-based activity detection
    static final int AM_COLOR = 0xFFB968FF;

    static int clustersNear(Map<Long, ChunkInfo> map, ChunkInfo ci) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                ChunkInfo o = map.get(ChunkPos.toLong(ci.cx + dx, ci.cz + dz));
                if (o != null) n += o.amClusters.size();
            }
        return n;
    }

    /** Many fully grown amethyst clusters around a chunk = somebody has been (farming) here for a while. */
    static boolean isAmActive(Map<Long, ChunkInfo> map, ChunkInfo ci) {
        return amClusterMin > 0 && !ci.amClusters.isEmpty() && clustersNear(map, ci) >= amClusterMin;
    }

    static boolean isFlagged(Map<Long, ChunkInfo> map, ChunkInfo ci) {
        return ci.score >= threshold || isAmActive(map, ci);
    }

    static void drawCard(DrawContext g, net.minecraft.client.font.TextRenderer tr, int cx, int y,
                         String title, String l2, String l3, int col, float bar) {
        int w = Math.max(tr.getWidth(title), Math.max(tr.getWidth(l2), tr.getWidth(l3))) + 14;
        int x = cx - w / 2;
        int h = 34;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x60000000);
        g.fillGradient(x, y, x + w, y + h, 0xE0242438, 0xE00C0C16);
        g.fill(x, y, x + 3, y + h, col);
        g.fill(x + 3, y + h - 2, x + w, y + h, 0x55000000);
        g.fill(x + 3, y + h - 2, x + 3 + (int) ((w - 3) * Math.max(0f, Math.min(1f, bar))), y + h, col);
        g.drawTextWithShadow(tr, title, x + 8, y + 3, col);
        g.drawTextWithShadow(tr, l2, x + 8, y + 13, 0xFFDDDDEE);
        g.drawTextWithShadow(tr, l3, x + 8, y + 22, 0xFF9999AA);
    }

    static void vline(DrawContext g, double x, double y0, double y1, double z, int col) {
        line3(g, cam(x, y0, z), cam(x, y1, z), col);
    }

    void renderChunks(DrawContext g, MinecraftClient mc, Map<Long, ChunkInfo> map) {
        var tr = mc.textRenderer;
        double rangeSq = (double) chunkRange * chunkRange;
        List<ChunkInfo> flagged = new ArrayList<>();
        for (ChunkInfo ci : map.values()) {
            double dx = ci.cx * 16 + 8 - ex, dz = ci.cz * 16 + 8 - ez;
            if (dx * dx + dz * dz < rangeSq && isFlagged(map, ci)) flagged.add(ci);
        }
        flagged.sort(Comparator.comparingDouble(ci -> Math.pow(ci.cx * 16 + 8 - ex, 2) + Math.pow(ci.cz * 16 + 8 - ez, 2)));
        int n = Math.min(chunkMax, flagged.size());
        double pulse = chunkPulse ? 0.8 + 0.2 * Math.sin(System.currentTimeMillis() / 300.0) : 1.0;
        for (int i = 0; i < n; i++) {
            ChunkInfo ci = flagged.get(i);
            boolean blocksAct = ci.score >= threshold;
            boolean amAct = isAmActive(map, ci);
            float ratio = Math.min(1f, ci.score / (threshold * 3f));
            int col = chunkColorMode == 1 ? hueColor(chunkHue)
                    : blocksAct ? (0xFF000000 | 0xFF0000 | ((int) (220 - 180 * ratio) << 8)) : AM_COLOR;
            double x0 = ci.cx * 16, z0 = ci.cz * 16;

            int dMin = Integer.MAX_VALUE, dMax = Integer.MIN_VALUE;
            if (blocksAct) { dMin = ci.minY; dMax = ci.maxY; }
            if (amAct && ci.cMinY != Integer.MAX_VALUE) { dMin = Math.min(dMin, ci.cMinY); dMax = Math.max(dMax, ci.cMaxY); }
            if (dMin == Integer.MAX_VALUE) { dMin = 64; dMax = 64; }

            boolean fixed = chunkYMode == 1;
            double yLow, yHigh;
            if (chunkStyle == 0) {                       // flat square
                yLow = yHigh = fixed ? chunkY : dMax + 1;
            } else if (chunkStyle == 1) {                // column from the base upwards
                yLow = fixed ? chunkY : dMin;
                yHigh = yLow + chunkHeight;
            } else {                                     // box around the detected blocks
                yLow = fixed ? chunkY : dMin;
                yHigh = fixed ? chunkY + chunkHeight : dMax + 1;
            }

            boolean doFill = chunkFill && i < 14;        // filling is the expensive part, so only the nearest chunks
            int fillCol = withAlpha(col, (int) (chunkAlpha * pulse));
            if (chunkStyle == 0) {
                if (doFill)
                    fillPoly(g, new double[][]{cam(x0, yLow, z0), cam(x0 + 16, yLow, z0),
                            cam(x0 + 16, yLow, z0 + 16), cam(x0, yLow, z0 + 16)}, fillCol);
            } else if (doFill) {
                fillBox3(g, x0, yLow, z0, x0 + 16, yHigh, z0 + 16, fillCol);
            }
            if (chunkOutline) {
                box3(g, x0, yLow, z0, x0 + 16, yHigh, z0 + 16, col);
                if (chunkStyle == 0) {                   // inner frame gives the square some depth
                    double in = 0.7;
                    box3(g, x0 + in, yLow, z0 + in, x0 + 16 - in, yLow, z0 + 16 - in, mulAlpha(col, 0.45));
                }
            }
            if (chunkBeams) {                            // fading light beams on the corners and in the middle
                double top = yLow + Math.max(4, chunkHeight);
                double[][] pts = {{x0, z0}, {x0 + 16, z0}, {x0, z0 + 16}, {x0 + 16, z0 + 16}, {x0 + 8, z0 + 8}};
                for (int k = 0; k < pts.length; k++) {
                    for (int seg = 0; seg < 4; seg++) {
                        double ya = yLow + (top - yLow) * seg / 4.0, yb = yLow + (top - yLow) * (seg + 1) / 4.0;
                        int sc = mulAlpha(col, (k == 4 ? 0.9 : 0.75) - seg * 0.2);
                        vline(g, pts[k][0], ya, yb, pts[k][1], sc);
                    }
                }
            }
            double yMid = (yLow + yHigh) / 2.0;
            if (tracers && trChunks) tracerTo(g, x0 + 8, yMid, z0 + 8, col);

            if (chunkLabels && i < 10) {
                double[] c = cam(x0 + 8, Math.max(yHigh, yLow + chunkHeight) + 1.5, z0 + 8);
                if (c[2] > NEAR) {
                    double[] s = scr(c);
                    double dx = x0 + 8 - ex, dz = z0 + 8 - ez;
                    int dist = (int) Math.sqrt(dx * dx + dz * dz);
                    int cl = clustersNear(map, ci);
                    String title = blocksAct && amAct ? "Player Base + Amethyst" : blocksAct ? "Player Base" : "Amethyst Activity";
                    String l2 = String.format("X %d  Z %d  |  %dm", (int) (x0 + 8), (int) (z0 + 8), dist);
                    String l3 = blocksAct
                            ? String.format("score %.0f / %d", ci.score, threshold) + (amAct ? "  +" + cl + " clusters" : "")
                            : cl + " clusters - player was here";
                    float bar = blocksAct ? ci.score / (threshold * 3f) : cl / (Math.max(1, amClusterMin) * 3f);
                    drawCard(g, tr, (int) s[0], (int) s[1] - 36, title, l2, l3, col, bar);
                }
            }
        }
    }

    void renderBlocks(DrawContext g, MinecraftClient mc, Map<Long, ChunkInfo> map) {
        var tr = mc.textRenderer;
        int drawn = 0;
        double rangeSq = (double) blockRange * blockRange;
        for (ChunkInfo ci : map.values()) {
            double cdx = ci.cx * 16 + 8 - ex, cdz = ci.cz * 16 + 8 - ez;
            if (cdx * cdx + cdz * cdz > (blockRange + 24.0) * (blockRange + 24.0)) continue;
            for (int i = 0; i < ci.esp.size(); i++) {
                if (drawn >= blockMax) return;
                BlockPos pos = BlockPos.fromLong(ci.esp.getLong(i));
                double dx = pos.getX() + 0.5 - ex, dy = pos.getY() + 0.5 - ey, dz = pos.getZ() + 0.5 - ez;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > rangeSq) continue;
                Block block = mc.world.getBlockState(pos).getBlock();
                boolean loaded = mc.world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4) != null;
                if (loaded && !espBlocks.contains(block)) continue;
                Block shown = loaded ? block : Blocks.CHEST;
                int col = blockColorMode == 1 ? hueColor(blockHue) : blockColor(shown);
                int fillC = blockStyle >= 1 ? withAlpha(col, blockFillAlpha) : 0;
                int lineC = blockStyle == 1 ? (col & 0xFFFFFF) : col;      // style "Fill" = no outline (alpha 0)
                double[] r = box2(g, new Box(pos), lineC, fillC);
                if (r == null) {
                    // The fast 2D path gives up when a corner is behind the camera. Blocks right next to the
                    // camera (e.g. in Free Cam) go through the clipped 3D path so they never vanish.
                    if (distSq > 64) continue;
                    if (fillC != 0) fillBox3(g, pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, fillC);
                    if (blockStyle != 1) box3(g, pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, col);
                    drawn++;
                    continue;
                }
                drawn++;
                if (blockLabels) {
                    String t = shown.getName().getString() + " " + (int) Math.sqrt(distSq) + "m";
                    g.drawTextWithShadow(tr, t, (int) ((r[0] + r[2]) / 2) - tr.getWidth(t) / 2, (int) r[1] - 10, col);
                }
            }
        }
    }

    void renderGeodes(DrawContext g, net.minecraft.client.font.TextRenderer tr) {
        int shown = 0;
        double rr = (double) amRange * amRange;
        for (Geode ge : geodes) {
            if (shown >= amMax) break;
            if (ge.likelihood < amMinPct) continue;
            double dx = ge.cx() - ex, dz = ge.cz() - ez;
            if (dx * dx + dz * dz > rr) continue;
            shown++;
            int col = riskColor(ge.likelihood);
            if (amFill) fillBox3(g, ge.minX, ge.minY, ge.minZ, ge.maxX + 1, ge.maxY + 1, ge.maxZ + 1, withAlpha(col, 16));
            box3(g, ge.minX, ge.minY, ge.minZ, ge.maxX + 1, ge.maxY + 1, ge.maxZ + 1, col);
            if (tracers && trGeodes) tracerTo(g, ge.cx(), ge.cy(), ge.cz(), col);
            double[] c = cam(ge.cx(), ge.maxY + 1.5, ge.cz());
            if (c[2] > NEAR) {
                double[] s = scr(c);
                String l1 = "Amethyst Geode";
                String l2 = String.format("clusters %d | budding %d | base %.0f%%", ge.clusters, ge.budding, ge.likelihood);
                int w = Math.max(tr.getWidth(l1), tr.getWidth(l2));
                g.fill((int) s[0] - w / 2 - 3, (int) s[1] - 12, (int) s[0] + w / 2 + 3, (int) s[1] + 10, 0x90101018);
                g.fill((int) s[0] - w / 2 - 3, (int) s[1] + 9, (int) s[0] + w / 2 + 3, (int) s[1] + 10, col);
                g.drawTextWithShadow(tr, l1, (int) s[0] - tr.getWidth(l1) / 2, (int) s[1] - 10, 0xFFD58CFF);
                g.drawTextWithShadow(tr, l2, (int) s[0] - tr.getWidth(l2) / 2, (int) s[1], col);
            }
        }
        if (amBudding) {
            int n = 0;
            double lim = Math.min(amRange, 96);
            double r2 = lim * lim;
            for (ChunkInfo ci : chunks(MinecraftClient.getInstance()).values()) {
                if (n >= 250) break;
                for (long l : ci.amBudding) {
                    BlockPos pos = BlockPos.fromLong(l);
                    double dx = pos.getX() + 0.5 - ex, dy = pos.getY() + 0.5 - ey, dz = pos.getZ() + 0.5 - ez;
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    if (box2(g, new Box(pos), 0xFFD58CFF, withAlpha(0xD58CFF, 28)) != null && ++n >= 250) break;
                }
            }
        }
    }

    void renderEntities(DrawContext g, MinecraftClient mc, float tp) {
        var tr = mc.textRenderer;
        double rangeSq = (double) entityRange * entityRange;
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player) continue;
            int col;
            if (e instanceof PlayerEntity) { if (!fPlayers) continue; col = hueColor(huePlayer); }
            else if (e instanceof HostileEntity) { if (!fHostile) continue; col = hueColor(hueHostile); }
            else if (e instanceof PassiveEntity) { if (!fPassive) continue; col = hueColor(huePassive); }
            else if (e instanceof ItemEntity) { if (!fItems) continue; col = hueColor(hueItem); }
            else {
                String id = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
                if (!fVehicles || !(id.contains("minecart") || id.contains("boat") || id.contains("raft"))) continue;
                col = hueColor(hueVehicle);
            }
            Vec3d lp = e.getLerpedPos(tp);
            double dx = lp.x - ex, dy = lp.y - ey, dz = lp.z - ez;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > rangeSq) continue;
            Box b = e.getBoundingBox().offset(lp.x - e.getX(), lp.y - e.getY(), lp.z - e.getZ());
            int fill = espFill ? withAlpha(col, espFillAlpha) : 0;
            double[] lab = null;
            if (espStyle == 2) {
                if (espFill) fillBox3(g, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, fill);
                box3(g, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, col);
                double[] c = cam(lp.x, lp.y + e.getHeight() + 0.25, lp.z);
                if (c[2] > NEAR) { double[] s = scr(c); lab = new double[]{s[0], s[1]}; }
            } else {
                double[] r = screenRect(b);
                if (r != null) {
                    int x0 = (int) Math.max(-50, r[0]), y0 = (int) Math.max(-50, r[1]);
                    int x1 = (int) Math.min(sw + 50, r[2]), y1 = (int) Math.min(sh + 50, r[3]);
                    if (fill != 0) g.fill(x0, y0, x1 + 1, y1 + 1, fill);
                    if (espStyle == 1) rectCorners(g, x0, y0, x1, y1, col); else rectOutline(g, x0, y0, x1, y1, col);
                    if (espHealthBar && e instanceof LivingEntity le) {
                        double ratio = Math.min(1.0, (le.getHealth() + le.getAbsorptionAmount()) / Math.max(1f, le.getMaxHealth()));
                        int bh = y1 - y0, fh = (int) (bh * ratio);
                        g.fill(x0 - 5, y0 - 1, x0 - 2, y1 + 2, 0xAA000000);
                        g.fill(x0 - 4, y1 + 1 - fh, x0 - 3, y1 + 1, hpColor(ratio));
                    }
                    lab = new double[]{(r[0] + r[2]) / 2, r[1]};
                }
            }
            if (tracers && trEntities) tracerTo(g, lp.x, lp.y + e.getHeight() / 2, lp.z, col);
            if (espLabels && lab != null && !(e instanceof PlayerEntity && nametagsOn)) {
                String name = e instanceof ItemEntity ie
                        ? ie.getStack().getName().getString() + " x" + ie.getStack().getCount()
                        : e.getName().getString();
                String label = espDistance ? name + " [" + (int) Math.sqrt(distSq) + "m]" : name;
                g.drawTextWithShadow(tr, label, (int) lab[0] - tr.getWidth(label) / 2, (int) lab[1] - 10, col);
            }
        }
    }

    void renderNametags(DrawContext g, MinecraftClient mc, float tp) {
        var tr = mc.textRenderer;
        ClientPlayNetworkHandler nh = mc.getNetworkHandler();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            Vec3d lp = p.getLerpedPos(tp);
            double dist = Math.sqrt(Math.pow(lp.x - ex, 2) + Math.pow(lp.y - ey, 2) + Math.pow(lp.z - ez, 2));
            if (dist > ntRange) continue;
            double[] c = cam(lp.x, lp.y + p.getHeight() + 0.75, lp.z);
            if (c[2] < NEAR) continue;
            double[] s = scr(c);
            float hp = p.getHealth() + p.getAbsorptionAmount();
            int hpCol = hp >= 15 ? 0xFF55FF55 : hp >= 8 ? 0xFFFFDD44 : 0xFFFF5555;
            String name = p.getName().getString();
            StringBuilder st = new StringBuilder();
            if (ntHealth) st.append("  ").append((int) Math.ceil(hp)).append("hp");
            if (ntDistance) st.append("  ").append((int) dist).append('m');
            if (ntPing && nh != null) {
                PlayerListEntry pe = nh.getPlayerListEntry(p.getUuid());
                if (pe != null) st.append("  ").append(pe.getLatency()).append("ms");
            }
            String stats = st.toString();
            int w = tr.getWidth(name) + tr.getWidth(stats);

            List<String> parts = new ArrayList<>();
            if (ntArmor) {
                StringBuilder armor = new StringBuilder();
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    ItemStack as = p.getEquippedStack(slot);
                    armor.append(as.isEmpty() ? "-" : as.getMaxDamage() <= 0 ? "#"
                            : String.valueOf(Math.max(0, 100 - as.getDamage() * 100 / as.getMaxDamage()) / 10)).append(' ');
                }
                parts.add("[" + armor.toString().trim() + "]");
            }
            if (ntItem) {
                ItemStack held = p.getMainHandStack();
                parts.add(held.isEmpty() ? "empty hand" : held.getName().getString());
            }
            String l2 = parts.isEmpty() ? null : String.join("  ", parts);
            int w2 = l2 == null ? 0 : tr.getWidth(l2);
            int bw = Math.max(w, w2);
            int bh = (l2 == null ? 12 : 22) + 3;
            int cx = (int) s[0], top = (int) s[1] - bh - 2;
            if (ntBackground) {
                g.fill(cx - bw / 2 - 5, top - 1, cx + bw / 2 + 5, top + bh + 1, 0x50000000);
                g.fillGradient(cx - bw / 2 - 4, top, cx + bw / 2 + 4, top + bh, 0xD0202034, 0xD00C0C14);
                int bar = (int) ((bw + 8) * Math.min(1f, hp / 20f));
                g.fill(cx - bw / 2 - 4, top + bh - 2, cx + bw / 2 + 4, top + bh, 0x66000000);
                g.fill(cx - bw / 2 - 4, top + bh - 2, cx - bw / 2 - 4 + bar, top + bh, hpCol);
            }
            g.drawTextWithShadow(tr, name, cx - w / 2, top + 3, 0xFFFFFFFF);
            if (!stats.isEmpty()) g.drawTextWithShadow(tr, stats, cx - w / 2 + tr.getWidth(name), top + 3, hpCol);
            if (l2 != null) g.drawTextWithShadow(tr, l2, cx - w2 / 2, top + 13, 0xFFBBBBCC);
        }
    }

    // ---- HUD
    void renderHud(DrawContext g, MinecraftClient mc) {
        var tr = mc.textRenderer;
        int accent = accent();
        int accentDim = accent & 0xFFFFFF;

        if (hudInfo) {
            List<String> lines = new ArrayList<>();
            List<Integer> cols = new ArrayList<>();
            BlockPos bp = mc.player.getBlockPos();
            String dk = dimKey(mc);
            if (hudCoords) {
                lines.add(String.format("XYZ  %d  %d  %d", bp.getX(), bp.getY(), bp.getZ())); cols.add(0xFFFFFFFF);
                if (hudNether && !dk.contains("end")) {
                    boolean nether = dk.contains("nether");
                    lines.add(String.format("%s  %d  %d", nether ? "Overworld" : "Nether",
                            nether ? bp.getX() * 8 : Math.floorDiv(bp.getX(), 8),
                            nether ? bp.getZ() * 8 : Math.floorDiv(bp.getZ(), 8)));
                    cols.add(nether ? 0xFF77DD77 : 0xFFFF8866);
                }
            }
            lines.add(String.format(hudFps ? "%s  |  %s  |  %d FPS" : "%s  |  %s", mc.player.getHorizontalFacing().asString(),
                    dk.replace("minecraft:", ""), mc.getCurrentFps())); cols.add(0xFFBBBBCC);
            int flagged = 0;
            Map<Long, ChunkInfo> cm = chunks(mc);
            for (ChunkInfo ci : cm.values()) if (isFlagged(cm, ci)) flagged++;
            lines.add("Suspicious chunks: " + flagged); cols.add(flagged > 0 ? 0xFFFF7755 : 0xFFBBBBCC);
            if (holesOn) { lines.add("Holes: " + holes.size()); cols.add(holes.isEmpty() ? 0xFFBBBBCC : hueColor(holeHue)); }
            if (amethystOn) {
                double best = 0;
                for (Geode ge : geodes) best = Math.max(best, ge.likelihood);
                lines.add(geodes.isEmpty() ? "Geodes: none" : String.format("Geodes: %d (best %.0f%%)", geodes.size(), best));
                cols.add(geodes.isEmpty() ? 0xFFBBBBCC : riskColor(best));
            }
            if (totemOn) { lines.add("Totems: " + countTotems(mc)); cols.add(0xFFFFD84A); }
            if (radarOn) {
                lines.add("Players <" + alertRadius + "m: " + nearby.size()); cols.add(nearby.isEmpty() ? 0xFFBBBBCC : 0xFFFF5555);
                for (int i = 0; !radarWidget && i < Math.min(radarList, nearby.size()); i++) {
                    Nearby n = nearby.get(i);
                    lines.add(String.format("  %s  %dm  %dhp", n.name(), (int) n.dist(), (int) Math.ceil(n.hp())));
                    cols.add(0xFFFF8888);
                }
            }
            if (vanishOn && !vanishNow.isEmpty()) {
                lines.add("Vanish suspects: " + vanishNow.size()); cols.add(0xFFFFAA33);
                int k = 0;
                for (String nm : vanishNow.values()) { if (k++ >= 3) break; lines.add("  " + nm); cols.add(0xFFFFCC77); }
            }
            int w = tr.getWidth(NAME) + 14;
            for (String l : lines) w = Math.max(w, tr.getWidth(l) + 14);
            int h = 18 + lines.size() * 10 + 5;
            int x = 4, y = 4;
            g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x50000000);
            g.fillGradient(x, y, x + w, y + h, 0xD0202034, 0xC00C0C14);
            g.fillGradient(x, y, x + 2, y + h, accent, accentDim);
            g.fillGradient(x, y, x + w, y + 14, 0x30FFFFFF, 0x00FFFFFF);
            g.fillGradient(x + 2, y + 14, x + w, y + 15, accent, accentDim);
            g.drawTextWithShadow(tr, NAME, x + 8, y + 3, accent);
            for (int i = 0; i < lines.size(); i++)
                g.drawTextWithShadow(tr, lines.get(i), x + 8, y + 19 + i * 10, cols.get(i));
        }

        if (hudList) {
            List<Mod> act = new ArrayList<>();
            for (Mod m : MODS) if (!m.cat.equals("Client") && m.get.getAsBoolean()) act.add(m);
            act.sort((a, b) -> tr.getWidth(b.name) - tr.getWidth(a.name));
            int ry = 4;
            float t = (System.currentTimeMillis() % 6000) / 6000f;
            for (int i = 0; i < act.size(); i++) {
                String m = act.get(i).name;
                int c = rainbow ? hsv(t + i * 0.045f, 0.55f, 1f) : accent;
                int mw = tr.getWidth(m);
                int rx0 = (int) sw - mw - 10;
                g.fill(rx0 - 3, ry, (int) sw - 3, ry + 11, 0x90101018);
                g.fill((int) sw - 3, ry, (int) sw - 1, ry + 11, c);
                g.fill(rx0 - 3, ry, rx0 - 2, ry + 11, mulAlpha(c, 0.5));
                g.drawTextWithShadow(tr, m, rx0, ry + 2, c);
                ry += 11;
            }
        }

        if (hudBanner && radarOn && !nearby.isEmpty()) renderAlertBanner(g, mc);
    }

    // ---- toasts (small slide-in notifications)
    static final class Toast {
        final String title, sub;
        final int color;
        final long t0 = System.currentTimeMillis();
        Toast(String title, String sub, int color) { this.title = title; this.sub = sub; this.color = color; }
    }

    static final List<Toast> toasts = new ArrayList<>();

    static void toast(String title, String sub, int color) {
        if (!toastsOn) return;
        toasts.add(new Toast(title, sub, color));
        while (toasts.size() > 4) toasts.remove(0);
    }

    void renderToasts(DrawContext g, MinecraftClient mc) {
        var tr = mc.textRenderer;
        long now = System.currentTimeMillis();
        toasts.removeIf(t -> now - t.t0 > 4400);
        int y = 34;
        for (Toast t : toasts) {
            long age = now - t.t0;
            double in = Math.min(1.0, age / 250.0);
            double out = age > 4000 ? 1.0 - (age - 4000) / 400.0 : 1.0;
            double k = Math.max(0.0, Math.min(in, out));
            int w = Math.max(tr.getWidth(t.title), tr.getWidth(t.sub)) + 20;
            int x = (int) sw / 2 - w / 2;
            int yy = y + (int) ((1 - in) * -14);
            int accent = mulAlpha(t.color, k);
            g.fill(x - 1, yy - 1, x + w + 1, yy + 25, mulAlpha(0x60000000, k));
            g.fillGradient(x, yy, x + w, yy + 24, mulAlpha(0xF0242438, k), mulAlpha(0xF00C0C16, k));
            g.fill(x, yy, x + 3, yy + 24, accent);
            g.fill(x + 3, yy + 22, x + 3 + (int) ((w - 3) * Math.max(0, 1 - age / 4400.0)), yy + 24, mulAlpha(t.color, 0.6 * k));
            if (k > 0.1) {
                int a = ((int) (255 * k)) << 24;
                g.drawTextWithShadow(tr, t.title, x + 9, yy + 3, (t.color & 0xFFFFFF) | a);
                g.drawTextWithShadow(tr, t.sub, x + 9, yy + 13, 0xDDDDEE | a);
            }
            y += 28;
        }
    }

    static void ring(DrawContext g, int cx, int cy, int r, int col) {
        int steps = Math.max(24, r * 5);
        for (int i = 0; i < steps; i++) {
            double a = i * Math.PI * 2 / steps;
            int px = cx + (int) Math.round(Math.cos(a) * r), py = cy + (int) Math.round(Math.sin(a) * r);
            g.fill(px, py, px + 1, py + 1, col);
        }
    }

    static String fit(net.minecraft.client.font.TextRenderer tr, String s, int maxW) {
        while (s.length() > 1 && tr.getWidth(s) > maxW) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Mini radar with a sweep line plus a card per nearby player (name, distance, health). */
    void renderRadarWidget(DrawContext g, MinecraftClient mc) {
        var tr = mc.textRenderer;
        int size = radarSize, head = 12, margin = 8;
        boolean right = radarPos == 0 || radarPos == 2;
        boolean bottom = radarPos == 0 || radarPos == 1;
        int x = right ? (int) sw - size - margin : margin;
        int y = bottom ? (int) sh - size - head - 40 : (int) (sh / 2 - (size + head) / 2);
        int total = nearby.size();
        int alertCol = total > 0 ? 0xFFFF5555 : 0xFF66DD99;
        int rgb = alertCol & 0xFFFFFF;

        g.fill(x - 1, y - 1, x + size + 1, y + size + head + 1, 0x60000000);
        g.fillGradient(x, y, x + size, y + size + head, 0xE01A1A2E, 0xE00A0A14);
        g.fillGradient(x, y, x + size, y + head, 0x60000000 | rgb, 0x10000000 | rgb);
        g.fill(x, y + head, x + size, y + head + 1, alertCol);
        g.drawTextWithShadow(tr, "PLAYERS", x + 4, y + 2, 0xFFFFFFFF);
        String cnt = String.valueOf(total);
        g.drawTextWithShadow(tr, cnt, x + size - 4 - tr.getWidth(cnt), y + 2, alertCol);

        int cx = x + size / 2, cy = y + head + 1 + (size - 1) / 2, rad = size / 2 - 5;
        ring(g, cx, cy, rad, 0x55FFFFFF);
        ring(g, cx, cy, Math.max(2, rad * 2 / 3), 0x2AFFFFFF);
        ring(g, cx, cy, Math.max(2, rad / 3), 0x2AFFFFFF);
        g.fill(cx, cy - rad, cx + 1, cy + rad + 1, 0x22FFFFFF);
        g.fill(cx - rad, cy, cx + rad + 1, cy + 1, 0x22FFFFFF);

        double sweep = (System.currentTimeMillis() % 2400) / 2400.0 * Math.PI * 2;
        for (int k = 0; k < 6; k++) {
            double a = sweep - k * 0.1;
            line2(g, cx, cy, cx + Math.sin(a) * rad, cy - Math.cos(a) * rad, ((int) (110 - k * 17) << 24) | rgb);
        }

        double yaw = Math.toRadians(mc.player.getYaw());
        double fwdX = -Math.sin(yaw), fwdZ = Math.cos(yaw), rightX = -Math.cos(yaw), rightZ = -Math.sin(yaw);
        double scale = rad / (double) Math.max(1, alertRadius);
        for (int i = 0; i < nearby.size(); i++) {
            Nearby nb = nearby.get(i);
            double dx = nb.pos().getX() + 0.5 - mc.player.getX(), dz = nb.pos().getZ() + 0.5 - mc.player.getZ();
            double px = dx * rightX + dz * rightZ, py = dx * fwdX + dz * fwdZ;
            double len = Math.sqrt(px * px + py * py);
            if (len > alertRadius) { px *= alertRadius / len; py *= alertRadius / len; }
            int dxp = cx + (int) Math.round(px * scale), dyp = cy - (int) Math.round(py * scale);
            int dc = lerpColor(0xFFFF4444, 0xFFFFDD55, (float) Math.min(1.0, nb.dist() / Math.max(1, alertRadius)));
            g.fill(dxp - 2, dyp - 2, dxp + 3, dyp + 3, 0xFF000000);
            g.fill(dxp - 1, dyp - 1, dxp + 2, dyp + 2, dc);
            if (i < 3 && size >= 70) {
                String nm = fit(tr, nb.name(), 48);
                g.drawTextWithShadow(tr, nm, dxp - tr.getWidth(nm) / 2, dyp - 11, dc);
            }
        }
        // you
        g.fill(cx, cy - 3, cx + 1, cy - 2, 0xFFFFFFFF);
        g.fill(cx - 1, cy - 2, cx + 2, cy - 1, 0xFFFFFFFF);
        g.fill(cx - 2, cy - 1, cx + 3, cy, 0xFFFFFFFF);

        if (radarCards && total > 0) {
            int cn = Math.min(radarList, total), ch = 16;
            int cy0 = bottom ? y - cn * (ch + 2) - 1 : y + head + size + 4;
            for (int i = 0; i < cn; i++) {
                Nearby nb = nearby.get(i);
                int yy = cy0 + i * (ch + 2);
                double hpRatio = Math.min(1.0, nb.hp() / 20.0);
                int hc = hpColor(hpRatio);
                int dc = lerpColor(0xFFFF6666, 0xFFFFDD66, (float) Math.min(1.0, nb.dist() / Math.max(1, alertRadius)));
                g.fill(x - 1, yy - 1, x + size + 1, yy + ch + 1, 0x60000000);
                g.fillGradient(x, yy, x + size, yy + ch, 0xE0242438, 0xE00E0E18);
                g.fill(x, yy, x + 3, yy + ch, hc);
                String d = (int) nb.dist() + "m";
                g.drawTextWithShadow(tr, fit(tr, nb.name(), size - 18 - tr.getWidth(d)), x + 7, yy + 2, 0xFFFFFFFF);
                g.drawTextWithShadow(tr, d, x + size - 4 - tr.getWidth(d), yy + 2, dc);
                g.fill(x + 3, yy + ch - 3, x + size, yy + ch - 1, 0x66000000);
                g.fill(x + 3, yy + ch - 3, x + 3 + (int) ((size - 3) * hpRatio), yy + ch - 1, hc);
            }
        }
    }

    void renderAlertBanner(DrawContext g, MinecraftClient mc) {
        var tr = mc.textRenderer;
        Nearby near = nearby.get(0);
        String title = nearby.size() == 1 ? "PLAYER NEARBY" : nearby.size() + " PLAYERS NEARBY";
        String sub = near.name() + "  " + (int) near.dist() + "m  " + (int) Math.ceil(near.hp()) + "hp"
                + (nearby.size() > 1 ? "  (+" + (nearby.size() - 1) + ")" : "");
        int w = Math.max(tr.getWidth(title), tr.getWidth(sub)) + 34;
        int bx = (int) sw / 2 - w / 2, by = 4;
        double k = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 250.0);
        int edge = lerpColor(0xFFFF3333, 0xFFFF9999, (float) k);
        g.fill(bx - 1, by - 1, bx + w + 1, by + 27, 0x60000000);
        g.fillGradient(bx, by, bx + w, by + 26, 0xE03A1018, 0xE0180A10);
        g.fill(bx, by, bx + w, by + 1, edge);
        g.fill(bx, by + 25, bx + w, by + 26, mulAlpha(edge, 0.5));
        g.fill(bx + 5, by + 5, bx + 21, by + 21, edge);
        g.drawTextWithShadow(tr, "!", bx + 12, by + 9, 0xFF300808);
        g.drawTextWithShadow(tr, title, bx + 28, by + 4, 0xFFFF8888);
        g.drawTextWithShadow(tr, sub, bx + 28, by + 14, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------ commands
    static LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, BooleanSupplier flip) {
        return ClientCommandManager.literal(name).executes(c -> {
            boolean v = flip.getAsBoolean();
            c.getSource().sendFeedback(Text.literal(PREFIX + name + ": " + (v ? "ON" : "OFF")));
            return 1;
        });
    }

    static Block parseBlock(String s) {
        Identifier id = Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s);
        return (id != null && Registries.BLOCK.containsId(id)) ? Registries.BLOCK.get(id) : null;
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> intSetting(String name, int min, int max,
                                                                         java.util.function.IntConsumer setter) {
        return ClientCommandManager.literal(name).then(ClientCommandManager.argument("n", IntegerArgumentType.integer(min, max))
                .executes(c -> {
                    int v = IntegerArgumentType.getInteger(c, "n");
                    setter.accept(v);
                    c.getSource().sendFeedback(Text.literal(PREFIX + name + " = " + v));
                    return 1;
                }));
    }

    static void registerCommands(CommandDispatcher<FabricClientCommandSource> d) {
        var root = ClientCommandManager.literal("lz");
        root.executes(c -> { openGuiRequested = true; return 1; });
        root.then(ClientCommandManager.literal("gui").executes(c -> { openGuiRequested = true; return 1; }));
        root.then(toggle("hud", () -> hudOn = !hudOn));
        root.then(toggle("esp", () -> espOn = !espOn));
        root.then(toggle("blocks", () -> blocksOn = !blocksOn));
        root.then(toggle("chunks", () -> chunksOn = !chunksOn));
        root.then(toggle("amethyst", () -> amethystOn = !amethystOn));
        root.then(toggle("radar", () -> radarOn = !radarOn));
        root.then(toggle("nametags", () -> nametagsOn = !nametagsOn));
        root.then(toggle("vanish", () -> vanishOn = !vanishOn));
        root.then(toggle("totem", () -> totemOn = !totemOn));
        root.then(toggle("tracers", () -> tracers = !tracers));
        root.then(toggle("holes", () -> holesOn = !holesOn));
        root.then(toggle("notify", () -> notify = !notify));
        root.then(ClientCommandManager.literal("filter")
                .then(toggle("players", () -> fPlayers = !fPlayers))
                .then(toggle("hostile", () -> fHostile = !fHostile))
                .then(toggle("passive", () -> fPassive = !fPassive))
                .then(toggle("items", () -> fItems = !fItems))
                .then(toggle("vehicles", () -> fVehicles = !fVehicles)));
        root.then(intSetting("threshold", 1, 10000, v -> threshold = v));
        root.then(intSetting("radius", 8, 128, v -> alertRadius = v));
        root.then(intSetting("totemdelay", 0, 20, v -> totemDelay = v));
        root.then(intSetting("range", 16, 1024, v -> blockRange = entityRange = v));
        root.then(ClientCommandManager.literal("add")
                .then(ClientCommandManager.argument("block", StringArgumentType.greedyString()).executes(c -> {
                    Block b = parseBlock(StringArgumentType.getString(c, "block").trim());
                    if (b == null) { c.getSource().sendFeedback(Text.literal(PREFIX + "unknown block")); return 0; }
                    espBlocks.add(b);
                    rescanAll(MinecraftClient.getInstance());
                    c.getSource().sendFeedback(Text.literal(PREFIX + "added " + Registries.BLOCK.getId(b)));
                    return 1;
                })));
        root.then(ClientCommandManager.literal("remove")
                .then(ClientCommandManager.argument("block", StringArgumentType.greedyString()).executes(c -> {
                    Block b = parseBlock(StringArgumentType.getString(c, "block").trim());
                    if (b == null || !espBlocks.remove(b)) { c.getSource().sendFeedback(Text.literal(PREFIX + "not in list")); return 0; }
                    rescanAll(MinecraftClient.getInstance());
                    c.getSource().sendFeedback(Text.literal(PREFIX + "removed " + Registries.BLOCK.getId(b)));
                    return 1;
                })));
        root.then(ClientCommandManager.literal("blocklist").executes(c -> {
            StringBuilder sb = new StringBuilder(PREFIX + "ESP blocks: ");
            espBlocks.stream().map(b -> Registries.BLOCK.getId(b).getPath()).sorted().forEach(s -> sb.append(s).append(", "));
            c.getSource().sendFeedback(Text.literal(sb.toString()));
            return 1;
        }));
        root.then(ClientCommandManager.literal("list").executes(c -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null) return 0;
            List<ChunkInfo> l = new ArrayList<>();
            Map<Long, ChunkInfo> cm = chunks(mc);
            for (ChunkInfo ci : cm.values()) if (isFlagged(cm, ci)) l.add(ci);
            l.sort((a, b) -> Float.compare(b.score, a.score));
            if (l.isEmpty()) c.getSource().sendFeedback(Text.literal(PREFIX + "no suspicious chunks yet"));
            for (int i = 0; i < Math.min(10, l.size()); i++) {
                ChunkInfo ci = l.get(i);
                c.getSource().sendFeedback(Text.literal(String.format(PREFIX + "#%d  x=%d z=%d  score %.0f",
                        i + 1, ci.cx * 16 + 8, ci.cz * 16 + 8, ci.score)));
            }
            return 1;
        }));
        root.then(ClientCommandManager.literal("geodes").executes(c -> {
            if (geodes.isEmpty()) c.getSource().sendFeedback(Text.literal(PREFIX + "no geodes tracked yet"));
            for (int i = 0; i < Math.min(10, geodes.size()); i++) {
                Geode ge = geodes.get(i);
                c.getSource().sendFeedback(Text.literal(String.format(PREFIX + "geode x=%d y=%d z=%d  clusters %d  budding %d  base %.0f%%",
                        (int) ge.cx(), (int) ge.cy(), (int) ge.cz(), ge.clusters, ge.budding, ge.likelihood)));
            }
            return 1;
        }));
        root.then(ClientCommandManager.literal("rescan").executes(c -> {
            rescanAll(MinecraftClient.getInstance());
            c.getSource().sendFeedback(Text.literal(PREFIX + "rescanning loaded chunks"));
            return 1;
        }));
        root.then(ClientCommandManager.literal("clear").executes(c -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world != null) chunks(mc).clear();
            geodes = new ArrayList<>();
            c.getSource().sendFeedback(Text.literal(PREFIX + "cleared remembered data for this dimension"));
            return 1;
        }));
        root.then(ClientCommandManager.literal("status").executes(c -> {
            c.getSource().sendFeedback(Text.literal(String.format(
                    PREFIX + "hud=%b esp=%b blocks=%b chunks=%b amethyst=%b radar=%b nametags=%b vanish=%b totem=%b tracers=%b | threshold=%d radius=%d range=%d",
                    hudOn, espOn, blocksOn, chunksOn, amethystOn, radarOn, nametagsOn, vanishOn, totemOn, tracers,
                    threshold, alertRadius, blockRange)));
            return 1;
        }));
        var node = d.register(root);
        d.register(ClientCommandManager.literal("lordzinka").redirect(node));
    }

    // ------------------------------------------------------------------ modules + ClickGUI
    static abstract class Setting {
        final String name;
        boolean adv;       // shown only under "Advanced"
        Setting(String n) { name = n; }
        abstract String save();
        abstract void load(String v);
    }

    static final class BoolS extends Setting {
        final BooleanSupplier get;
        final Consumer<Boolean> set;
        BoolS(String n, BooleanSupplier g, Consumer<Boolean> s) { super(n); get = g; set = s; }
        @Override String save() { return String.valueOf(get.getAsBoolean()); }
        @Override void load(String v) { set.accept(Boolean.parseBoolean(v.trim())); }
    }

    static class IntS extends Setting {
        final int min, max;
        final IntSupplier get;
        final IntConsumer set;
        IntS(String n, int min, int max, IntSupplier g, IntConsumer s) { super(n); this.min = min; this.max = max; get = g; set = s; }
        @Override String save() { return String.valueOf(get.getAsInt()); }
        @Override void load(String v) { set.accept(Math.max(min, Math.min(max, Integer.parseInt(v.trim())))); }
    }

    /** Hue slider 0-359. */
    static final class ColorS extends IntS {
        ColorS(String n, IntSupplier g, IntConsumer s) { super(n, 0, 359, g, s); }
    }

    static final class EnumS extends Setting {
        final String[] options;
        final IntSupplier get;
        final IntConsumer set;
        EnumS(String n, String[] o, IntSupplier g, IntConsumer s) { super(n); options = o; get = g; set = s; }
        void cycle(int d) { int n = options.length; set.accept(((get.getAsInt() + d) % n + n) % n); }
        String current() { return options[Math.max(0, Math.min(options.length - 1, get.getAsInt()))]; }
        @Override String save() { return String.valueOf(get.getAsInt()); }
        @Override void load(String v) { int i = Integer.parseInt(v.trim()); if (i >= 0 && i < options.length) set.accept(i); }
    }

    /** Keyboard bind (GLFW key code, 0 = none). */
    static final class BindS extends Setting {
        final IntSupplier get;
        final IntConsumer set;
        boolean canClear = true;
        BindS(String n, IntSupplier g, IntConsumer s) { super(n); get = g; set = s; }
        @Override String save() { return String.valueOf(get.getAsInt()); }
        @Override void load(String v) {
            int k = Integer.parseInt(v.trim());
            if (k >= 0 && k <= GLFW.GLFW_KEY_LAST) set.accept(k);
        }
    }

    /** The "Advanced +/-" row that reveals a module's less common settings. */
    static final class AdvS extends Setting {
        final Mod owner;
        AdvS(Mod owner) { super("Advanced"); this.owner = owner; }
        @Override String save() { return ""; }
        @Override void load(String v) { }
    }

    static final class Mod {
        final String name, cat;
        final BooleanSupplier get;
        final Consumer<Boolean> set;
        final List<Setting> settings = new ArrayList<>();
        final BindS bind;
        String desc = "";
        int key = 0;
        boolean open;
        float anim;
        boolean showAdv;
        final AdvS advRow = new AdvS(this);
        Mod(String name, String cat, BooleanSupplier get, Consumer<Boolean> set) {
            this.name = name; this.cat = cat; this.get = get; this.set = set;
            this.bind = new BindS("Bind", () -> key, k -> key = k);
            settings.add(bind);
        }
        Mod with(Setting s) { settings.add(s); return this; }
        Mod adv(Setting s) { s.adv = true; settings.add(s); return this; }
        /** Rows shown in the GUI: simple settings, then the Advanced toggle and (if open) the advanced settings. */
        List<Setting> rows() {
            List<Setting> r = new ArrayList<>();
            boolean hasAdv = false;
            for (Setting s : settings) { if (!s.adv) r.add(s); else hasAdv = true; }
            if (hasAdv) {
                r.add(advRow);
                if (showAdv) for (Setting s : settings) if (s.adv) r.add(s);
            }
            return r;
        }
        Mod info(String d) { desc = d; return this; }
    }

    static final List<Mod> MODS = new ArrayList<>();
    static final Map<String, int[]> GUI_POS = new LinkedHashMap<>();
    static final Set<String> COLLAPSED = new HashSet<>();
    static final boolean[] keyWas = new boolean[GLFW.GLFW_KEY_LAST + 1];
    static Mod guiMod;
    static BindS listening;
    static boolean openGuiRequested, guiWasOpen;

    static BoolS sb(String n, BooleanSupplier g, Consumer<Boolean> s) { return new BoolS(n, g, s); }
    static IntS si(String n, int min, int max, IntSupplier g, IntConsumer s) { return new IntS(n, min, max, g, s); }
    static ColorS sc(String n, IntSupplier g, IntConsumer s) { return new ColorS(n, g, s); }
    static EnumS se(String n, String[] o, IntSupplier g, IntConsumer s) { return new EnumS(n, o, g, s); }

    static final Map<String, List<Block>> GROUPS = new LinkedHashMap<>();

    static void buildGroups() {
        GROUPS.put("Chests", List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST));
        GROUPS.put("Ender Chests", List.of(Blocks.ENDER_CHEST));
        GROUPS.put("Barrels", List.of(Blocks.BARREL));
        List<Block> shulkers = new ArrayList<>();
        for (Block b : Registries.BLOCK)
            if (Registries.BLOCK.getId(b).getPath().endsWith("shulker_box")) shulkers.add(b);
        GROUPS.put("Shulkers", shulkers);
        GROUPS.put("Spawners", List.of(Blocks.SPAWNER));
        GROUPS.put("Beacons", List.of(Blocks.BEACON));
        GROUPS.put("Ench. Tables", List.of(Blocks.ENCHANTING_TABLE));
        GROUPS.put("Furnaces", List.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER));
        GROUPS.put("Hoppers", List.of(Blocks.HOPPER));
        GROUPS.put("Dispensers", List.of(Blocks.DISPENSER, Blocks.DROPPER));
        // enabled by default
        for (String g : new String[]{"Chests", "Ender Chests", "Barrels", "Shulkers", "Spawners", "Beacons", "Ench. Tables"})
            espBlocks.addAll(GROUPS.get(g));
    }

    static void buildModules() {
        // ---------------- Render
        // Every module shows only its most useful options first; the rest sits under "Advanced".
        MODS.add(new Mod("Chunk Finder", "Render", () -> chunksOn, v -> chunksOn = v)
                .info("Znajduje bazy graczy i aktywnosc (klastry ametystu). Kwadrat mozna ustawic na wybranej wysokosci Y")
                .with(se("Sensitivity", new String[]{"Low", "Normal", "High"},
                        () -> threshold >= 25 ? 0 : threshold >= 12 ? 1 : 2,
                        v -> threshold = v == 0 ? 30 : v == 1 ? 15 : 8))
                .with(si("Clusters (0=off)", 0, 60, () -> amClusterMin, v -> amClusterMin = v))
                .with(se("Style", new String[]{"Square", "Column", "Box"}, () -> chunkStyle, v -> chunkStyle = v))
                .with(se("Y Mode", new String[]{"Detected", "Fixed"}, () -> chunkYMode, v -> chunkYMode = v))
                .with(si("Y Level", -64, 320, () -> chunkY, v -> chunkY = v))
                .with(se("Color Mode", new String[]{"Auto", "Custom"}, () -> chunkColorMode, v -> chunkColorMode = v))
                .with(sc("Color", () -> chunkHue, v -> chunkHue = v))
                .adv(si("Threshold", 1, 200, () -> threshold, v -> threshold = v))
                .adv(si("Max Chunks", 5, 100, () -> chunkMax, v -> chunkMax = v))
                .adv(si("Range", 64, 2048, () -> chunkRange, v -> chunkRange = v))
                .adv(si("Height", 1, 256, () -> chunkHeight, v -> chunkHeight = v))
                .adv(sb("Fill", () -> chunkFill, v -> chunkFill = v))
                .adv(si("Fill Opacity", 5, 80, () -> chunkAlpha, v -> chunkAlpha = v))
                .adv(sb("Outline", () -> chunkOutline, v -> chunkOutline = v))
                .adv(sb("Beams", () -> chunkBeams, v -> chunkBeams = v))
                .adv(sb("Pulse", () -> chunkPulse, v -> chunkPulse = v))
                .adv(sb("Labels", () -> chunkLabels, v -> chunkLabels = v))
                .adv(si("Scan Speed", 1, 10, () -> chunkSpeed, v -> chunkSpeed = v))
                .adv(si("Rescan Ticks", 20, 600, () -> chunkRescan, v -> chunkRescan = v)));

        MODS.add(new Mod("Hole ESP", "Render", () -> holesOn, v -> holesOn = v)
                .info("Podswietla pionowe dziury 1x1. Ustaw minimalna dlugosc, od ktorej dziura sie pojawia, i kolor")
                .with(si("Min Length", 1, 32, () -> holeMin, v -> holeMin = v))
                .with(sc("Color", () -> holeHue, v -> holeHue = v))
                .with(sb("Fill", () -> holeFill, v -> holeFill = v))
                .adv(si("Range", 4, 32, () -> holeRange, v -> holeRange = v))
                .adv(si("Vertical Range", 8, 64, () -> holeVRange, v -> holeVRange = v))
                .adv(si("Max Length", 1, 128, () -> holeMaxLen, v -> holeMaxLen = v))
                .adv(si("Max Holes", 10, 200, () -> holeCap, v -> holeCap = v))
                .adv(sb("Require Floor", () -> holeFloor, v -> holeFloor = v))
                .adv(se("Color Mode", new String[]{"Single", "Depth"}, () -> holeMode, v -> holeMode = v))
                .adv(sc("Deep Color", () -> holeHue2, v -> holeHue2 = v))
                .adv(si("Fill Opacity", 5, 80, () -> holeAlpha, v -> holeAlpha = v))
                .adv(sb("Outline", () -> holeOutline, v -> holeOutline = v))
                .adv(sb("Labels", () -> holeLabels, v -> holeLabels = v)));

        Mod blockEsp = new Mod("Block ESP", "Render", () -> blocksOn, v -> blocksOn = v)
                .info("Podswietla skrzynki, beczki, spawnery itp. przez sciany. Dziala tez w Free Cam")
                .with(se("Style", new String[]{"Outline", "Fill", "Both"}, () -> blockStyle, v -> blockStyle = v))
                .with(se("Color Mode", new String[]{"By Type", "Custom"}, () -> blockColorMode, v -> blockColorMode = v))
                .with(sc("Color", () -> blockHue, v -> blockHue = v))
                .with(si("Range", 16, 512, () -> blockRange, v -> blockRange = v))
                .adv(si("Max Boxes", 50, 3000, () -> blockMax, v -> blockMax = v))
                .adv(si("Fill Opacity", 5, 60, () -> blockFillAlpha, v -> blockFillAlpha = v))
                .adv(sb("Labels", () -> blockLabels, v -> blockLabels = v));
        List<String> simpleGroups = List.of("Chests", "Ender Chests", "Barrels", "Shulkers", "Spawners");
        for (Map.Entry<String, List<Block>> en : GROUPS.entrySet()) {
            final List<Block> list = en.getValue();
            Setting s = sb(en.getKey(), () -> espBlocks.containsAll(list), v -> {
                if (v) espBlocks.addAll(list); else espBlocks.removeAll(list);
                rescanAll(MinecraftClient.getInstance());
            });
            if (simpleGroups.contains(en.getKey())) blockEsp.with(s); else blockEsp.adv(s);
        }
        MODS.add(blockEsp);

        MODS.add(new Mod("Amethyst ESP", "Render", () -> amethystOn, v -> amethystOn = v)
                .info("Znajduje geody i ocenia, czy ktos juz je zebral")
                .with(si("Range", 32, 512, () -> amRange, v -> amRange = v))
                .with(sb("Fill", () -> amFill, v -> amFill = v))
                .with(sb("Mark Budding", () -> amBudding, v -> amBudding = v))
                .adv(si("Max Geodes", 1, 30, () -> amMax, v -> amMax = v))
                .adv(si("Min Chance %", 0, 99, () -> amMinPct, v -> amMinPct = v)));

        MODS.add(new Mod("Entity ESP", "Render", () -> espOn, v -> espOn = v)
                .info("Ramki wokol graczy, mobow, itemow i pojazdow")
                .with(sb("Players", () -> fPlayers, v -> fPlayers = v))
                .with(sb("Hostile", () -> fHostile, v -> fHostile = v))
                .with(sb("Passive", () -> fPassive, v -> fPassive = v))
                .with(sb("Items", () -> fItems, v -> fItems = v))
                .with(se("Box", new String[]{"2D", "Corners", "3D"}, () -> espStyle, v -> espStyle = v))
                .with(si("Range", 16, 512, () -> entityRange, v -> entityRange = v))
                .adv(sb("Vehicles", () -> fVehicles, v -> fVehicles = v))
                .adv(sb("Fill", () -> espFill, v -> espFill = v))
                .adv(si("Fill Opacity", 5, 60, () -> espFillAlpha, v -> espFillAlpha = v))
                .adv(sb("Health Bar", () -> espHealthBar, v -> espHealthBar = v))
                .adv(sb("Labels", () -> espLabels, v -> espLabels = v))
                .adv(sb("Distance", () -> espDistance, v -> espDistance = v))
                .adv(sc("Color Players", () -> huePlayer, v -> huePlayer = v))
                .adv(sc("Color Hostile", () -> hueHostile, v -> hueHostile = v))
                .adv(sc("Color Passive", () -> huePassive, v -> huePassive = v))
                .adv(sc("Color Items", () -> hueItem, v -> hueItem = v))
                .adv(sc("Color Vehicles", () -> hueVehicle, v -> hueVehicle = v)));

        MODS.add(new Mod("Nametags", "Render", () -> nametagsOn, v -> nametagsOn = v)
                .info("Rozbudowane tabliczki graczy: HP, pancerz, item w rece")
                .with(sb("Health", () -> ntHealth, v -> ntHealth = v))
                .with(sb("Distance", () -> ntDistance, v -> ntDistance = v))
                .with(sb("Armor", () -> ntArmor, v -> ntArmor = v))
                .with(sb("Held Item", () -> ntItem, v -> ntItem = v))
                .adv(si("Range", 16, 256, () -> ntRange, v -> ntRange = v))
                .adv(sb("Ping", () -> ntPing, v -> ntPing = v))
                .adv(sb("Background", () -> ntBackground, v -> ntBackground = v)));

        MODS.add(new Mod("Tracers", "Render", () -> tracers, v -> tracers = v)
                .info("Linie od ekranu do chunkow, dziur, graczy i geod")
                .with(sb("Chunks", () -> trChunks, v -> trChunks = v))
                .with(sb("Holes", () -> trHoles, v -> trHoles = v))
                .with(sb("Entities", () -> trEntities, v -> trEntities = v))
                .with(sb("Geodes", () -> trGeodes, v -> trGeodes = v))
                .with(se("Origin", new String[]{"Bottom", "Center"}, () -> trOrigin, v -> trOrigin = v)));

        // ---------------- Player
        MODS.add(new Mod("Player Detection", "Player", () -> radarOn, v -> radarOn = v)
                .info("Wykrywa graczy w poblizu: mini radar, karty z HP i dystansem, alerty")
                .with(si("Radius", 8, 128, () -> alertRadius, v -> alertRadius = v))
                .with(sb("Radar Widget", () -> radarWidget, v -> radarWidget = v))
                .with(se("Position", new String[]{"Bottom Right", "Bottom Left", "Right Mid", "Left Mid"},
                        () -> radarPos, v -> radarPos = v))
                .with(sb("Chat Alerts", () -> radarChat, v -> radarChat = v))
                .with(sb("Sound", () -> radarSound, v -> radarSound = v))
                .adv(si("Widget Size", 56, 120, () -> radarSize, v -> radarSize = v))
                .adv(sb("Player Cards", () -> radarCards, v -> radarCards = v))
                .adv(si("List Size", 1, 10, () -> radarList, v -> radarList = v)));

        MODS.add(new Mod("Vanish Detect", "Player", () -> vanishOn, v -> vanishOn = v)
                .info("Wykrywa ukrytych graczy (vanish) i spectatorow")
                .with(sb("No Tab Entry", () -> vanTab, v -> vanTab = v))
                .with(sb("Spectators", () -> vanSpec, v -> vanSpec = v))
                .with(sb("Chat Alerts", () -> vanChat, v -> vanChat = v)));

        MODS.add(new Mod("Auto Totem", "Player", () -> totemOn, v -> totemOn = v)
                .info("Automatycznie wklada totem do drugiej reki")
                .with(si("HP Below (0=any)", 0, 20, () -> totemHealth, v -> totemHealth = v))
                .with(sb("Warn When Empty", () -> totemWarn, v -> totemWarn = v))
                .adv(si("Delay", 0, 20, () -> totemDelay, v -> totemDelay = v))
                .adv(sb("Hotbar", () -> totemHotbar, v -> totemHotbar = v)));

        // ---------------- Client
        guiMod = new Mod("ClickGUI", "Client", () -> true, v -> { })
                .info("To menu. Kliknij Bind i nacisnij dowolny klawisz, zeby zmienic przycisk otwierajacy");
        guiMod.key = GLFW.GLFW_KEY_RIGHT_SHIFT;
        guiMod.bind.canClear = false;
        guiMod.with(sb("Rainbow", () -> rainbow, v -> rainbow = v))
                .with(sc("Accent Hue", () -> accentHue, v -> accentHue = v));
        MODS.add(guiMod);

        MODS.add(new Mod("HUD", "Client", () -> hudOn, v -> hudOn = v)
                .info("Panel informacji, lista modulow i alerty na ekranie")
                .with(sb("Info Panel", () -> hudInfo, v -> hudInfo = v))
                .with(sb("Coords", () -> hudCoords, v -> hudCoords = v))
                .with(sb("Nether Coords", () -> hudNether, v -> hudNether = v))
                .with(sb("FPS", () -> hudFps, v -> hudFps = v))
                .with(sb("Module List", () -> hudList, v -> hudList = v))
                .with(sb("Alert Banner", () -> hudBanner, v -> hudBanner = v)));
        MODS.add(new Mod("Notifications", "Client", () -> notify, v -> notify = v)
                .info("Wiadomosci o podejrzanych chunkach i aktywnosci gracza")
                .with(sb("Toasts", () -> toastsOn, v -> toastsOn = v)));
    }

    static int accent() {
        return rainbow ? hsv((System.currentTimeMillis() % 6000) / 6000f, 0.55f, 1f)
                : hsv(accentHue / 360f, 0.6f, 1f);
    }

    static int hueColor(int hue) { return hsv(hue / 360f, 0.8f, 1f); }

    static String keyName(int k) {
        if (k <= 0) return "None";
        switch (k) {
            case 32: return "Space";
            case 256: return "Esc";
            case 257: return "Enter";
            case 258: return "Tab";
            case 259: return "Backspace";
            case 260: return "Insert";
            case 261: return "Delete";
            case 262: return "Right";
            case 263: return "Left";
            case 264: return "Down";
            case 265: return "Up";
            case 266: return "PgUp";
            case 267: return "PgDn";
            case 268: return "Home";
            case 269: return "End";
            case 280: return "Caps";
            case 340: return "L-Shift";
            case 341: return "L-Ctrl";
            case 342: return "L-Alt";
            case 343: return "L-Super";
            case 344: return "R-Shift";
            case 345: return "R-Ctrl";
            case 346: return "R-Alt";
            case 347: return "R-Super";
            case 348: return "Menu";
            default: break;
        }
        if (k >= 290 && k <= 314) return "F" + (k - 289);
        if (k >= 320 && k <= 329) return "Num" + (k - 320);
        String n = GLFW.glfwGetKeyName(k, 0);
        return n != null ? n.toUpperCase() : "Key" + k;
    }

    static Path cfgPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("lordzinka.properties");
    }

    static void saveConfig() {
        Properties p = new Properties();
        for (Mod m : MODS) {
            p.setProperty(m.name, String.valueOf(m.get.getAsBoolean()));
            for (Setting s : m.settings) {
                try { p.setProperty(m.name + "." + s.name, s.save()); } catch (Exception ignored) { }
            }
        }
        for (Map.Entry<String, int[]> en : GUI_POS.entrySet())
            p.setProperty("pos." + en.getKey(), en.getValue()[0] + "," + en.getValue()[1]);
        try (OutputStream o = Files.newOutputStream(cfgPath())) {
            p.store(o, NAME);
        } catch (Exception ignored) { }
    }

    static void loadConfig() {
        Path f = cfgPath();
        if (!Files.exists(f)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (Exception e) {
            return;
        }
        for (Mod m : MODS) {
            if (p.containsKey(m.name)) m.set.accept(Boolean.parseBoolean(p.getProperty(m.name)));
            for (Setting s : m.settings) {
                String v = p.getProperty(m.name + "." + s.name);
                if (v == null) continue;
                try { s.load(v); } catch (Exception ignored) { }
            }
        }
        for (String k : p.stringPropertyNames()) {
            if (!k.startsWith("pos.")) continue;
            try {
                String[] xy = p.getProperty(k).split(",");
                GUI_POS.put(k.substring(4), new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())});
            } catch (Exception ignored) { }
        }
    }

    /**
     * Polls module binds (including the ClickGUI key) via GLFW, so no keybinding API is needed.
     * A module key toggles the module when no screen is open; the ClickGUI key opens/closes the menu.
     */
    void handleGuiKey(MinecraftClient mc) {
        long h = mc.getWindow().getHandle();
        boolean isGui = mc.currentScreen instanceof ClickGui;
        if (!isGui) listening = null;

        List<Mod> fired = new ArrayList<>();
        for (Mod m : MODS) {
            int k = m.key;
            if (k < GLFW.GLFW_KEY_SPACE || k > GLFW.GLFW_KEY_LAST) continue;
            if (GLFW.glfwGetKey(h, k) == GLFW.GLFW_PRESS && !keyWas[k]) fired.add(m);
        }
        for (Mod m : MODS) {
            int k = m.key;
            if (k < GLFW.GLFW_KEY_SPACE || k > GLFW.GLFW_KEY_LAST) continue;
            keyWas[k] = GLFW.glfwGetKey(h, k) == GLFW.GLFW_PRESS;
        }
        if (listening == null) {
            for (Mod m : fired) {
                if (m == guiMod) {
                    if (mc.currentScreen == null) mc.setScreen(new ClickGui());
                    else if (mc.currentScreen instanceof ClickGui) mc.setScreen(null);
                } else if (mc.currentScreen == null) {
                    boolean v = !m.get.getAsBoolean();
                    m.set.accept(v);
                    mc.player.sendMessage(Text.literal(PREFIX + m.name + ": " + (v ? "ON" : "OFF")), true);
                }
            }
        }
        if (openGuiRequested && mc.currentScreen == null) {
            openGuiRequested = false;
            mc.setScreen(new ClickGui());
        }
        isGui = mc.currentScreen instanceof ClickGui;
        if (guiWasOpen && !isGui) saveConfig();
        guiWasOpen = isGui;
    }

    static int winHeight(String cat, boolean collapsed) {
        int h = 16;
        if (collapsed) return h + 1;
        for (Mod m : MODS) {
            if (!m.cat.equals(cat)) continue;
            h += 14;
            if (m.open) h += 12 * m.rows().size();
        }
        return h + 1;
    }

    /**
     * Meteor-style ClickGUI: draggable category windows with animated module rows.
     * Left click = toggle module, right click = expand settings (every module has a Bind row),
     * drag sliders, right click on a header = collapse the window, drag header to move.
     * Mouse and keyboard are polled through GLFW so no Screen input overrides are needed.
     */
    static final class ClickGui extends Screen {
        boolean wasL, wasR;
        String dragCat;
        int dox, doy;
        IntS dragInt;
        final long openedAt = System.currentTimeMillis();
        final boolean[] capPrev = new boolean[GLFW.GLFW_KEY_LAST + 1];

        ClickGui() { super(Text.literal(NAME)); }

        @Override
        public boolean shouldPause() { return false; }

        @Override
        public boolean shouldCloseOnEsc() { return listening == null; }

        void startListening(BindS b, long h) {
            listening = b;
            for (int k = GLFW.GLFW_KEY_SPACE; k <= GLFW.GLFW_KEY_LAST; k++)
                capPrev[k] = GLFW.glfwGetKey(h, k) == GLFW.GLFW_PRESS;
        }

        void captureKey(long h) {
            for (int k = GLFW.GLFW_KEY_SPACE; k <= GLFW.GLFW_KEY_LAST; k++) {
                boolean d = GLFW.glfwGetKey(h, k) == GLFW.GLFW_PRESS;
                if (d && !capPrev[k] && listening != null) {
                    if (k == GLFW.GLFW_KEY_ESCAPE) {
                        // cancel
                    } else if (k == GLFW.GLFW_KEY_BACKSPACE || k == GLFW.GLFW_KEY_DELETE) {
                        if (listening.canClear) listening.set.accept(0);
                    } else {
                        listening.set.accept(k);
                        keyWas[k] = true; // don't let the same press trigger the freshly bound module
                    }
                    listening = null;
                }
                capPrev[k] = d;
            }
        }

        @Override
        public void render(DrawContext g, int mx, int my, float delta) {
            MinecraftClient mc = MinecraftClient.getInstance();
            var tr = mc.textRenderer;
            long h = mc.getWindow().getHandle();
            boolean l = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            boolean r = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            boolean lc = l && !wasL, rc = r && !wasR;
            wasL = l;
            wasR = r;
            if (!l) { dragCat = null; dragInt = null; }
            if (lc && listening != null) listening = null; // click anywhere cancels; a Bind row click re-arms below
            if (listening != null) captureKey(h);

            float open = Math.min(1f, (System.currentTimeMillis() - openedAt) / 180f);
            int accent = accent();
            int accentRgb = accent & 0xFFFFFF;
            g.fillGradient(0, 0, width, height, ((int) (0xB0 * open) << 24), ((int) (0x60 * open) << 24) | 0x0A0A18);
            g.drawTextWithShadow(tr, NAME, 8, 6, accent);
            g.drawTextWithShadow(tr, "v1.0  |  " + keyName(guiMod.key) + " = menu", 8 + tr.getWidth(NAME) + 6, 6, 0xFF777788);
            String hoverInfo = null;

            List<String> cats = new ArrayList<>();
            for (Mod m : MODS) if (!cats.contains(m.cat)) cats.add(m.cat);
            final int W = 132;

            for (int ci = 0; ci < cats.size(); ci++) {
                String cat = cats.get(ci);
                final int idx = ci;
                int[] p = GUI_POS.computeIfAbsent(cat, k -> new int[]{12 + idx * (W + 10), 22});
                if (cat.equals(dragCat)) { p[0] = mx - dox; p[1] = my - doy; }
                p[0] = Math.max(0, Math.min(width - W, p[0]));     // keep every window reachable
                p[1] = Math.max(0, Math.min(height - 30, p[1]));
                int x = p[0], y = p[1];
                boolean collapsed = COLLAPSED.contains(cat);
                int total = winHeight(cat, collapsed);

                // shadow + body
                g.fill(x - 2, y - 2, x + W + 2, y + total + 2, 0x30000000);
                g.fill(x - 1, y - 1, x + W + 1, y + total + 1, 0x55000000);

                int on0 = 0, all0 = 0;
                for (Mod m : MODS) if (m.cat.equals(cat)) { all0++; if (m.get.getAsBoolean()) on0++; }
                g.fillGradient(x, y, x + W, y + 16, 0xF0262640, 0xF0101020);
                g.fillGradient(x, y + 15, x + W, y + 16, accent, accentRgb);
                g.drawTextWithShadow(tr, cat, x + 6, y + 4, accent);
                String cnt = on0 + "/" + all0 + "  " + (collapsed ? "+" : "-");
                g.drawTextWithShadow(tr, cnt, x + W - 5 - tr.getWidth(cnt), y + 4, 0xFF9999AA);
                if (mx >= x && mx < x + W && my >= y && my < y + 16 && dragCat == null && dragInt == null) {
                    if (lc) {
                        dragCat = cat;
                        dox = mx - x;
                        doy = my - y;
                    }
                    if (rc) {
                        if (collapsed) COLLAPSED.remove(cat); else COLLAPSED.add(cat);
                    }
                }
                int cy = y + 16;
                if (collapsed) { g.fill(x, cy, x + W, cy + 1, accent); continue; }

                for (Mod m : MODS) {
                    if (!m.cat.equals(cat)) continue;
                    boolean on = m.get.getAsBoolean();
                    m.anim += ((on ? 1f : 0f) - m.anim) * 0.3f;
                    boolean hover = mx >= x && mx < x + W && my >= cy && my < cy + 14 && dragCat == null && dragInt == null;
                    int rowRgb = lerpColor(hover ? 0x2A2A3E : 0x161624, accentRgb, 0.45f * m.anim) & 0xFFFFFF;
                    g.fill(x, cy, x + W, cy + 14, 0xE8000000 | rowRgb);
                    if (m.anim > 0.02f) g.fill(x, cy, x + 2, cy + 14, mulAlpha(accent, m.anim));
                    g.drawTextWithShadow(tr, m.name, x + 6, cy + 3, lerpColor(0xAAAABB, 0xFFFFFF, m.anim));
                    String tag = (m.key > 0 ? "[" + keyName(m.key) + "] " : "") + (m.open ? "-" : "+");
                    g.drawTextWithShadow(tr, tag, x + W - 5 - tr.getWidth(tag), cy + 3, 0xFFCCCCDD);
                    if (hover) {
                        hoverInfo = m.desc;
                        if (lc) m.set.accept(!on);
                        if (rc) m.open = !m.open;
                    }
                    cy += 14;
                    if (!m.open) continue;

                    for (Setting s : m.rows()) {
                        boolean sh = mx >= x && mx < x + W && my >= cy && my < cy + 12 && dragCat == null && dragInt == null;
                        g.fill(x, cy, x + W, cy + 12, sh ? 0xF0141424 : 0xF00C0C16);
                        g.fill(x, cy, x + 1, cy + 12, mulAlpha(accent, 0.5));
                        if (s instanceof AdvS ar) {
                            g.drawTextWithShadow(tr, "Advanced", x + 9, cy + 2, 0xFF9999BB);
                            String t = ar.owner.showAdv ? "-" : "+";
                            g.drawTextWithShadow(tr, t, x + W - 8 - tr.getWidth(t), cy + 2, 0xFFCCCCDD);
                            g.fill(x + 6, cy + 11, x + W - 6, cy + 12, 0x22FFFFFF);
                            if (sh && (lc || rc)) ar.owner.showAdv = !ar.owner.showAdv;
                            if (sh) hoverInfo = "Dodatkowe opcje tego modulu";
                        } else if (s instanceof BoolS bs) {
                            boolean v = bs.get.getAsBoolean();
                            g.drawTextWithShadow(tr, s.name, x + 9, cy + 2, 0xFFCCCCDD);
                            int tx = x + W - 22;
                            g.fill(tx, cy + 2, tx + 16, cy + 10, v ? (0xFF000000 | accentRgb) : 0xFF3A3A4C);
                            g.fill(v ? tx + 9 : tx + 1, cy + 3, v ? tx + 15 : tx + 7, cy + 9, 0xFFFFFFFF);
                            if (sh && lc) bs.set.accept(!v);
                        } else if (s instanceof ColorS cs) {
                            int v = Math.max(cs.min, Math.min(cs.max, cs.get.getAsInt()));
                            for (int sx = 0; sx < W; sx += 4)
                                g.fill(x + sx, cy, Math.min(x + W, x + sx + 4), cy + 12, hsv(sx / (float) W, 0.6f, 0.5f));
                            int markX = x + (int) (W * v / 359.0);
                            g.fill(markX - 1, cy, markX + 1, cy + 12, 0xFFFFFFFF);
                            g.drawTextWithShadow(tr, s.name, x + 9, cy + 2, 0xFFFFFFFF);
                            g.fill(x + W - 16, cy + 1, x + W - 3, cy + 11, 0xFF000000);
                            g.fill(x + W - 15, cy + 2, x + W - 4, cy + 10, hueColor(v));
                            if (sh && lc) dragInt = cs;
                            if (dragInt == cs && l) {
                                double f = Math.max(0, Math.min(1, (mx - x) / (double) W));
                                cs.set.accept(cs.min + (int) Math.round(f * (cs.max - cs.min)));
                            }
                        } else if (s instanceof IntS is) {
                            int v = Math.max(is.min, Math.min(is.max, is.get.getAsInt()));
                            double frac = (v - is.min) / (double) Math.max(1, is.max - is.min);
                            int fw = (int) (W * frac);
                            g.fillGradient(x, cy, x + fw, cy + 12, 0x90000000 | accentRgb, 0x40000000 | accentRgb);
                            if (fw > 0) g.fill(x + fw - 1, cy, x + fw + 1, cy + 12, 0xFFFFFFFF);
                            g.drawTextWithShadow(tr, s.name, x + 9, cy + 2, 0xFFCCCCDD);
                            String t = String.valueOf(v);
                            g.drawTextWithShadow(tr, t, x + W - 6 - tr.getWidth(t), cy + 2, 0xFFFFFFFF);
                            if (sh && lc) dragInt = is;
                            if (dragInt == is && l) {
                                double f = Math.max(0, Math.min(1, (mx - x) / (double) W));
                                is.set.accept(is.min + (int) Math.round(f * (is.max - is.min)));
                            }
                        } else if (s instanceof EnumS es) {
                            g.drawTextWithShadow(tr, s.name, x + 9, cy + 2, 0xFFCCCCDD);
                            String t = "< " + es.current() + " >";
                            g.drawTextWithShadow(tr, t, x + W - 6 - tr.getWidth(t), cy + 2, 0xFFFFDD66);
                            if (sh && lc) es.cycle(1);
                            if (sh && rc) es.cycle(-1);
                        } else if (s instanceof BindS bd) {
                            boolean lis = listening == bd;
                            String t = lis ? "press a key..." : "[" + keyName(bd.get.getAsInt()) + "]";
                            g.drawTextWithShadow(tr, "Bind", x + 9, cy + 2, 0xFFCCCCDD);
                            g.drawTextWithShadow(tr, t, x + W - 6 - tr.getWidth(t), cy + 2, lis ? 0xFFFFFF55 : 0xFF88CCFF);
                            if (sh && lc) startListening(bd, h);
                            if (sh && rc && bd.canClear) { bd.set.accept(0); if (listening == bd) listening = null; }
                            if (sh) hoverInfo = bd.canClear ? "Kliknij i nacisnij klawisz. Backspace/Delete lub PPM: usun bind. Esc: anuluj"
                                    : "Kliknij i nacisnij klawisz. Esc: anuluj";
                        }
                        cy += 12;
                    }
                }
                g.fillGradient(x, cy, x + W, cy + 1, accent, accentRgb);
            }

            String help = "LPM: wlacz/wylacz  |  PPM: ustawienia i Bind  |  PPM na naglowku: zwin  |  "
                    + keyName(guiMod.key) + ": zamknij";
            g.fill(0, height - 14, width, height, 0xA0000000);
            g.drawTextWithShadow(tr, help, 8, height - 11, 0xFFAAAAAA);
            if (hoverInfo != null && !hoverInfo.isEmpty()) {
                int tw = tr.getWidth(hoverInfo);
                g.fill(6, height - 29, 12 + tw, height - 16, 0xD0101018);
                g.fill(6, height - 29, 8, height - 16, accent);
                g.drawTextWithShadow(tr, hoverInfo, 10, height - 26, 0xFFFFDD88);
            }
        }
    }
                }
