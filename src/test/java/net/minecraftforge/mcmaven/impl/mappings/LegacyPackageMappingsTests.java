/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LegacyPackageMappingsTests {
    @Test
    void repackagesClassNamesAndDescriptors() throws Exception {
        var mappings = """
            CL: awe net/minecraft/src/GuiScreen
            CL: awe$1 net/minecraft/src/GuiScreen$1
            MD: awe/a ()Lawe; net/minecraft/src/GuiScreen/func_1_a ()Lnet/minecraft/src/GuiScreen;
            """.getBytes(StandardCharsets.UTF_8);
        var packages = """
            class,package
            GuiScreen,net/minecraft/client/gui
            """.getBytes(StandardCharsets.UTF_8);

        var result = LegacyPackageMappings.repackage(mappings, packages);

        Assertions.assertEquals("""
            CL: awe net/minecraft/client/gui/GuiScreen
            CL: awe$1 net/minecraft/client/gui/GuiScreen$1
            MD: awe/a ()Lawe; net/minecraft/client/gui/GuiScreen/func_1_a ()Lnet/minecraft/client/gui/GuiScreen;
            """, new String(result, StandardCharsets.UTF_8));
    }
}
