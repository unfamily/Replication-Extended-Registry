package net.unfamiy.rep_ext_reg;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Replication Extended Registry mod configuration.
 * Registered in ExtReg: modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.Builder DEV = BUILDER
            .comment("Dev / external scripts. Base path for rep_ext_reg external data.").push("dev");

    /** Base directory for rep_ext_reg external scripts. Reusable for features that load from disk. */
    public static final ModConfigSpec.ConfigValue<String> EXTERNAL_SCRIPTS_PATH = DEV
            .comment("Directory for rep_ext_reg external scripts. Default: kubejs/external_scripts/rep_ext_reg")
            .define("externalScriptsPath", "kubejs/external_scripts/rep_ext_reg");

    static {
        DEV.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();
}
