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
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
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
    static final Set<Block> espBlocks = new HashSet<>();

    // ------------------------------------------------------------------ data
    static final class ChunkInfo {
        int cx, cz, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        float score;
        boolean notified;
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
        for (Block b : new Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST,
                Blocks.SPAWNER, Blocks.BEACON, Blocks.ENCHANTING_TABLE}) espBlocks.add(b);
        for (Block b : Registries.BLOCK)
            if (Registries.BLOCK.getId(b).getPath().endsWith("shulker_box")) espBlocks.add(b);

        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> queue.add(chunk.getPos().toLong()));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            data.clear(); queue.clear(); geodes = new ArrayList<>(); nearby.clear();
            inRange.clear(); vanishWarned.clear(); spectatorWarned.clear(); vanishNow.clear();
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
                Identifier.of("lordzinka", "overlay"), this::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
    }

    // ------------------------------------------------------------------ tick
    void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        tickCounter++;

        scanQueue(mc);
        if (radarOn) updateRadar(mc); else { nearby.clear(); inRange.clear(); }
        if (vanishOn && tickCounter % 20 == 0) updateVanish(mc);
        if (amethystOn && tickCounter % 40 == 0) computeGeodes(mc);
        if (totemOn) autoTotem(mc);
    }

    // ---- chunk scanning
    void scanQueue(MinecraftClient mc) {
        if (tickCounter % 100 == 0) {
            ChunkPos pc = mc.player.getChunkPos();
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++)
                    queue.add(ChunkPos.toLong(pc.x + dx, pc.z + dz));
        }
        Iterator<Long> it = queue.iterator();
        int budget = 2;
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
                            if (b == Blocks.AMETHYST_CLUSTER) { if (info.amClusters.size() < 3000) info.amClusters.add(p); }
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
        }
        if (info.score < threshold) info.notified = false;
        map.put(cp.toLong(), info);
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
            if (inRange.add(p.getUuid())) {
                chat(mc, String.format("%s entered %dm range (%d away) at %d, %d, %d", p.getName().getString(),
                        alertRadius, (int) d, p.getBlockX(), p.getBlockY(), p.getBlockZ()));
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.5f);
            }
        }
        for (Iterator<UUID> it = inRange.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (!now.contains(id)) {
                it.remove();
                chat(mc, "A player left your " + alertRadius + "m range");
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
            if (nh.getPlayerListEntry(p.getUuid()) == null) {
                vanishNow.put(p.getUuid(), p.getName().getString());
                if (vanishWarned.add(p.getUuid()))
                    chat(mc, "Possible VANISHED player (no tab entry): " + p.getName().getString()
                            + " at " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ());
            }
        }
        for (PlayerListEntry e : nh.getPlayerList()) {
            UUID id = e.getProfile().id();
            if (mc.player.getUuid().equals(id)) continue;
            if (e.getGameMode() == GameMode.SPECTATOR) {
                vanishNow.put(id, e.getProfile().name() + " (spectator)");
                if (spectatorWarned.add(id))
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
        // screen-handler slots: 9-35 main inventory, 36-44 hotbar
        for (int i = 9; i <= 44; i++) {
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
        ClientPlayerEntity p = mc.player;
        Vec3d lp = p.getLerpedPos(tp);
        ex = lp.x;
        ey = lp.y + (p.getEyeY() - p.getY());
        ez = lp.z;
        double yaw = Math.toRadians(p.getYaw(tp)), pitch = Math.toRadians(p.getPitch(tp));
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

    static double[] box2(DrawContext g, Box b, int col) {
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
        line2(g, sw / 2, sh, s[0], s[1], col);
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
        if (blocksOn) renderBlocks(g, mc, map);
        if (amethystOn) renderGeodes(g, tr);
        if (espOn) renderEntities(g, mc, tp);
        if (nametagsOn) renderNametags(g, mc, tp);
        if (hudOn) renderHud(g, mc);
    }

    void renderChunks(DrawContext g, MinecraftClient mc, Map<Long, ChunkInfo> map) {
        var tr = mc.textRenderer;
        List<ChunkInfo> flagged = new ArrayList<>();
        for (ChunkInfo ci : map.values()) {
            double dx = ci.cx * 16 + 8 - ex, dz = ci.cz * 16 + 8 - ez;
            if (ci.score >= threshold && dx * dx + dz * dz < 512 * 512) flagged.add(ci);
        }
        flagged.sort(Comparator.comparingDouble(ci -> Math.pow(ci.cx * 16 + 8 - ex, 2) + Math.pow(ci.cz * 16 + 8 - ez, 2)));
        for (int i = 0; i < Math.min(40, flagged.size()); i++) {
            ChunkInfo ci = flagged.get(i);
            float ratio = Math.min(1f, ci.score / (threshold * 3f));
            int col = 0xFF000000 | 0xFF0000 | ((int) (220 - 180 * ratio) << 8);
            double x0 = ci.cx * 16, z0 = ci.cz * 16;
            box3(g, x0, ci.minY, z0, x0 + 16, ci.maxY + 1, z0 + 16, col);
            if (tracers) tracerTo(g, x0 + 8, (ci.minY + ci.maxY + 1) / 2.0, z0 + 8, col);
            double[] c = cam(x0 + 8, ci.maxY + 1.5, z0 + 8);
            if (c[2] > NEAR) {
                double[] s = scr(c);
                String label = String.format("Chunk %d,%d  score %.0f", ci.cx, ci.cz, ci.score);
                g.drawTextWithShadow(tr, label, (int) s[0] - tr.getWidth(label) / 2, (int) s[1], col);
            }
        }
    }

    void renderBlocks(DrawContext g, MinecraftClient mc, Map<Long, ChunkInfo> map) {
        int drawn = 0;
        double rangeSq = (double) blockRange * blockRange;
        for (ChunkInfo ci : map.values()) {
            double cdx = ci.cx * 16 + 8 - ex, cdz = ci.cz * 16 + 8 - ez;
            if (cdx * cdx + cdz * cdz > (blockRange + 24.0) * (blockRange + 24.0)) continue;
            for (int i = 0; i < ci.esp.size() && drawn < 800; i++) {
                BlockPos pos = BlockPos.fromLong(ci.esp.getLong(i));
                double dx = pos.getX() + 0.5 - ex, dy = pos.getY() + 0.5 - ey, dz = pos.getZ() + 0.5 - ez;
                if (dx * dx + dy * dy + dz * dz > rangeSq) continue;
                Block block = mc.world.getBlockState(pos).getBlock();
                boolean loaded = mc.world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4) != null;
                if (loaded && !espBlocks.contains(block)) continue;
                int col = blockColor(loaded ? block : Blocks.CHEST);
                if (box2(g, new Box(pos), col) != null) drawn++;
            }
        }
    }

    void renderGeodes(DrawContext g, net.minecraft.client.font.TextRenderer tr) {
        int shown = 0;
        for (Geode ge : geodes) {
            if (shown >= 12) break;
            double dx = ge.cx() - ex, dz = ge.cz() - ez;
            if (dx * dx + dz * dz > 256 * 256) continue;
            shown++;
            int col = riskColor(ge.likelihood);
            box3(g, ge.minX, ge.minY, ge.minZ, ge.maxX + 1, ge.maxY + 1, ge.maxZ + 1, col);
            double[] c = cam(ge.cx(), ge.maxY + 1.5, ge.cz());
            if (c[2] > NEAR) {
                double[] s = scr(c);
                String l1 = "Amethyst Geode";
                String l2 = String.format("clusters %d | budding %d | base %.0f%%", ge.clusters, ge.budding, ge.likelihood);
                g.drawTextWithShadow(tr, l1, (int) s[0] - tr.getWidth(l1) / 2, (int) s[1] - 10, 0xFFD58CFF);
                g.drawTextWithShadow(tr, l2, (int) s[0] - tr.getWidth(l2) / 2, (int) s[1], col);
            }
        }
    }

    void renderEntities(DrawContext g, MinecraftClient mc, float tp) {
        var tr = mc.textRenderer;
        double rangeSq = (double) entityRange * entityRange;
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player) continue;
            int col;
            if (e instanceof PlayerEntity) { if (!fPlayers) continue; col = 0xFFFF3333; }
            else if (e instanceof HostileEntity) { if (!fHostile) continue; col = 0xFFFF8800; }
            else if (e instanceof PassiveEntity) { if (!fPassive) continue; col = 0xFF44DD44; }
            else if (e instanceof ItemEntity) { if (!fItems) continue; col = 0xFF33CCFF; }
            else {
                String id = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
                if (!fVehicles || !(id.contains("minecart") || id.contains("boat") || id.contains("raft"))) continue;
                col = 0xFFFFDD33;
            }
            Vec3d lp = e.getLerpedPos(tp);
            double dx = lp.x - ex, dy = lp.y - ey, dz = lp.z - ez;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > rangeSq) continue;
            Box b = e.getBoundingBox().offset(lp.x - e.getX(), lp.y - e.getY(), lp.z - e.getZ());
            double[] r = box2(g, b, col);
            if (tracers) tracerTo(g, lp.x, lp.y + e.getHeight() / 2, lp.z, col);
            if (r != null && !(e instanceof PlayerEntity && nametagsOn)) {
                String label = e.getName().getString() + " [" + (int) Math.sqrt(distSq) + "m]";
                g.drawTextWithShadow(tr, label, (int) ((r[0] + r[2]) / 2) - tr.getWidth(label) / 2, (int) r[1] - 10, col);
            }
        }
    }

    void renderNametags(DrawContext g, MinecraftClient mc, float tp) {
        var tr = mc.textRenderer;
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            Vec3d lp = p.getLerpedPos(tp);
            double dist = Math.sqrt(Math.pow(lp.x - ex, 2) + Math.pow(lp.y - ey, 2) + Math.pow(lp.z - ez, 2));
            if (dist > 160) continue;
            double[] c = cam(lp.x, lp.y + p.getHeight() + 0.75, lp.z);
            if (c[2] < NEAR) continue;
            double[] s = scr(c);
            float hp = p.getHealth() + p.getAbsorptionAmount();
            int hpCol = hp >= 15 ? 0xFF55FF55 : hp >= 8 ? 0xFFFFDD44 : 0xFFFF5555;
            String name = p.getName().getString();
            String stats = String.format(" %dhp  %dm", (int) Math.ceil(hp), (int) dist);
            int w = tr.getWidth(name) + tr.getWidth(stats);
            int x = (int) s[0] - w / 2, y = (int) s[1] - 22;

            // armor durability line
            StringBuilder armor = new StringBuilder();
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack st = p.getEquippedStack(slot);
                armor.append(st.isEmpty() ? "-" : st.getMaxDamage() <= 0 ? "#"
                        : String.valueOf(Math.max(0, 100 - st.getDamage() * 100 / st.getMaxDamage()) / 10)).append(' ');
            }
            ItemStack held = p.getMainHandStack();
            String l2 = "[" + armor.toString().trim() + "]  " + (held.isEmpty() ? "empty hand" : held.getName().getString());
            int w2 = tr.getWidth(l2);
            int bw = Math.max(w, w2);
            g.fill((int) s[0] - bw / 2 - 3, y - 2, (int) s[0] + bw / 2 + 3, y + 21, 0x99101018);
            g.fill((int) s[0] - bw / 2 - 3, y - 2, (int) s[0] + bw / 2 + 3, y - 1, hpCol);
            g.drawTextWithShadow(tr, name, x, y, 0xFFFFFFFF);
            g.drawTextWithShadow(tr, stats, x + tr.getWidth(name), y, hpCol);
            g.drawTextWithShadow(tr, l2, (int) s[0] - w2 / 2, y + 10, 0xFFBBBBBB);
        }
    }

    // ---- HUD
    void renderHud(DrawContext g, MinecraftClient mc) {
        var tr = mc.textRenderer;
        int accent = hsv((System.currentTimeMillis() % 6000) / 6000f, 0.55f, 1f);

        // --- left info panel
        List<String> lines = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        BlockPos bp = mc.player.getBlockPos();
        lines.add(String.format("XYZ  %d  %d  %d", bp.getX(), bp.getY(), bp.getZ())); cols.add(0xFFFFFFFF);
        lines.add(String.format("%s  |  %s  |  %d FPS", mc.player.getHorizontalFacing().asString(),
                dimKey(mc).replace("minecraft:", ""), mc.getCurrentFps())); cols.add(0xFFBBBBCC);
        int flagged = 0;
        for (ChunkInfo ci : chunks(mc).values()) if (ci.score >= threshold) flagged++;
        lines.add("Suspicious chunks: " + flagged); cols.add(flagged > 0 ? 0xFFFF7755 : 0xFFBBBBCC);
        if (amethystOn) {
            double best = 0;
            for (Geode ge : geodes) best = Math.max(best, ge.likelihood);
            lines.add(geodes.isEmpty() ? "Geodes: none" : String.format("Geodes: %d (best %.0f%%)", geodes.size(), best));
            cols.add(geodes.isEmpty() ? 0xFFBBBBCC : riskColor(best));
        }
        if (totemOn) { lines.add("Totems: " + countTotems(mc)); cols.add(0xFFFFD84A); }
        if (radarOn) {
            lines.add("Players <" + alertRadius + "m: " + nearby.size()); cols.add(nearby.isEmpty() ? 0xFFBBBBCC : 0xFFFF5555);
            for (int i = 0; i < Math.min(5, nearby.size()); i++) {
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
        int w = tr.getWidth(NAME) + 12;
        for (String l : lines) w = Math.max(w, tr.getWidth(l) + 12);
        int h = 16 + lines.size() * 10 + 4;
        int x = 4, y = 4;
        g.fill(x, y, x + w, y + h, 0xB0101018);
        g.fill(x, y, x + 2, y + h, accent);
        g.fill(x + 2, y + 13, x + w, y + 14, 0x40FFFFFF);
        g.drawTextWithShadow(tr, NAME, x + 7, y + 3, accent);
        for (int i = 0; i < lines.size(); i++)
            g.drawTextWithShadow(tr, lines.get(i), x + 7, y + 18 + i * 10, cols.get(i));

        // --- right module list
        List<String> mods = new ArrayList<>();
        if (chunksOn) mods.add("Chunk Finder");
        if (blocksOn) mods.add("Block ESP");
        if (amethystOn) mods.add("Amethyst ESP");
        if (espOn) mods.add("Entity ESP");
        if (radarOn) mods.add("Player Radar");
        if (nametagsOn) mods.add("Nametags");
        if (vanishOn) mods.add("Vanish Detect");
        if (totemOn) mods.add("Auto Totem");
        if (tracers) mods.add("Tracers");
        mods.sort((a, b) -> tr.getWidth(b) - tr.getWidth(a));
        int ry = 4;
        for (String m : mods) {
            int mw = tr.getWidth(m);
            int rx0 = (int) sw - mw - 9;
            g.fill(rx0 - 2, ry, (int) sw - 3, ry + 11, 0x90101018);
            g.fill((int) sw - 3, ry, (int) sw - 1, ry + 11, accent);
            g.drawTextWithShadow(tr, m, rx0, ry + 2, accent);
            ry += 11;
        }

        // --- player alert banner
        if (radarOn && !nearby.isEmpty()) {
            StringBuilder sb = new StringBuilder("[!] PLAYER NEARBY: ");
            for (int i = 0; i < Math.min(3, nearby.size()); i++) {
                if (i > 0) sb.append(", ");
                sb.append(nearby.get(i).name()).append(" (").append((int) nearby.get(i).dist()).append("m)");
            }
            String t = sb.toString();
            int tw = tr.getWidth(t);
            boolean pulse = (System.currentTimeMillis() / 400) % 2 == 0;
            int bx = (int) sw / 2 - tw / 2;
            g.fill(bx - 8, 18, bx + tw + 8, 34, pulse ? 0xC0801010 : 0xC0501010);
            g.fill(bx - 8, 18, bx + tw + 8, 19, 0xFFFF4444);
            g.drawTextWithShadow(tr, t, bx, 23, 0xFFFFFFFF);
        }
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
            for (ChunkInfo ci : chunks(mc).values()) if (ci.score >= threshold) l.add(ci);
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
}
