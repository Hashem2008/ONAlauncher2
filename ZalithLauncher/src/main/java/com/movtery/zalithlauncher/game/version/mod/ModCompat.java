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
    }

    public static class Dependency {
        public String modId, range;
        boolean fabricStyle;
    }

    public static class ModInfo {
        public File file;
        public String name;
        /** Every mod id this jar provides, nested jars included */
        final Set<String> providedIds = new HashSet<>();
        final List<Dependency> dependencies = new ArrayList<>();
        boolean hasForgeToml, hasNeoforgeToml, hasFabricJson;
        @Nullable String forgeLoaderRange;
    }

    public static final int PROBLEM_INCOMPATIBLE = 0;
    public static final int PROBLEM_MISSING = 1;
    public static final int PROBLEM_WRONG_LOADER = 2;

    public static class Problem {
        public int kind;
        public File file;
        /** Display name of the mod that has the problem */
        public String modName;
        /** What it needs: a mod id, "minecraft" or a loader */
        public String requiredId;
        public String requiredRange;
        @Nullable public String actualVersion;
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

    @Nullable
    public static ModInfo read(File jar) {
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
        if (id.equals("fabric-api")) info.providedIds.add("fabric");
        if (outer && info.name == null) info.name = json.has("name") ? json.get("name").getAsString() : id;
        JsonElement provides = json.get("provides");
        if (provides != null && provides.isJsonArray()) {
            for (JsonElement element : provides.getAsJsonArray()) info.providedIds.add(element.getAsString());
        }
        JsonElement depends = json.get("depends");
        if (depends == null || !depends.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : depends.getAsJsonObject().entrySet()) {
            Dependency dependency = new Dependency();
            dependency.modId = entry.getKey();
            dependency.fabricStyle = true;
            JsonElement value = entry.getValue();
            if (value.isJsonArray()) {
                // Any of the listed predicates may match
                List<String> options = new ArrayList<>();
                for (JsonElement option : value.getAsJsonArray()) options.add(option.getAsString());
                dependency.range = android.text.TextUtils.join("||", options);
            } else {
                dependency.range = value.getAsString();
            }
            info.dependencies.add(dependency);
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
        if (predicate.startsWith(">=")) return compareVersions(version, predicate.substring(2)) >= 0;
        if (predicate.startsWith("<=")) return compareVersions(version, predicate.substring(2)) <= 0;
        if (predicate.startsWith(">")) return compareVersions(version, predicate.substring(1)) > 0;
        if (predicate.startsWith("<")) return compareVersions(version, predicate.substring(1)) < 0;
        if (predicate.startsWith("~") || predicate.startsWith("^")) {
            String base = predicate.substring(1);
            if (compareVersions(version, base) < 0) return false;
            String[] parts = base.split("\\.");
            String[] own = version.split("\\.");
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

    // ---- Checking ----

    /** @return the first problem that prevents this jar from loading on the environment, or null */
    @Nullable
    public static Problem checkCompatible(ModInfo info, Environment environment) {
        Problem wrongLoader = checkLoader(info, environment);
        if (wrongLoader != null) return wrongLoader;
        for (Dependency dependency : info.dependencies) {
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
                fits = info.hasForgeToml;
                madeFor = info.hasNeoforgeToml ? "NeoForge" : "Fabric";
                break;
            case "neoforge":
                fits = info.hasNeoforgeToml || info.hasForgeToml;
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
        Set<String> reportedMissing = new HashSet<>();
        for (ModInfo info : mods) {
            Problem incompatible = checkCompatible(info, environment);
            if (incompatible != null) {
                problems.add(incompatible);
                continue;
            }
            for (Dependency dependency : info.dependencies) {
                String id = dependency.modId;
                if (provided.contains(id) || isFabricApiModule(id) && provided.contains("fabric-api")) continue;
                if (!reportedMissing.add(id)) continue;
                problems.add(problem(PROBLEM_MISSING, info, id, dependency.range, null));
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
        try {
            if (problem.kind == PROBLEM_MISSING) {
                String projectId = findProjectForModId(problem.requiredId, environment);
                if (projectId == null) return FIX_FAILED;
                File installed = installCompatible(projectId, modsDir, environment, problem.requiredId);
                return installed != null ? FIX_DOWNLOADED : FIX_FAILED;
            }
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
            File disabled = new File(problem.file.getPath() + ".disabled");
            return problem.file.renameTo(disabled) ? FIX_DISABLED : FIX_FAILED;
        } catch (Exception e) {
            Log.w(TAG, "Fix failed for " + problem.modName, e);
            return FIX_FAILED;
        }
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

    public static void download(String url, @NonNull File output) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw e;
        } finally {
            connection.disconnect();
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
