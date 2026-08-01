package com.github.mcchallenge.challengehud.neoforge;

import com.github.mcchallenge.challengehud.ChallengeHud;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod("challengehud")
public class ChallengeHudNeoForge {
    public ChallengeHudNeoForge(IEventBus bus) {
        bus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        ChallengeHud.init();
    }
}
