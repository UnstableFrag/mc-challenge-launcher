package com.github.mcchallenge.challengehud.forge;

import com.github.mcchallenge.challengehud.ChallengeHud;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod("challengehud")
public class ChallengeHudForge {
    public ChallengeHudForge() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        ChallengeHud.init();
    }
}
