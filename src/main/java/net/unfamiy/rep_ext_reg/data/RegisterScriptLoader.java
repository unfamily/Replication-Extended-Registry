package net.unfamiy.rep_ext_reg.data;

import com.buuz135.replication.ReplicationRegistry;
import com.buuz135.replication.api.IMatterType;
import com.buuz135.replication.calculation.MatterCompound;
import com.buuz135.replication.calculation.MatterValue;
import com.buuz135.replication.calculation.ReplicationCalculation;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.unfamily.iskalib.load.LoadFilesystemBootstrap;
import net.unfamily.iskalib.load.LoadJson;
import net.unfamiy.rep_ext_reg.ExtReg;
import org.slf4j.Logger;

import java.util.*;

/**
 * Loads matter registration scripts via Iskandert Library load JSON.
 * JSON format: type {@code rep_ext_reg} (or {@code rep_ext_reg:register}), optional {@code aggressive},
 * {@code entries} array of {@code { "res": [...], "mat": [...] }}.
 * Sources: jar {@code data/rep_ext_reg/load/} (any nested folders), datapack bootstrap, ResourceManager.
 */
public class RegisterScriptLoader {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String TYPE = "rep_ext_reg";
    public static final String TYPE_ALT = "rep_ext_reg:register";
    private static final Set<String> ACCEPTED_TYPES = Set.of(TYPE, TYPE_ALT);

    private static final List<RegisterEntry> LOADED_ENTRIES = new ArrayList<>();

    public static void load() {
        load(null);
    }

    public static void load(ResourceManager resourceManager) {
        LOADED_ENTRIES.clear();
        Map<Identifier, JsonElement> merged = new LinkedHashMap<>();
        merged.putAll(LoadJson.collectFromModJarPath(ExtReg.MODID, ExtReg.MODID, LoadJson.LOAD_FOLDER));
        LoadFilesystemBootstrap.mergeIntoForTypes(merged, ACCEPTED_TYPES);
        if (resourceManager != null) {
            merged.putAll(LoadJson.collectMergedJsonForTypes(resourceManager, ACCEPTED_TYPES));
        }
        for (Map.Entry<Identifier, JsonElement> entry : LoadJson.orderedEntries(merged)) {
            parseRoot(entry.getValue());
        }
        LOGGER.info("[rep_ext_reg] Loaded {} register entries from load JSON", LOADED_ENTRIES.size());
    }

    private static void parseRoot(JsonElement root) {
        if (root == null || !root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        String type = obj.has("type") ? obj.get("type").getAsString() : "";
        if (!ACCEPTED_TYPES.contains(type)) return;
        boolean aggressive = obj.has("aggressive") && obj.get("aggressive").getAsBoolean();
        if (!obj.has("entries") || !obj.get("entries").isJsonArray()) return;
        JsonArray entries = obj.get("entries").getAsJsonArray();
        for (JsonElement el : entries) {
            if (!el.isJsonObject()) continue;
            RegisterEntry entry = parseEntry(el.getAsJsonObject(), aggressive);
            if (entry != null) LOADED_ENTRIES.add(entry);
        }
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
                mat.add(new MatterAmount(matterId.indexOf(':') >= 0 ? Identifier.parse(matterId) : Identifier.fromNamespaceAndPath("replication", matterId), amount));
            }
        }
        if (res.isEmpty() || mat.isEmpty()) return null;
        return new RegisterEntry(res, mat, aggressive);
    }

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
            IMatterType matterType = ReplicationRegistry.MATTER_TYPES_REGISTRY.getValue(ma.matterId);
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
                Identifier tagId = Identifier.tryParse(r.substring(1));
                if (tagId == null) continue;
                TagKey<Item> tagKey = TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagId);
                for (var holder : BuiltInRegistries.ITEM.getTagOrEmpty(tagKey)) {
                    items.add(holder.value());
                }
            } else {
                Identifier id = Identifier.tryParse(r);
                if (id == null) continue;
                Item item = BuiltInRegistries.ITEM.getValue(id);
                if (item != null && item != net.minecraft.world.item.Items.AIR) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    public static boolean hasLoadedEntries() {
        return !LOADED_ENTRIES.isEmpty();
    }

    private static boolean needsInject = false;

    public static void registerEvents(net.neoforged.bus.api.IEventBus forgeBus) {
        forgeBus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, RegisterScriptLoader::onServerTickPost);
        forgeBus.addListener(net.neoforged.neoforge.event.TagsUpdatedEvent.class, RegisterScriptLoader::onTagsUpdated);
    }

    private static void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        if (event.getUpdateCause() != net.neoforged.neoforge.event.TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            load(server.getResourceManager());
        } else {
            load();
        }
        needsInject = true;
    }

    private static void onServerTickPost(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!needsInject || !hasLoadedEntries()) return;
        if (ReplicationCalculation.STATUS != com.buuz135.replication.api.MatterCalculationStatus.CALCULATED) return;
        inject();
        needsInject = false;
    }

    private record RegisterEntry(List<String> res, List<MatterAmount> mat, boolean aggressive) {}
    private record MatterAmount(Identifier matterId, double amount) {}
}
