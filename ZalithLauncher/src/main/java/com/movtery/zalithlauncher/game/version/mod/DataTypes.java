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

package com.movtery.zalithlauncher.game.version.mod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * ONA: world generation types a data mod uses from another mod.
 * Lithosphere 1.9 (tagged for 1.21.8) uses "lithostitched:fast_noise", which the newest
 * Lithostitched for 1.21.8 (1.5.0+beta5) does not have: the game stops at "Registry Loading"
 * (Unknown registry key ... density_function_type: lithostitched:fast_noise). Found before the
 * launch, the fixer then swaps in a provider that has the type, or a build of the data mod that
 * only uses what the provider has.
 * Only worldgen / dimension files are read: those registries stop the game when one entry fails.
 */
final class DataTypes {
    private DataTypes() {}

    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([a-z0-9_.-]+):([a-z0-9_/.-]+)\"");
    /** Namespaces of the game and the loaders: never a mod that could be too old */
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            "minecraft", "c", "forge", "neoforge", "fabric", "quilt", "common"));
    private static final long MAX_FILE = 4L * 1024 * 1024;

    private static final Map<String, Object[]> USED_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Object[]> TEXT_CACHE = new ConcurrentHashMap<>();

    static boolean isWorldData(String entry) {
        if (!entry.startsWith("data/") || !entry.endsWith(".json")) return false;
        return entry.contains("/worldgen/") || entry.contains("/dimension/") || entry.contains("/dimension_type/");
    }

    /**
     * Types of other namespaces the jar's world data names ("namespace" -> names), its own data
     * namespaces left out. Empty when it has no world data or cannot be read.
     */
    static Map<String, Set<String>> usedTypes(File jar) {
        String key = jar.getAbsolutePath();
        Object[] cached = USED_CACHE.get(key);
        if (cached != null && (long) cached[0] == jar.length() && (long) cached[1] == jar.lastModified()) {
            //noinspection unchecked
            return (Map<String, Set<String>>) cached[2];
        }
        Map<String, Set<String>> used = readUsed(jar);
        USED_CACHE.put(key, new Object[]{jar.length(), jar.lastModified(), used});
        return used;
    }

    private static Map<String, Set<String>> readUsed(File jar) {
        Map<String, Set<String>> used = new TreeMap<>();
        Set<String> own = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("data/")) {
                    int slash = name.indexOf('/', 5);
                    if (slash > 5) own.add(name.substring(5, slash));
                }
                if (entry.isDirectory() || !isWorldData(name) || entry.getSize() > MAX_FILE) continue;
                String text;
                try (InputStream in = zip.getInputStream(entry)) {
                    text = new String(readAll(in, MAX_FILE), StandardCharsets.UTF_8);
                }
                Matcher m = TYPE.matcher(text);
                while (m.find()) {
                    String namespace = m.group(1);
                    if (SKIP.contains(namespace)) continue;
                    used.computeIfAbsent(namespace, k -> new TreeSet<>()).add(m.group(2));
                }
            }
        } catch (IOException | RuntimeException e) {
            return Collections.emptyMap();
        }
        used.keySet().removeAll(own);
        return used;
    }

    /**
     * True when [name] appears anywhere in the provider jar (its classes, where codecs are
     * registered by name, or its own data and nested jars), exactly or in upper case (enum
     * names). True as well when the jar cannot be read: only a clear "not there" is acted on.
     */
    static boolean has(File provider, String name) {
        Set<String> all = contents(provider);
        if (all == null) return true;
        return all.contains(name) || all.contains(name.toUpperCase(Locale.ROOT));
    }

    /** Every identifier-like word in the jar, read once per jar version */
    private static Set<String> contents(File jar) {
        String key = jar.getAbsolutePath();
        Object[] cached = TEXT_CACHE.get(key);
        if (cached != null && (long) cached[0] == jar.length() && (long) cached[1] == jar.lastModified()) {
            //noinspection unchecked
            return (Set<String>) cached[2];
        }
        Set<String> words = new HashSet<>();
        try (InputStream in = new FileInputStream(jar)) {
            collectWords(in, words, 0);
        } catch (IOException | RuntimeException e) {
            words = null;
        }
        TEXT_CACHE.put(key, new Object[]{jar.length(), jar.lastModified(), words});
        // a jar replaced under the same name gets a new entry; keep the cache small
        if (TEXT_CACHE.size() > 64) TEXT_CACHE.clear();
        return words;
    }

    private static void collectWords(InputStream in, Set<String> words, int depth) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            String name = entry.getName();
            addWords(name, words);
            if (entry.isDirectory()) continue;
            if (name.endsWith(".jar")) {
                if (depth < 2) collectWords(new ByteArrayInputStream(readAll(zip, 64L * 1024 * 1024)), words, depth + 1);
                continue;
            }
            if (name.endsWith(".png") || name.endsWith(".ogg") || name.endsWith(".nbt")) continue;
            addWords(new String(readAll(zip, 16L * 1024 * 1024), StandardCharsets.ISO_8859_1), words);
        }
    }

    /** Runs of [a-zA-Z0-9_] (class constant pools keep strings as plain bytes) */
    private static void addWords(String text, Set<String> words) {
        int start = -1;
        int length = text.length();
        for (int i = 0; i <= length; i++) {
            char c = i < length ? text.charAt(i) : ' ';
            boolean word = c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
            if (word) {
                if (start < 0) start = i;
            } else if (start >= 0) {
                if (i - start >= 2 && i - start <= 64) words.add(text.substring(start, i));
                start = -1;
            }
        }
    }

    /** The names of [names] the provider does not have, in order */
    static List<String> missing(File provider, Set<String> names) {
        List<String> missing = new java.util.ArrayList<>();
        for (String name : names) {
            // a path like "density_function/fast_noise": its last part is the registered word
            String word = name.substring(name.lastIndexOf('/') + 1);
            if (word.contains(".") || word.contains("-")) continue;
            if (!has(provider, word)) missing.add(name);
        }
        return missing;
    }

    /** Uses of [namespace] in [jar] that [provider] does not have */
    static List<String> missingIn(File jar, String namespace, File provider) {
        Set<String> names = usedTypes(jar).get(namespace);
        if (names == null || names.isEmpty()) return Collections.emptyList();
        return missing(provider, names);
    }

    private static byte[] readAll(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        long total = 0;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > max) throw new IOException("Entry too large");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** For tests and logs: a map of the uses as text */
    static String describe(Map<String, Set<String>> used) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Set<String>> entry : new HashMap<>(used).entrySet()) {
            if (text.length() > 0) text.append("; ");
            text.append(entry.getKey()).append(": ").append(String.join(",", entry.getValue()));
        }
        return text.toString();
    }
}
