package com.movtery.zalithlauncher.game.version.mod;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Ported from ONAlauncher.
 * Reads mod jar metadata (Forge/NeoForge mods.toml, Fabric fabric.mod.json) to tell whether mods
 * fit a profile, finds what is broken in a mods folder and fixes it from Modrinth.
 */
public class ModCompat {
    private static final String TAG = "ModCompat";
    private static final String API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "ONALauncher2 (github.com/Hashem2008/ONAlauncher2)";
    private static final Gson GSON = new Gson();
    private static final int MAX_CANDIDATES = 6;

    /** What a profile runs: the vanilla version, the mod loader and its version */
    public static class Environment {
        public String gameVersion;
        @Nullable public String loader;
        @Nullable public String loaderVersion;
        /** Sinytra Connector is installed: Fabric mods load on (Neo)Forge too */
        public boolean connector;
    }

    public static class Dependency {
        public String modId, range;
        boolean fabricStyle;
        /** Metadata it came from: fabric, forge or neoforge (multi-loader jars carry several) */
        String source;
    }

    public static class ModInfo {
        public File file;
        public String name;
        /** Every mod id this jar provides, nested jars included */
        final Set<String> providedIds = new HashSet<>();
        final List<Dependency> dependencies = new ArrayList<>();
        /** Fabric "breaks": mods (and versions) this one does not load with */
        final List<Dependency> breaks = new ArrayList<>();
        /** Version of each provided mod id (nested jars included) when the metadata gives one */
        final Map<String, String> versions = new HashMap<>();
        boolean hasForgeToml, hasNeoforgeToml, hasFabricJson;
        @Nullable String forgeLoaderRange;
    }

    public static final int PROBLEM_INCOMPATIBLE = 0;
    public static final int PROBLEM_MISSING = 1;
    public static final int PROBLEM_WRONG_LOADER = 2;
    /** A required mod is there, but in a version the mod that needs it does not accept */
    public static final int PROBLEM_WRONG_VERSION = 3;
    /**
     * Another mod is there in a version this one "breaks" (Sodium 0.9.3-beta.4 breaks Iris
     * 1.11.7 and older): [file] is the mod that breaks it, [providerFile] the broken one
     */
    public static final int PROBLEM_BREAKS = 4;
    /**
     * ONA: a data mod's world generation uses types the installed provider does not have
     * (Lithosphere 1.9 -> lithostitched:fast_noise, Lithostitched 1.5.0+beta5): [requiredId] is
     * the provider's mod id, [requiredRange] the missing type names (comma separated),
     * [providerFile] the provider jar, [actualVersion] its version
     */
    public static final int PROBLEM_MISSING_TYPE = 5;

    public static class Problem {
        public int kind;
        public File file;
        /** Display name of the mod that has the problem */
        public String modName;
        /** What it needs: a mod id, "minecraft" or a loader */
        public String requiredId;
        public String requiredRange;
        @Nullable public String actualVersion;
        /** PROBLEM_WRONG_VERSION: the jar that provides the required mod in the wrong version */
        @Nullable public File providerFile;
    }

    // ---- Environment ----

    /** @param loader forge, neoforge, fabric, quilt or null for vanilla */
    public static Environment of(String gameVersion, @Nullable String loader, @Nullable String loaderVersion) {
        Environment environment = new Environment();
        environment.gameVersion = gameVersion;
        environment.loader = loader;
        environment.loaderVersion = loaderVersion;
        return environment;
    }

    @Nullable
    public static String detectLoader(String versionId) {
        String lower = versionId.toLowerCase(Locale.ROOT);
        if (lower.contains("neoforge")) return "neoforge";
        if (lower.contains("quilt")) return "quilt";
        if (lower.contains("fabric")) return "fabric";
        if (lower.contains("forge")) return "forge";
        return null;
    }

    /** Mod ids the game itself provides for this loader */
    private static Set<String> builtinIds(Environment environment) {
        Set<String> ids = new HashSet<>(Arrays.asList("minecraft", "java", "mixinextras"));
        if (environment.loader == null) return ids;
        switch (environment.loader) {
            case "forge": ids.addAll(Arrays.asList("forge", "javafml", "lowcodefml")); break;
            case "neoforge": ids.addAll(Arrays.asList("neoforge", "javafml", "lowcodefml", "fml_loader")); break;
            case "quilt": ids.addAll(Arrays.asList("quilt_loader", "fabricloader", "fabric-loader")); break;
            default: ids.addAll(Arrays.asList("fabricloader", "fabric-loader"));
        }
        return ids;
    }

    // ---- Reading jars ----

    /** ONA: jars do not change between checks, keep what was read keyed by size and time */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object[]> READ_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object NO_INFO = new Object();

    @Nullable
    public static ModInfo read(File jar) {
        String key = jar.getAbsolutePath();
        long length = jar.length();
        long modified = jar.lastModified();
        Object[] cached = READ_CACHE.get(key);
        if (cached != null && (long) cached[0] == length && (long) cached[1] == modified) {
            return cached[2] == NO_INFO ? null : (ModInfo) cached[2];
        }
        ModInfo info = readUncached(jar);
        READ_CACHE.put(key, new Object[]{length, modified, info == null ? NO_INFO : info});
        return info;
    }

    @Nullable
    private static ModInfo readUncached(File jar) {
        ModInfo info = new ModInfo();
        info.file = jar;
        try (InputStream in = new FileInputStream(jar)) {
            scan(in, info, 0);
        } catch (Exception e) {
            Log.w(TAG, "Could not read " + jar, e);
            return null;
        }
        if (info.providedIds.isEmpty()) return null;
        if (info.name == null) info.name = jar.getName();
        return info;
    }

