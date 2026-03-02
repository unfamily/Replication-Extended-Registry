package net.unfamiy.rep_ext_reg.data;

import com.buuz135.replication.ReplicationRegistry;
import com.buuz135.replication.calculation.MatterCompound;
import com.buuz135.replication.calculation.MatterValue;
import com.buuz135.replication.calculation.ReplicationCalculation;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.unfamiy.rep_ext_reg.Config;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Loads matter registration scripts from the external scripts path.
 * JSON format: type "rep_ext_reg", optional "aggressive" (default false), "entries" array of
 * { "res": ["minecraft:tnt"] or ["#c:ingots/lead"], "mat": [{"replication:earth": 10}] }.
 * When aggressive is true, these entries override any existing matter value for the same item.
 * Call {@link #load()} to parse scripts; call {@link #inject()} after Replication has finished
 * calculation (ReplicationCalculation.STATUS == CALCULATED) to apply entries to DEFAULT_MATTER_COMPOUND.
 */
public class RegisterScriptLoader {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String EXPECTED_TYPE = "rep_ext_reg";

    private static final List<RegisterEntry> LOADED_ENTRIES = new ArrayList<>();

    public static void load() {
        LOADED_ENTRIES.clear();
        String basePath = Config.EXTERNAL_SCRIPTS_PATH.get();
        if (basePath == null || basePath.trim().isEmpty()) {
            basePath = "kubejs/external_scripts/rep_ext_reg";
        }
        Path dir = Paths.get(basePath);
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                createReadme(dir);
                LOGGER.info("[rep_ext_reg] Created scripts directory and README at {}", dir.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.warn("[rep_ext_reg] Could not create scripts directory: {}", e.getMessage());
            }
            return;
        }
        if (!Files.isDirectory(dir)) {
            LOGGER.debug("[rep_ext_reg] Register scripts path is not a directory: {}", dir);
            return;
        }
        try {
            createReadme(dir);
        } catch (IOException e) {
            LOGGER.warn("[rep_ext_reg] Could not write README: {}", e.getMessage());
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .forEach(RegisterScriptLoader::parseFile);
        } catch (IOException e) {
            LOGGER.warn("[rep_ext_reg] Failed to scan register scripts: {}", e.getMessage());
        }
        LOGGER.info("[rep_ext_reg] Loaded {} register entries from scripts", LOADED_ENTRIES.size());
    }

    private static void createReadme(Path dir) throws IOException {
        String content = """
            # Replication Extended Registry – Register scripts

            This folder is read by **rep_ext_reg** to register matter values for items (Replication mod).
            Place one or more `.json` files here. They are loaded on server start and after `/reload`.

            ## Path

            Configured in mod config: **dev.externalScriptsPath** (default: `kubejs/external_scripts/rep_ext_reg`).

            ## JSON format

            Each JSON file must have:

            - **`type`** (required): must be `"rep_ext_reg"`. Files with another type are ignored.
            - **`aggressive`** (optional, default `false`): if `true`, these entries **override** any existing matter value for the same item (datapack/KubeJS). If `false`, entries apply only when the item has no value yet.
            - **`entries`** (required): array of objects, each with:
              - **`res`**: array of strings — one or more item IDs (`"minecraft:tnt"`) and/or **tags** with `#` prefix (`"#c:ingots/lead"`). All listed IDs share the same matter definition for this entry.
              - **`mat`**: array of objects mapping matter type to amount: `{ "replication:earth": 10 }`. Use full ID (`replication:metallic`) or short name (`metallic` → `replication:metallic`). Multiple matter types per entry are allowed.

            ## Comments

            `//` and `/* */` comments are allowed in JSON and are stripped before parsing.

            ## Example

            One entry can list multiple IDs in `res`; they all get the same `mat`:

            ```json
            {
                "type": "rep_ext_reg",
                "aggressive": false,
                "entries": [
                    {
                        "res": ["minecraft:tnt", "minecraft:gunpowder"],
                        "mat": [{"replication:earth": 10}]
                    },
                    {
                        "res": ["#c:ingots/lead", "#c:ingots/iron", "minecraft:iron_ingot"],
                        "mat": [{"replication:metallic": 10}]
                    }
                ]
            }
            ```

            ## When it applies

            Scripts are loaded on **TagsUpdatedEvent** (server data load / reload). Values are **injected** into Replication’s matter calculation after it has finished (first server tick where status is CALCULATED). Use `/reload` to re-apply after editing files.
            """;
        Files.writeString(dir.resolve("README.md"), content);
    }

    private static void parseFile(Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            LOGGER.warn("[rep_ext_reg] Could not read {}: {}", file, e.getMessage());
            return;
        }
        content = stripJsonComments(content);
        JsonElement root;
        try {
            root = GSON.fromJson(content, JsonElement.class);
        } catch (Exception e) {
            LOGGER.warn("[rep_ext_reg] Invalid JSON in {}: {}", file, e.getMessage());
            return;
        }
        if (root == null || !root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        String type = obj.has("type") ? obj.get("type").getAsString() : "";
        if (!EXPECTED_TYPE.equals(type)) return;
        boolean aggressive = obj.has("aggressive") && obj.get("aggressive").getAsBoolean();
        if (!obj.has("entries") || !obj.get("entries").isJsonArray()) return;
        JsonArray entries = obj.get("entries").getAsJsonArray();
        for (JsonElement el : entries) {
            if (!el.isJsonObject()) continue;
            RegisterEntry entry = parseEntry(el.getAsJsonObject(), aggressive);
            if (entry != null) LOADED_ENTRIES.add(entry);
        }
    }

    private static String stripJsonComments(String json) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (inBlockComment) {
                if (c == '*' && i + 1 < json.length() && json.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
                i++;
                continue;
            }
            if (inLineComment) {
                if (c == '\n' || c == '\r') inLineComment = false;
                i++;
                continue;
            }
            if (escaped) {
                out.append(c);
                escaped = false;
                i++;
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\') escaped = true;
                else if (c == stringChar) inString = false;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
                out.append(c);
                i++;
                continue;
            }
            if (c == '/' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '/') {
                    inLineComment = true;
                    i += 2;
                    continue;
                }
                if (next == '*') {
                    inBlockComment = true;
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static RegisterEntry parseEntry(JsonObject o, boolean aggressive) {
        if (!o.has("res") || !o.get("res").isJsonArray() || !o.has("mat") || !o.get("mat").isJsonArray()) return null;
        List<String> res = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("res")) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString())
                res.add(e.getAsString());
        }
        List<MatterAmount> mat = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("mat")) {
            if (!e.isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
                String matterId = kv.getKey();
                double amount = 1;
                if (kv.getValue().isJsonPrimitive() && kv.getValue().getAsJsonPrimitive().isNumber())
                    amount = kv.getValue().getAsDouble();
                else if (kv.getValue().isJsonPrimitive() && kv.getValue().getAsJsonPrimitive().isString()) {
                    try {
                        amount = Double.parseDouble(kv.getValue().getAsString());
                    } catch (NumberFormatException ignored) {}
                }
                mat.add(new MatterAmount(matterId.indexOf(':') >= 0 ? ResourceLocation.parse(matterId) : ResourceLocation.fromNamespaceAndPath("replication", matterId), amount));
            }
        }
        if (res.isEmpty() || mat.isEmpty()) return null;
        return new RegisterEntry(res, mat, aggressive);
    }

    /**
     * Applies loaded register entries to Replication's DEFAULT_MATTER_COMPOUND.
     * Call only after ReplicationCalculation.STATUS == CALCULATED (e.g. from a delayed tick task).
     */
    public static void inject() {
        if (ReplicationRegistry.MATTER_TYPES_REGISTRY == null) {
            LOGGER.warn("[rep_ext_reg] Replication matter registry not available, skipping inject");
            return;
        }
        if (ReplicationCalculation.DEFAULT_MATTER_COMPOUND == null) {
            LOGGER.warn("[rep_ext_reg] Replication DEFAULT_MATTER_COMPOUND not available, skipping inject");
            return;
        }
        int applied = 0;
        for (RegisterEntry entry : LOADED_ENTRIES) {
            MatterCompound compound = buildCompound(entry.mat);
            if (compound == null) continue;
            Set<Item> items = resolveResources(entry.res);
            for (Item item : items) {
                if (entry.aggressive) {
                    ReplicationCalculation.DEFAULT_MATTER_COMPOUND.put(item, compound.duplicate());
                    applied++;
                } else if (!ReplicationCalculation.DEFAULT_MATTER_COMPOUND.containsKey(item)) {
                    ReplicationCalculation.DEFAULT_MATTER_COMPOUND.put(item, compound.duplicate());
                    applied++;
                }
            }
        }
        if (applied > 0) {
            LOGGER.info("[rep_ext_reg] Injected {} item matter value(s) from register scripts", applied);
        }
    }

    private static MatterCompound buildCompound(List<MatterAmount> mat) {
        MatterCompound compound = new MatterCompound();
        for (MatterAmount ma : mat) {
            var matterType = ReplicationRegistry.MATTER_TYPES_REGISTRY.get(ma.matterId);
            if (matterType == null) {
                LOGGER.debug("[rep_ext_reg] Unknown matter type: {}", ma.matterId);
                continue;
            }
            compound.add(new MatterValue(matterType, ma.amount));
        }
        return compound.getValues().isEmpty() ? null : compound;
    }

    private static Set<Item> resolveResources(List<String> res) {
        Set<Item> items = new HashSet<>();
        for (String r : res) {
            if (r == null || r.isEmpty()) continue;
            if (r.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(r.substring(1));
                if (tagId == null) continue;
                TagKey<Item> tagKey = TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagId);
                BuiltInRegistries.ITEM.getTag(tagKey).ifPresent(holderSet ->
                        holderSet.stream().map(h -> h.value()).forEach(items::add));
            } else {
                ResourceLocation id = ResourceLocation.tryParse(r);
                if (id == null) continue;
                if (BuiltInRegistries.ITEM.containsKey(id))
                    items.add(BuiltInRegistries.ITEM.get(id));
            }
        }
        return items;
    }

    public static boolean hasLoadedEntries() {
        return !LOADED_ENTRIES.isEmpty();
    }

    // --- Event-driven load and inject (call registerEvents() from mod constructor) ---

    private static boolean needsInject = false;

    public static void registerEvents(net.neoforged.bus.api.IEventBus forgeBus) {
        forgeBus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, RegisterScriptLoader::onServerTickPost);
        forgeBus.addListener(net.neoforged.neoforge.event.TagsUpdatedEvent.class, RegisterScriptLoader::onTagsUpdated);
    }

    private static void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        if (event.getUpdateCause() != net.neoforged.neoforge.event.TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
        load();
        needsInject = true;
    }

    private static void onServerTickPost(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!needsInject || !hasLoadedEntries()) return;
        if (ReplicationCalculation.STATUS != com.buuz135.replication.api.MatterCalculationStatus.CALCULATED) return;
        inject();
        needsInject = false;
    }

    private record RegisterEntry(List<String> res, List<MatterAmount> mat, boolean aggressive) {}
    private record MatterAmount(ResourceLocation matterId, double amount) {}
}
