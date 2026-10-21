package net.unfamiy.rep_ext_reg;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Replication Extended Registry mod configuration.
 * Matter register data is loaded via iska_lib data load JSON (no external_scripts path).
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec SPEC = BUILDER.build();
}
