package com.github.mcchallenge.challengehud.fabric;

import com.github.mcchallenge.challengehud.ChallengeHud;
import net.fabricmc.api.ClientModInitializer;

public class ChallengeHudFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ChallengeHud.init();
    }
}
