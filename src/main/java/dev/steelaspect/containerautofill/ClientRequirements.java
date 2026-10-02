/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The same jar runs on clients and dedicated servers, and only the client needs Litematica and MaLiLib.
 * fabric.mod.json can't make a dependency client-only, so they are listed under "suggests" and this
 * check (the first client entrypoint, with no Litematica/MaLiLib imports) stops the game with a clear
 * message if either is missing or too old, before any class that uses them is loaded.
 */
public class ClientRequirements implements ClientModInitializer {
    private static final List<String> CLIENT_DEPENDENCIES = List.of("litematica", "malilib");
    private static boolean met;

    /** False if the check failed; the main client entrypoint then does nothing. */
    public static boolean met() {
        return met;
    }

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        ModContainer self = loader.getModContainer(Reference.MOD_ID).orElseThrow();
        List<String> problems = new ArrayList<>();
        for (ModDependency dependency : self.getMetadata().getDependencies()) {
            if (dependency.getKind() != ModDependency.Kind.SUGGESTS || !CLIENT_DEPENDENCIES.contains(dependency.getModId())) continue;
            Optional<ModContainer> mod = loader.getModContainer(dependency.getModId());
            if (mod.isEmpty()) {
                problems.add(dependency.getModId() + " " + dependency.getVersionRequirements() + " is missing");
            } else if (!dependency.matches(mod.get().getMetadata().getVersion())) {
                problems.add(dependency.getModId() + " " + dependency.getVersionRequirements() + " is needed, found "
                        + mod.get().getMetadata().getVersion().getFriendlyString());
            }
        }
        met = problems.isEmpty();
        if (!met) {
            throw new IllegalStateException("Cytra Container needs Litematica and MaLiLib on the client: "
                    + String.join("; ", problems) + ". (A dedicated server doesn't need them.)");
        }
    }
}
