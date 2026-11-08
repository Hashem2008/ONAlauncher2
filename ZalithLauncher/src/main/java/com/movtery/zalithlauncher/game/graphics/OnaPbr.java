/*
 * ONA Launcher 2 (based on Zalith Launcher 2)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.graphics;

import java.util.Locale;

/**
 * ONA: PBR maps (labPBR 1.3) made from a block texture of the player's own game, for "ONA Mobile
 * PBR". Nothing of Mojang's is shipped: the maps are worked out on the phone.
 * <ul>
 *   <li>normal map {@code _n}: R, G = surface normal (OpenGL, Y up), B = ambient occlusion,
 *       A = height (kept flat: no parallax, it costs too much on phones)</li>
 *   <li>specular map {@code _s}: R = smoothness, G = F0 (230+ = metal), B = porosity /
 *       subsurface, A = emission (0-254, 255 = none)</li>
 * </ul>
 * The height comes from the brightness of the pixels (dark pixel art lines are the gaps), the
 * material (how shiny, metal, glowing) from the texture name. Pure Java, no Android classes, so
 * it is tested on a PC with the real textures.
 */
public final class OnaPbr {
    private OnaPbr() {}

    public static final class Maps {
        public final int[] normal;
        public final int[] specular;
        Maps(int[] normal, int[] specular) {
            this.normal = normal;
            this.specular = specular;
        }
    }

    /** Material of a texture, from its file name */
    static final class Material {
        float bump = 1.4f;        // normal map strength
        float smooth = 0.20f;     // base smoothness
        float smoothFromLight = 0f; // brighter pixels a bit smoother (worn stone, polished faces)
        int f0 = 10;              // non-metal reflectance (0-229)
        int metal = 0;            // 0 = no, else labPBR metal id (230 iron, 231 gold, 234 copper, 255 albedo)
        int porosity = 0;         // 0-64 porosity, 65-255 subsurface (leaves, plants)
        float emissive = 0f;      // 0 = none, else strength
        float emissiveFrom = 0.6f; // brightness where glowing starts
        boolean ore = false;      // ore pixels (coloured, unlike the stone around them) shine
        int oreMetal = 0;
    }

    /** Textures that get maps (not break animation frames, not maps themselves) */
    public static boolean shouldProcess(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return !n.startsWith("destroy_stage") && !n.endsWith("_n") && !n.endsWith("_s")
                && !n.startsWith("water_") && !n.equals("water");
    }