    private static void scan(InputStream in, ModInfo info, int depth) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            String name = entry.getName();
            if (name.equals("META-INF/mods.toml") || name.equals("META-INF/neoforge.mods.toml")) {
                boolean neoforge = name.startsWith("META-INF/neoforge");
                if (depth == 0) {
                    if (neoforge) info.hasNeoforgeToml = true;
                    else info.hasForgeToml = true;
                }
                parseToml(new String(readAll(zip), StandardCharsets.UTF_8), info, depth == 0, neoforge);
            } else if (name.equals("fabric.mod.json")) {
                if (depth == 0) info.hasFabricJson = true;
                parseFabric(new String(readAll(zip), StandardCharsets.UTF_8), info, depth == 0);
            } else if (depth < 2 && name.endsWith(".jar")
                    && (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))) {
                scan(new ByteArrayInputStream(readAll(zip)), info, depth + 1);
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    /** Just enough TOML for mods.toml: top level keys, [[mods]] and [[dependencies.x]] tables */
    private static void parseToml(String text, ModInfo info, boolean outer, boolean neoforge) {
        String section = "";
        Map<String, String> table = null;
        String multilineEnd = null;
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (multilineEnd != null) {
                if (line.contains(multilineEnd)) multilineEnd = null;
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                flushTomlTable(section, table, info, outer, neoforge);
                boolean arrayTable = line.startsWith("[[");
                section = line.replace("[", "").replace("]", "").trim();
                table = arrayTable ? new HashMap<>() : null;
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (value.startsWith("'''") || value.startsWith("\"\"\"")) {
                String quote = value.substring(0, 3);
                if (value.length() < 6 || !value.substring(3).contains(quote)) multilineEnd = quote;
                continue;
            }
            value = unquoteToml(value);
            if (table != null) table.put(key, value);
            else if (section.isEmpty() && outer && key.equals("loaderVersion")) info.forgeLoaderRange = value;
        }
        flushTomlTable(section, table, info, outer, neoforge);
    }

    private static String unquoteToml(String value) {
        if (value.startsWith("\"") || value.startsWith("'")) {
            char quote = value.charAt(0);
            int end = value.indexOf(quote, 1);
            return end > 0 ? value.substring(1, end) : value.substring(1);
        }
        int comment = value.indexOf('#');
        return (comment >= 0 ? value.substring(0, comment) : value).trim();
    }

    private static void flushTomlTable(String section, @Nullable Map<String, String> table, ModInfo info, boolean outer, boolean neoforge) {
        if (table == null) return;
        if (section.equals("mods")) {
            String modId = table.get("modId");
            if (modId == null) return;
            info.providedIds.add(modId);
            String modVersion = table.get("version");
            if (modVersion != null && !modVersion.isEmpty() && !modVersion.contains("${")) info.versions.putIfAbsent(modId, modVersion);
            if (outer && info.name == null) info.name = table.containsKey("displayName") ? table.get("displayName") : modId;
            return;
        }
        if (!section.startsWith("dependencies")) return;
        String modId = table.get("modId");
        if (modId == null || "SERVER".equalsIgnoreCase(table.get("side"))) return;
        String mandatory = table.get("mandatory");
        String type = table.get("type");
        boolean required = "true".equals(mandatory) || "required".equalsIgnoreCase(type)
                || (neoforge && mandatory == null && type == null);
        if (!required) return;
        Dependency dependency = new Dependency();
        dependency.modId = modId;
        dependency.range = table.containsKey("versionRange") ? table.get("versionRange") : "";
        dependency.source = neoforge ? "neoforge" : "forge";
        info.dependencies.add(dependency);
    }

    private static void parseFabric(String text, ModInfo info, boolean outer) {
        JsonObject json;
        try {
            json = GSON.fromJson(text, JsonObject.class);
        } catch (Exception e) {
            return;
        }
        if (json == null || !json.has("id")) return;
        String id = json.get("id").getAsString();
        info.providedIds.add(id);
        String modVersion = json.has("version") && json.get("version").isJsonPrimitive() ? json.get("version").getAsString() : null;
        if (modVersion != null && !modVersion.contains("${")) info.versions.putIfAbsent(id, modVersion);
        if (id.equals("fabric-api")) info.providedIds.add("fabric");
        if (outer && info.name == null) info.name = json.has("name") ? json.get("name").getAsString() : id;
        JsonElement provides = json.get("provides");
        if (provides != null && provides.isJsonArray()) {
            for (JsonElement element : provides.getAsJsonArray()) {
                info.providedIds.add(element.getAsString());
                if (modVersion != null && !modVersion.contains("${")) info.versions.putIfAbsent(element.getAsString(), modVersion);
            }
        }
        readFabricDependencies(json.get("breaks"), info.breaks);
        readFabricDependencies(json.get("depends"), info.dependencies);
    }

    private static void readFabricDependencies(@Nullable JsonElement depends, List<Dependency> into) {
        if (depends == null || !depends.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : depends.getAsJsonObject().entrySet()) {
            Dependency dependency = new Dependency();
            dependency.modId = entry.getKey();
            dependency.fabricStyle = true;
            dependency.source = "fabric";
            JsonElement value = entry.getValue();
            if (value.isJsonArray()) {
                // Any of the listed predicates may match
                List<String> options = new ArrayList<>();
                for (JsonElement option : value.getAsJsonArray()) options.add(option.getAsString());
                dependency.range = android.text.TextUtils.join("||", options);
            } else {
                dependency.range = value.getAsString();
            }
            into.add(dependency);
        }
    }

    // ---- Version ranges ----

    public static int compareVersions(String a, String b) {
        String[] partsA = a.split("[.\\-+_]");
        String[] partsB = b.split("[.\\-+_]");
        for (int i = 0; i < Math.max(partsA.length, partsB.length); i++) {
            String x = i < partsA.length ? partsA[i] : "0";
            String y = i < partsB.length ? partsB[i] : "0";
            boolean numericX = x.matches("\\d{1,18}");
            boolean numericY = y.matches("\\d{1,18}");
            int result;
            if (numericX && numericY) result = Long.compare(Long.parseLong(x), Long.parseLong(y));
            else if (numericX != numericY) result = numericX ? 1 : -1; // 1.21 > 1.21-pre1
            else result = x.compareToIgnoreCase(y);
            if (result != 0) return result;
        }
        return 0;
    }

    /** Forge/NeoForge maven style: "[1.21.1,1.22)", "[52,)", a bare version means "any" */
    static boolean matchesMavenRange(String version, @Nullable String spec) {
        if (spec == null || spec.trim().isEmpty() || spec.trim().equals("*")) return true;
        Matcher matcher = Pattern.compile("([\\[(])([^\\])]*)([\\])])").matcher(spec);
        boolean anyRange = false;
        while (matcher.find()) {
            anyRange = true;
            boolean lowerInclusive = matcher.group(1).equals("[");
            boolean upperInclusive = matcher.group(3).equals("]");
            String body = matcher.group(2);
            int comma = body.indexOf(',');
            if (comma < 0) {
                if (compareVersions(version, body.trim()) == 0) return true;
                continue;
            }
            String lower = body.substring(0, comma).trim();
            String upper = body.substring(comma + 1).trim();
            boolean ok = true;
            if (!lower.isEmpty()) {
                int c = compareVersions(version, lower);
                ok = lowerInclusive ? c >= 0 : c > 0;
            }
            if (ok && !upper.isEmpty()) {
                int c = compareVersions(version, upper);
                ok = upperInclusive ? c <= 0 : c < 0;
            }
            if (ok) return true;
        }
        return !anyRange;
    }

    /** Fabric semver predicates: "*", ">=1.21", "~1.21.1", "^1.0", "1.21.x", "1.20 || 1.21" */
    static boolean matchesFabricRange(String version, @Nullable String spec) {
        if (spec == null || spec.trim().isEmpty()) return true;
        for (String alternative : spec.split("\\|\\|")) {
            boolean all = true;
            for (String predicate : alternative.trim().split("\\s+")) {
                if (!predicate.isEmpty() && !matchesFabricPredicate(version, predicate)) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    private static boolean matchesFabricPredicate(String version, String predicate) {
        if (predicate.equals("*")) return true;
        // build metadata does not count in a comparison (Fabric ignores it too): 1.11.7+mc26.3 <= 1.11.7
        if (predicate.startsWith("<") || predicate.startsWith(">")) {
            version = version.replaceFirst("\\+.*$", "");
            predicate = predicate.replaceFirst("\\+.*$", "");
        }
        if (predicate.startsWith(">=")) return compareVersions(version, predicate.substring(2)) >= 0;
        if (predicate.startsWith("<=")) return compareVersions(version, predicate.substring(2)) <= 0;
        if (predicate.startsWith(">")) return compareVersions(version, predicate.substring(1)) > 0;
        if (predicate.startsWith("<")) return compareVersions(version, predicate.substring(1)) < 0;
        if (predicate.startsWith("~") || predicate.startsWith("^")) {
            String base = predicate.substring(1);
            if (compareVersions(version, base) < 0) return false;
            // "~26.3-" means 26.3 and its pre-releases: only the release part has to line up
            String[] parts = base.replaceFirst("[-+].*$", "").split("\\.");
            String[] own = version.replaceFirst("[-+].*$", "").split("\\.");
            int fixedParts = predicate.startsWith("^") ? 1 : Math.min(2, parts.length);
            for (int i = 0; i < fixedParts && i < parts.length; i++) {
                if (i >= own.length || !own[i].equals(parts[i])) return false;
            }
            return true;
        }
        String exact = predicate.startsWith("=") ? predicate.substring(1) : predicate;
        if (exact.endsWith(".x") || exact.endsWith(".X") || exact.endsWith(".*")) {
            String prefix = exact.substring(0, exact.length() - 1);
            return version.startsWith(prefix) || version.equals(prefix.substring(0, prefix.length() - 1));
        }
        return compareVersions(version, exact) == 0;
    }

    private static boolean matches(Dependency dependency, String version) {
        return dependency.fabricStyle ? matchesFabricRange(version, dependency.range) : matchesMavenRange(version, dependency.range);
    }

    /** Only the metadata the running loader reads counts, a Fabric+NeoForge jar must not need NeoForge on Fabric */
    private static boolean appliesTo(Dependency dependency, ModInfo info, Environment environment) {
        if (environment.loader == null || dependency.source == null) return true;
        switch (environment.loader) {
            case "forge": return dependency.source.equals("forge");
            case "neoforge": return dependency.source.equals("neoforge")
                    || dependency.source.equals("forge") && !info.hasNeoforgeToml;
            default: return dependency.source.equals("fabric");
        }
    }

    // ---- Checking ----

    /** @return the first problem that prevents this jar from loading on the environment, or null */
    @Nullable
    public static Problem checkCompatible(ModInfo info, Environment environment) {
        Problem wrongLoader = checkLoader(info, environment);
        if (wrongLoader != null) return wrongLoader;
        for (Dependency dependency : info.dependencies) {
            if (!appliesTo(dependency, info, environment)) continue;
            String actual = null;
            if (dependency.modId.equals("minecraft")) actual = environment.gameVersion;
            else if (environment.loaderVersion != null && isLoaderId(dependency.modId, environment.loader)) actual = environment.loaderVersion;
            if (actual != null && !matches(dependency, actual)) {
                return problem(PROBLEM_INCOMPATIBLE, info, dependency.modId, dependency.range, actual);
            }
        }
        if ("forge".equals(environment.loader) && environment.loaderVersion != null && info.forgeLoaderRange != null) {
            String forgeMajor = environment.loaderVersion.split("\\.")[0];
            if (!matchesMavenRange(forgeMajor, info.forgeLoaderRange)) {
                return problem(PROBLEM_INCOMPATIBLE, info, "forge", info.forgeLoaderRange, environment.loaderVersion);
            }
        }
        return null;
    }

    private static boolean isLoaderId(String modId, @Nullable String loader) {
        if (loader == null) return false;
        switch (loader) {
            case "forge": return modId.equals("forge");
            case "neoforge": return modId.equals("neoforge");
            case "fabric": return modId.equals("fabricloader") || modId.equals("fabric-loader");
            default: return false;
        }
    }

    @Nullable
    private static Problem checkLoader(ModInfo info, Environment environment) {
        if (environment.loader == null) return null;
        boolean fits;
        String madeFor;
        switch (environment.loader) {
            case "forge":
                fits = info.hasForgeToml || environment.connector && info.hasFabricJson;
                madeFor = info.hasNeoforgeToml ? "NeoForge" : "Fabric";
                break;
            case "neoforge":
                fits = info.hasNeoforgeToml || info.hasForgeToml || environment.connector && info.hasFabricJson;
                madeFor = "Fabric";
                break;
            default:
                fits = info.hasFabricJson;
                madeFor = info.hasNeoforgeToml ? "NeoForge" : "Forge";
        }
        if (fits) return null;
        return problem(PROBLEM_WRONG_LOADER, info, madeFor, "", environment.loader);
    }

    private static Problem problem(int kind, ModInfo info, String requiredId, String range, @Nullable String actual) {
        Problem problem = new Problem();
        problem.kind = kind;
        problem.file = info.file;
        problem.modName = info.name;
        problem.requiredId = requiredId;
        problem.requiredRange = range;
        problem.actualVersion = actual;
        return problem;
    }

    /** Everything that would stop the mods folder from loading */
    public static List<Problem> analyze(File modsDir, Environment environment) {
        List<Problem> problems = new ArrayList<>();
        if (environment.loader == null) return problems;
        File[] files = modsDir.listFiles();
        if (files == null) return problems;
        List<ModInfo> mods = new ArrayList<>();
        Set<String> provided = builtinIds(environment);
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".jar")) continue;
            ModInfo info = read(file);
            if (info == null) continue;
            mods.add(info);
            provided.addAll(info.providedIds);
        }
        // Sinytra Connector runs the Fabric mods of a (Neo)Forge pack, they are not misplaced
        if (provided.contains("connector") && !"fabric".equals(environment.loader) && !"quilt".equals(environment.loader)) {
            Environment withConnector = of(environment.gameVersion, environment.loader, environment.loaderVersion);
            withConnector.connector = true;
            environment = withConnector;
        }
        // Every version each mod id is there in, and the top level jar it is in
        Map<String, List<String>> versionsOf = new HashMap<>();
        Map<String, File> providerOf = new HashMap<>();
        for (ModInfo info : mods) {
            for (Map.Entry<String, String> entry : info.versions.entrySet()) {
                versionsOf.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
                providerOf.putIfAbsent(entry.getKey(), info.file);
            }
        }
        Set<String> builtin = builtinIds(environment);
        Set<String> reportedMissing = new HashSet<>();
        Set<String> reportedVersion = new HashSet<>();
        for (ModInfo info : mods) {
            Problem incompatible = checkCompatible(info, environment);
            if (incompatible != null) {
                problems.add(incompatible);
                continue;
            }
            for (Dependency dependency : info.dependencies) {
                if (!appliesTo(dependency, info, environment)) continue;
                String id = dependency.modId;
                if (provided.contains(id) && !builtin.contains(id)) {
                    // There, but is it a version this mod accepts? (any copy that fits is enough)
                    List<String> present = versionsOf.get(id);
                    if (present == null || dependency.range == null || dependency.range.trim().isEmpty()) continue;
                    boolean fits = false;
                    for (String version : present) {
                        if (matches(dependency, version)) {
                            fits = true;
                            break;
                        }
                    }
                    if (!fits && reportedVersion.add(info.file.getName() + "|" + id)) {
                        Problem wrong = problem(PROBLEM_WRONG_VERSION, info, id, dependency.range, present.get(0));
                        wrong.providerFile = providerOf.get(id);
                        problems.add(wrong);
                    }
                    continue;
                }
                if (provided.contains(id) || isFabricApiModule(id) && provided.contains("fabric-api")) continue;
                if (!reportedMissing.add(id)) continue;
                problems.add(problem(PROBLEM_MISSING, info, id, dependency.range, null));
            }
            for (Dependency rule : info.breaks) {
                if (!appliesTo(rule, info, environment)) continue;
                List<String> present = versionsOf.get(rule.modId);
                File broken = providerOf.get(rule.modId);
                if (present == null || broken == null || broken.equals(info.file)) continue;
                for (String version : present) {
                    if (!matches(rule, version)) continue;
                    Problem problem = problem(PROBLEM_BREAKS, info, rule.modId, rule.range, version);
                    problem.providerFile = broken;
                    problems.add(problem);
                    break;
                }
            }
        }
        // ONA: world generation types of another mod that its installed build does not have
        for (ModInfo info : mods) {
            Map<String, Set<String>> used = DataTypes.usedTypes(info.file);
            for (Map.Entry<String, Set<String>> entry : used.entrySet()) {
                String id = entry.getKey();
                if (info.providedIds.contains(id)) continue;
                File provider = providerOf.get(id);
                if (provider == null || provider.equals(info.file)) continue;
                List<String> missing = DataTypes.missing(provider, entry.getValue());
                if (missing.isEmpty()) continue;
                List<String> present = versionsOf.get(id);
                Problem problem = problem(PROBLEM_MISSING_TYPE, info, id, String.join(",", missing),
                        present == null || present.isEmpty() ? null : present.get(0));
                problem.providerFile = provider;
                problems.add(problem);
                Log.i(TAG, info.name + " uses " + id + " types " + missing + " that " + provider.getName() + " does not have");
            }
        }
        return problems;
    }

    private static boolean isFabricApiModule(String id) {
        return id.equals("fabric") || id.startsWith("fabric-") && (id.matches(".*-v\\d+$") || id.equals("fabric-api-base"));
    }

    // ---- Fixing from Modrinth ----

    public static final int FIX_REPLACED = 0;
    public static final int FIX_DOWNLOADED = 1;
    public static final int FIX_DISABLED = 2;
    public static final int FIX_FAILED = 3;

    /** Try to fix one problem. Network heavy, call off the UI thread. */
    public static int fix(Problem problem, File modsDir, Environment environment) {
        // ONA never switches a mod off: only add requirements or swap in a build that fits
        return fix(problem, modsDir, environment, false);
    }

    /**
     * @param allowDisable false never turns a mod off, used by the automatic fixer so it only
     *                     ever adds or swaps jars behind the user's back
     */
    public static int fix(Problem problem, File modsDir, Environment environment, boolean allowDisable) {
        try {
            if (problem.kind == PROBLEM_MISSING) {
                // A jar the user (or an older fix) disabled may already provide it
                if (reenableProvider(problem.requiredId, modsDir, environment)) return FIX_REPLACED;
                String projectId = findProjectForModId(problem.requiredId, environment);
                if (projectId == null) return FIX_FAILED;
                File installed = installCompatible(projectId, modsDir, environment, problem.requiredId);
                return installed != null ? FIX_DOWNLOADED : FIX_FAILED;
            }
            if (problem.kind == PROBLEM_WRONG_VERSION) return fixWrongVersion(problem, modsDir, environment);
            if (problem.kind == PROBLEM_BREAKS) return fixBreaks(problem, modsDir, environment);
            if (problem.kind == PROBLEM_MISSING_TYPE) return fixMissingType(problem, modsDir, environment);
            // Incompatible or wrong loader: look for the right build of the same project first
            String projectId = findProjectForFile(problem.file);
            if (projectId != null) {
                File replacement = installCompatible(projectId, modsDir, environment, null);
                if (replacement != null) {
                    if (!replacement.equals(problem.file) && !problem.file.delete()) {
                        Log.w(TAG, "Could not remove " + problem.file);
                    }
                    return FIX_REPLACED;
                }
            }
            if (!allowDisable) return FIX_FAILED;
            File disabled = new File(problem.file.getPath() + ".disabled");
            return problem.file.renameTo(disabled) ? FIX_DISABLED : FIX_FAILED;
        } catch (Exception e) {
            Log.w(TAG, "Fix failed for " + problem.modName, e);
            return FIX_FAILED;
        }
    }

    /**
     * A required mod in the wrong version (Reese's Sodium Options wants Sodium 0.9.3-beta.1, Sodium
     * 0.9.2 is there): first the build of the required mod that the range accepts replaces the old
     * one, else a build of the mod that needs it which accepts the version that is there. Only
     * versions of the same projects are swapped, nothing is turned off.
     */
    private static int fixWrongVersion(Problem problem, File modsDir, Environment environment) throws IOException {
        Dependency wanted = new Dependency();
        wanted.modId = problem.requiredId;
        wanted.range = problem.requiredRange;
        String trimmed = problem.requiredRange == null ? "" : problem.requiredRange.trim();
        // Forge / NeoForge ranges are Maven style ("[1.2,)"), Fabric's are not
        wanted.fabricStyle = !(trimmed.startsWith("[") || trimmed.startsWith("("));
        if (problem.providerFile != null && problem.providerFile.isFile()) {
            String projectId = findProjectForFile(problem.providerFile);
            if (projectId == null) projectId = findProjectForModId(problem.requiredId, environment);
            if (projectId != null) {
                String hint = exactVersionHint(problem.requiredRange);
                File installed = installWhere(projectId, modsDir, environment, problem.requiredId, hint, info -> {
                    String version = info.versions.get(problem.requiredId);
                    // and not a build that breaks another mod there (newest Sodium vs Iris)
                    return version != null && matches(wanted, version)
                            && fitsOthers(modsDir, info, problem.providerFile, environment, false);
                });
                if (installed != null) {
                    if (!installed.equals(problem.providerFile) && !problem.providerFile.delete()) {
                        Log.w(TAG, "Could not remove " + problem.providerFile);
                    }
                    return FIX_REPLACED;
                }
            }
        }
        // The other way round: a build of the mod that needs it, which accepts what is there
        if (problem.actualVersion != null && problem.file != null && problem.file.isFile()) {
            String projectId = findProjectForFile(problem.file);
            if (projectId != null) {
                File installed = installWhere(projectId, modsDir, environment, null, null, info -> {
                    for (Dependency dependency : info.dependencies) {
                        if (dependency.modId.equals(problem.requiredId) && appliesTo(dependency, info, environment)
                                && !matches(dependency, problem.actualVersion)) return false;
                    }
                    return true;
                });
                if (installed != null) {
                    if (!installed.equals(problem.file) && !problem.file.delete()) {
                        Log.w(TAG, "Could not remove " + problem.file);
                    }
                    return FIX_REPLACED;
                }
            }
        }
        return FIX_FAILED;
    }

    /**
     * ONA: a data mod uses world generation types its provider does not have. First a build of the
     * provider (same project) that has every one of them and fits the other mods; else the newest
     * build of the data mod that only uses what the installed provider has (Lithosphere 1.9 ->
     * 1.8.2 on 1.21.8, where no Lithostitched has "fast_noise"). Only builds of the same projects
     * are swapped, nothing is turned off.
     */
    private static int fixMissingType(Problem problem, File modsDir, Environment environment) throws IOException {
        File provider = problem.providerFile;
        if (provider == null || !provider.isFile() || problem.requiredRange == null) return FIX_FAILED;
        Set<String> names = new java.util.LinkedHashSet<>(Arrays.asList(problem.requiredRange.split(",")));
        String providerProject = findProjectForFile(provider);
        if (providerProject == null) providerProject = findProjectForModId(problem.requiredId, environment);
        if (providerProject != null) {
            File installed = installWhere(providerProject, modsDir, environment, problem.requiredId, null,
                    info -> DataTypes.missing(info.file, names).isEmpty()
                            && fitsOthers(modsDir, info, provider, environment, true));
            if (installed != null) {
                if (!installed.equals(provider) && !provider.delete()) Log.w(TAG, "Could not remove " + provider);
                Log.i(TAG, "Replaced " + provider.getName() + " with " + installed.getName() + " (has " + names + ")");
                return FIX_REPLACED;
            }
        }
        if (problem.file == null || !problem.file.isFile()) return FIX_FAILED;
        String dataProject = findProjectForFile(problem.file);
        if (dataProject == null) return FIX_FAILED;
        File installed = installWhere(dataProject, modsDir, environment, null, null,
                info -> DataTypes.missingIn(info.file, problem.requiredId, provider).isEmpty()
                        && fitsOthers(modsDir, info, problem.file, environment, true));
        if (installed == null) return FIX_FAILED;
        if (!installed.equals(problem.file) && !problem.file.delete()) Log.w(TAG, "Could not remove " + problem.file);
        Log.i(TAG, "Replaced " + problem.file.getName() + " with " + installed.getName()
                + " (uses only what " + provider.getName() + " has)");
        return FIX_REPLACED;
    }

    /**
     * Sodium 0.9.3-beta.4 breaks Iris 1.11.7: first a newer Iris that it does not break, else the
     * newest Sodium that does not break the Iris that is there (beta.3), and still fits what the
     * other mods ask of it. Only builds of the same projects are swapped, nothing is turned off.
     */
    private static int fixBreaks(Problem problem, File modsDir, Environment environment) throws IOException {
        Dependency rule = new Dependency();
        rule.modId = problem.requiredId;
        rule.range = problem.requiredRange;
        rule.fabricStyle = true;
        ModInfo breaker = read(problem.file);
        if (breaker == null) return FIX_FAILED;
        // 1. A build of the broken mod outside the range
        if (problem.providerFile != null && problem.providerFile.isFile()) {
            String projectId = findProjectForFile(problem.providerFile);
            if (projectId == null) projectId = findProjectForModId(problem.requiredId, environment);
            if (projectId != null) {
                File installed = installWhere(projectId, modsDir, environment, problem.requiredId, null, info -> {
                    String version = info.versions.get(problem.requiredId);
                    return version != null && !matches(rule, version) && fitsOthers(modsDir, info, problem.providerFile, environment, true);
                });
                if (installed != null) {
                    if (!installed.equals(problem.providerFile) && !problem.providerFile.delete()) {
                        Log.w(TAG, "Could not remove " + problem.providerFile);
                    }
                    return FIX_REPLACED;
                }
            }
        }
        // 2. A build of the mod that breaks it which accepts the version that is there
        if (problem.actualVersion != null && problem.file.isFile()) {
            String projectId = findProjectForFile(problem.file);
            String ownId = breaker.versions.isEmpty() ? null : breaker.versions.keySet().iterator().next();
            if (projectId == null && ownId != null) projectId = findProjectForModId(ownId, environment);
            if (projectId != null) {
                File installed = installWhere(projectId, modsDir, environment, null, null, info -> {
                    for (Dependency other : info.breaks) {
                        if (other.modId.equals(problem.requiredId) && matches(other, problem.actualVersion)) return false;
                    }
                    return fitsOthers(modsDir, info, problem.file, environment, true);
                });
                if (installed != null) {
                    if (!installed.equals(problem.file) && !problem.file.delete()) {
                        Log.w(TAG, "Could not remove " + problem.file);
                    }
                    return FIX_REPLACED;
                }
            }
        }
        return FIX_FAILED;
    }

    /**
     * True when [candidate], put in place of [replaced], still has every version the other mods
     * ask of it, does not break them, and its own requirements on them are met ([versions] false:
     * only that nothing breaks)
     */
    private static boolean fitsOthers(File modsDir, ModInfo candidate, @Nullable File replaced, Environment environment,
                                      boolean versions) {
        File[] jars = modsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null) return true;
        for (File jar : jars) {
            if (jar.equals(replaced) || jar.getName().endsWith(".check")) continue;
            ModInfo other = read(jar);
            if (other == null) continue;
            for (Dependency dependency : other.dependencies) {
                if (!versions || !appliesTo(dependency, other, environment)) continue;
                String version = candidate.versions.get(dependency.modId);
                if (version != null && !matches(dependency, version)) return false;
            }
            for (Dependency rule : other.breaks) {
                String version = candidate.versions.get(rule.modId);
                if (version != null && matches(rule, version)) return false;
            }
            for (Dependency rule : candidate.breaks) {
                String version = other.versions.get(rule.modId);
                if (version != null && matches(rule, version)) return false;
            }
            for (Dependency dependency : candidate.dependencies) {
                if (!versions || !appliesTo(dependency, candidate, environment)) continue;
                String version = other.versions.get(dependency.modId);
                if (version != null && dependency.range != null && !dependency.range.trim().isEmpty()
                        && !matches(dependency, version)) return false;
            }
        }
        return true;
    }

    /** "=0.9.3-beta.1+mc26.3" / "0.9.3" / "[1.2.3]": the version text to look for first, else null */
    @Nullable
    private static String exactVersionHint(@Nullable String range) {
        if (range == null) return null;
        String value = range.trim();
        if (value.startsWith("[") && value.endsWith("]") && !value.contains(",")) value = value.substring(1, value.length() - 1);
        if (value.startsWith("=")) value = value.substring(1);
        if (value.isEmpty() || value.contains(" ") || value.contains("|") || value.contains(",")
                || "<>~^*".indexOf(value.charAt(0)) >= 0 || value.endsWith(".x") || value.endsWith("*")) return null;
        return value.replaceFirst("\\+.*$", "");
    }

    interface JarCheck { boolean ok(ModInfo info); }

    /**
     * Download builds of a project (newest first, those whose version name contains [hint] before
     * the rest, pre-releases included) until one loads on the environment, provides [mustProvide]
     * and passes [check]. @return the installed jar or null
     */
    @Nullable
    static File installWhere(String projectId, File modsDir, Environment environment, @Nullable String mustProvide,
                             @Nullable String hint, JarCheck check) throws IOException {
        String url = API + "/project/" + projectId + "/version?game_versions=" + encode("[\"" + environment.gameVersion + "\"]");
        if (environment.loader != null) url += "&loaders=" + encode("[\"" + environment.loader + "\"]");
        JsonArray versions = GSON.fromJson(httpGet(url), JsonArray.class);
        List<JsonObject> ordered = new ArrayList<>();
        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            String number = version.has("version_number") ? version.get("version_number").getAsString() : "";
            if (hint != null && number.contains(hint)) ordered.add(0, version);
            else ordered.add(version);
        }
        int tried = 0;
        for (JsonObject version : ordered) {
            if (tried++ >= 12) break;
            JsonObject file = primaryFile(version);
            if (file == null) continue;
            String fileName = new File(file.get("filename").getAsString()).getName();
            File candidate = new File(modsDir, fileName + ".check");
            download(file.get("url").getAsString(), candidate);
            ModInfo info = readUncached(candidate);
            boolean fits = info != null && checkCompatible(info, environment) == null
                    && (mustProvide == null || info.providedIds.contains(mustProvide)
                        || isFabricApiModule(mustProvide) && info.providedIds.contains("fabric-api"))
                    && check.ok(info);
            if (!fits) {
                //noinspection ResultOfMethodCallIgnored
                candidate.delete();
                continue;
            }
            File output = new File(modsDir, fileName);
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            if (!candidate.renameTo(output)) throw new IOException("Cannot write " + fileName);
            return output;
        }
        return null;
    }

    /**
     * ONA: Fabric's own answer after a failed start ("Replace mod 'Sodium' (sodium) 0.9.2+mc26.3 with
     * version 0.9.3-beta.1+mc26.3."), turned into problems the fixer handles on the next launch.
     */
    public static List<Problem> fromFabricSolution(String log, File modsDir, Environment environment) {
        List<Problem> problems = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Replace mod '([^']+)' \\(([^)]+)\\) (\\S+) with version (\\S+?)\\.?(?:\\s|$)")
                .matcher(log);
        File[] jars = modsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        while (m.find()) {
            String id = m.group(2);
            File provider = null;
            if (jars != null) {
                for (File jar : jars) {
                    ModInfo info = read(jar);
                    if (info != null && m.group(3).equals(info.versions.get(id))) {
                        provider = jar;
                        break;
                    }
                }
            }
            if (provider == null) continue;
            Problem problem = new Problem();
            problem.kind = PROBLEM_WRONG_VERSION;
            problem.file = provider;
            problem.modName = m.group(1);
            problem.requiredId = id;
            problem.requiredRange = "=" + m.group(4);
            problem.actualVersion = m.group(3);
            problem.providerFile = provider;
            problems.add(problem);
        }
        return problems;
    }

    private static boolean reenableProvider(String modId, File modsDir, Environment environment) {
        File[] disabled = modsDir.listFiles((dir, name) -> name.endsWith(".jar.disabled"));
        if (disabled == null) return false;
        for (File file : disabled) {
            ModInfo info = read(file);
            if (info == null || checkCompatible(info, environment) != null) continue;
            if (!info.providedIds.contains(modId) && !(isFabricApiModule(modId) && info.providedIds.contains("fabric-api"))) continue;
            String name = file.getName();
            File enabled = new File(modsDir, name.substring(0, name.length() - ".disabled".length()));
            if (!enabled.exists() && file.renameTo(enabled)) return true;
        }
        return false;
    }

    @Nullable
    private static String findProjectForFile(File jar) throws IOException {
        String sha1 = sha1(jar);
        try {
            JsonObject version = GSON.fromJson(httpGet(API + "/version_file/" + sha1 + "?algorithm=sha1"), JsonObject.class);
            return version.get("project_id").getAsString();
        } catch (IOException e) {
            return null; // Not a Modrinth file
        }
    }

    @Nullable
    private static String findProjectForModId(String modId, Environment environment) throws IOException {
        String slug = isFabricApiModule(modId) ? "fabric-api" : modId.replace('_', '-');
        try {
            JsonObject project = GSON.fromJson(httpGet(API + "/project/" + encode(slug)), JsonObject.class);
            if ("mod".equals(project.get("project_type").getAsString())) return project.get("id").getAsString();
        } catch (IOException ignored) {
            // No project with that slug, search for it
        }
        String facets = "[[\"project_type:mod\"],[\"categories:" + environment.loader + "\"]]";
        JsonObject result = GSON.fromJson(httpGet(API + "/search?limit=5&query=" + encode(modId)
                + "&facets=" + encode(facets)), JsonObject.class);
        JsonArray hits = result.getAsJsonArray("hits");
        String normalized = modId.replaceAll("[-_ ]", "").toLowerCase(Locale.ROOT);
        for (JsonElement element : hits) {
            JsonObject hit = element.getAsJsonObject();
            String hitSlug = hit.get("slug").getAsString().replaceAll("[-_ ]", "").toLowerCase(Locale.ROOT);
            if (hitSlug.equals(normalized)) return hit.get("project_id").getAsString();
        }
        return hits.size() > 0 ? hits.get(0).getAsJsonObject().get("project_id").getAsString() : null;
    }

    /**
     * Download the newest build of a project that really loads on this environment, checking the
     * jar metadata since Modrinth version tags are sometimes wrong.
     * @param mustProvide only accept jars that provide this mod id, or null for any
     * @return the installed jar, or null when no build fits
     */
    @Nullable
    public static File installCompatible(String projectId, File modsDir, Environment environment, @Nullable String mustProvide) throws IOException {
        String url = API + "/project/" + projectId + "/version?game_versions=" + encode("[\"" + environment.gameVersion + "\"]");
        if (environment.loader != null) url += "&loaders=" + encode("[\"" + environment.loader + "\"]");
        JsonArray versions = GSON.fromJson(httpGet(url), JsonArray.class);
        for (int i = 0; i < versions.size() && i < MAX_CANDIDATES; i++) {
            JsonObject file = primaryFile(versions.get(i).getAsJsonObject());
            if (file == null) continue;
            File installed = downloadIfCompatible(file.get("url").getAsString(), file.get("filename").getAsString(),
                    modsDir, environment, mustProvide);
            if (installed != null) return installed;
        }
        return null;
    }

    @Nullable
    public static JsonObject primaryFile(JsonObject version) {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null || files.size() == 0) return null;
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            if (file.has("primary") && file.get("primary").getAsBoolean()) return file;
        }
        return files.get(0).getAsJsonObject();
    }

    /**
     * Download a mod jar to a temporary name, keep it only if its metadata fits the environment.
     * @return the installed jar, or null if it does not fit
     */
    @Nullable
    public static File downloadIfCompatible(String url, String rawFileName, File modsDir, Environment environment,
                                            @Nullable String mustProvide) throws IOException {
        String fileName = new File(rawFileName).getName();
        File output = new File(modsDir, fileName);
        if (output.exists()) {
            ModInfo existing = read(output);
            if (existing == null || checkCompatible(existing, environment) == null) return output;
        }
        File candidate = new File(modsDir, fileName + ".check");
        download(url, candidate);
        ModInfo info = read(candidate);
        boolean fits = info == null || (checkCompatible(info, environment) == null
                && (mustProvide == null || info.providedIds.contains(mustProvide)
                    || isFabricApiModule(mustProvide) && info.providedIds.contains("fabric-api")));
        if (!fits) {
            //noinspection ResultOfMethodCallIgnored
            candidate.delete();
            return null;
        }
        //noinspection ResultOfMethodCallIgnored
        output.delete();
        if (!candidate.renameTo(output)) throw new IOException("Cannot write " + fileName);
        return output;
    }

    // ---- Networking ----

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IOException("HTTP " + code);
        }
        return connection;
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Downloads a file. It is written next to the output as ".part" and moved into place when it is
     * complete: a lost connection or an app closed half way never leaves half a file under the
     * real name (a half jar or pack would be taken for a finished one at the next launch), and a
     * file that was there before stays until the new one is whole.
     */
    public static void download(String url, @NonNull File output) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        File part = new File(output.getPath() + ".part");
        HttpURLConnection connection = open(url);
        try {
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            if (part.length() == 0) throw new IOException("Empty download: " + url);
            if (output.exists() && !output.delete()) throw new IOException("Cannot replace " + output);
            if (!part.renameTo(output)) throw new IOException("Cannot write " + output);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            connection.disconnect();
        }
    }

    /** True when the file opens as a zip / jar with at least one entry (a download that is whole) */
    public static boolean isZip(@NonNull File file) {
        if (!file.isFile() || file.length() < 22) return false;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
            return zip.entries().hasMoreElements();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String sha1(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
