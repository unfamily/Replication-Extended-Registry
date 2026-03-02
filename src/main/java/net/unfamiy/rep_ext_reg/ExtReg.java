package net.unfamiy.rep_ext_reg;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.unfamiy.rep_ext_reg.data.RegisterScriptLoader;

@Mod(ExtReg.MODID)
public class ExtReg {

    public static final String MODID = "rep_ext_reg";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ExtReg(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this);
        RegisterScriptLoader.registerEvents(NeoForge.EVENT_BUS);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.debug("rep_ext_reg common setup");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.debug("rep_ext_reg server starting");
    }
}