    static Material material(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        Material m = new Material();
        // ---- shape: how deep the pixel art reads ----
        if (has(n, "cobblestone", "bricks", "brick", "tuff", "basalt", "blackstone", "deepslate", "cobbled")) m.bump = 2.6f;
        else if (has(n, "stone", "andesite", "diorite", "granite", "netherrack", "end_stone", "calcite", "dripstone")) m.bump = 2.1f;
        else if (has(n, "log", "stem", "hyphae", "_wood", "bark")) m.bump = 2.0f;
        else if (has(n, "planks", "bookshelf", "crafting_table", "barrel", "chest", "door", "trapdoor")) m.bump = 1.6f;
        else if (has(n, "dirt", "gravel", "sand", "mud", "clay", "soul_soil", "farmland", "podzol", "mycelium", "path")) m.bump = 1.5f;
        else if (has(n, "leaves", "grass", "fern", "flower", "vine", "moss")) m.bump = 1.1f;
        else if (has(n, "wool", "concrete", "glass", "carpet", "ice", "snow", "slime", "honey")) m.bump = 0.5f;

        // ---- surface ----
        if (has(n, "glass")) { m.smooth = 0.95f; m.f0 = 10; }
        else if (has(n, "blue_ice", "packed_ice", "ice")) { m.smooth = 0.88f; m.f0 = 5; }
        else if (has(n, "obsidian")) { m.smooth = 0.78f; m.f0 = 14; }
        else if (has(n, "glazed_terracotta")) { m.smooth = 0.72f; m.f0 = 10; }
        else if (has(n, "concrete") && !has(n, "powder")) { m.smooth = 0.46f; }
        else if (has(n, "terracotta")) { m.smooth = 0.24f; }
        else if (has(n, "quartz", "polished", "smooth_", "_tiles", "purpur")) { m.smooth = 0.55f; m.smoothFromLight = 0.1f; }
        else if (has(n, "prismarine")) { m.smooth = 0.52f; }
        else if (has(n, "amethyst")) { m.smooth = 0.70f; m.f0 = 14; }
        else if (has(n, "diamond_block", "emerald_block")) { m.smooth = 0.86f; m.f0 = 40; }
        else if (has(n, "lapis_block", "redstone_block")) { m.smooth = 0.45f; }
        else if (has(n, "leaves")) { m.smooth = 0.38f; m.porosity = 190; }
        else if (has(n, "grass", "fern", "flower", "vine", "sapling", "bush", "petals", "kelp", "seagrass", "lily")) { m.smooth = 0.30f; m.porosity = 170; }
        else if (has(n, "planks", "log", "stem", "_wood", "door", "trapdoor")) { m.smooth = 0.26f; m.porosity = 30; }
        else if (has(n, "wool", "carpet")) { m.smooth = 0.03f; m.porosity = 60; }
        else if (has(n, "sand", "gravel", "dirt", "soil", "mud", "farmland", "podzol", "mycelium")) { m.smooth = 0.06f; m.porosity = 50; }
        else if (has(n, "snow")) { m.smooth = 0.32f; m.porosity = 120; }
        else if (has(n, "stone", "cobble", "brick", "andesite", "diorite", "granite", "deepslate", "tuff", "basalt", "netherrack", "end_stone")) { m.smooth = 0.16f; m.smoothFromLight = 0.14f; m.porosity = 25; }

        // ---- metals ----
        boolean oxidized = has(n, "oxidized");
        boolean weathered = has(n, "weathered");
        boolean exposed = has(n, "exposed");
        if (has(n, "copper") && !has(n, "ore") && !has(n, "raw_")) {
            if (oxidized) { m.smooth = 0.30f; m.metal = 0; m.f0 = 10; }
            else { m.metal = 234; m.smooth = weathered ? 0.42f : exposed ? 0.55f : 0.68f; }
        } else if (has(n, "gold_block") || n.equals("gold")) { m.metal = 231; m.smooth = 0.80f; }
        else if (has(n, "iron_block", "iron_bars", "iron_door", "iron_trapdoor", "anvil", "chain", "cauldron",
                "hopper", "lightning_rod", "heavy_weighted", "light_weighted", "bell", "iron_chain") && !has(n, "command")) { m.metal = 230; m.smooth = 0.72f; }
        else if (has(n, "netherite_block")) { m.metal = 255; m.smooth = 0.66f; }
        else if (has(n, "lantern") && !has(n, "jack", "sea_lantern")) { m.metal = 230; m.smooth = 0.55f; }

        // ---- ores: the coloured specks shine like what they are ----
        if (has(n, "_ore") || n.equals("ancient_debris") || has(n, "raw_")) {
            m.ore = true;
            if (has(n, "gold")) m.oreMetal = 231;
            else if (has(n, "iron")) m.oreMetal = 230;
            else if (has(n, "copper")) m.oreMetal = 234;
            else m.oreMetal = 0; // diamond, emerald, lapis, redstone, coal, quartz: shiny, not metal
        }

        // ---- light sources ----
        if (has(n, "lava")) { m.emissive = 1.0f; m.emissiveFrom = 0.0f; m.smooth = 0.3f; }
        else if (has(n, "glowstone", "shroomlight", "froglight", "sea_lantern", "redstone_lamp_on", "beacon", "end_rod", "glow_lichen"))
            { m.emissive = 0.9f; m.emissiveFrom = 0.35f; }
        else if (has(n, "magma")) { m.emissive = 0.85f; m.emissiveFrom = 0.45f; }
        else if (has(n, "fire", "torch", "lantern", "campfire", "candle_lit", "jack_o_lantern", "respawn_anchor_top", "nether_portal", "crying_obsidian", "sculk_catalyst")
                && !has(n, "coral", "torchflower", "pitcher", "fire_charge"))
            { m.emissive = 1.0f; m.emissiveFrom = 0.62f; }
        return m;
    }

    private static boolean has(String n, String... parts) {
        for (String p : parts) if (n.contains(p)) return true;
        return false;
    }

    /**
     * @param argb non-premultiplied ARGB pixels (as Android's Bitmap.getPixels and Java's
     *             BufferedImage.getRGB give them)
     */
    public static Maps generate(String name, int[] argb, int width, int height) {
        Material m = material(name);
        int frame = (height > width && height % width == 0) ? width : height; // animated strips
        int[] normal = new int[argb.length];
        int[] specular = new int[argb.length];
        float[] h = new float[argb.length];
        float[] sat = new float[argb.length];
        float[] lum = new float[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            int a = (c >>> 24) & 0xFF;
            float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
            float l = 0.299f * r + 0.587f * g + 0.114f * b;
            float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            lum[i] = l;
            sat[i] = max <= 0f ? 0f : (max - min) / max;
            h[i] = a < 26 ? 0f : l;
        }
        for (int top = 0; top + frame <= height; top += frame) {
            for (int y = 0; y < frame; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (top + y) * width + x;
                    int a = (argb[i] >>> 24) & 0xFF;
                    if (a < 26) {
                        normal[i] = 0xFF8080FF;
                        specular[i] = 0xFF000000;
                        continue;
                    }
                    // Sobel on the brightness, wrapping inside the frame (textures tile)
                    float tl = at(h, width, frame, top, x - 1, y - 1), t = at(h, width, frame, top, x, y - 1), tr = at(h, width, frame, top, x + 1, y - 1);
                    float l = at(h, width, frame, top, x - 1, y), r = at(h, width, frame, top, x + 1, y);
                    float bl = at(h, width, frame, top, x - 1, y + 1), b = at(h, width, frame, top, x, y + 1), br = at(h, width, frame, top, x + 1, y + 1);
                    float dx = (tr + 2 * r + br) - (tl + 2 * l + bl);
                    float dy = (bl + 2 * b + br) - (tl + 2 * t + tr);
                    float s = m.bump * 0.25f;
                    float nx = -dx * s, ny = dy * s, nz = 1f;
                    float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    nx /= len; ny /= len;
                    // cavity: pixels darker than their neighbours sit deeper, they get less sky
                    float around = (tl + t + tr + l + r + bl + b + br) / 8f;
                    float ao = clamp(1f - (around - h[i]) * 1.6f, 0.55f, 1f);
                    normal[i] = argbOf(255, enc(nx), enc(ny), Math.round(ao * 255f));

                    // ---- specular ----
                    float smooth = m.smooth + (lum[i] - 0.5f) * m.smoothFromLight;
                    int f0 = m.metal != 0 ? m.metal : m.f0;
                    if (m.ore) {
                        boolean speck = sat[i] > 0.28f || lum[i] > 0.78f;
                        if (speck) {
                            smooth = 0.82f;
                            f0 = m.oreMetal != 0 ? m.oreMetal : 50;
                        }
                    }
                    int emission = 255;
                    if (m.emissive > 0f) {
                        float e = (lum[i] - m.emissiveFrom) / Math.max(1f - m.emissiveFrom, 0.05f);
                        e = clamp(e, 0f, 1f) * m.emissive;
                        if (e > 0.02f) emission = Math.min(254, Math.round(e * 254f));
                    }
                    specular[i] = argbOf(emission, Math.round(clamp(smooth, 0f, 1f) * 255f), f0, m.porosity);
                }
            }
        }
        return new Maps(normal, specular);
    }

    private static float at(float[] h, int width, int frame, int top, int x, int y) {
        x = ((x % width) + width) % width;
        y = ((y % frame) + frame) % frame;
        return h[(top + y) * width + x];
    }

    private static int enc(float v) {
        return Math.round(clamp(v * 0.5f + 0.5f, 0f, 1f) * 255f);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int argbOf(int a, int r, int g, int b) {
        return (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
    }
}
