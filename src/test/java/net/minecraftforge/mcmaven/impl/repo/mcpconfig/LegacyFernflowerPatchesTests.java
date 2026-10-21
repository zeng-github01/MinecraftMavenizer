/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LegacyFernflowerPatchesTests {
    @Test
    void appliesLegacyFernflowerPatchAfterPackageRemapping(@TempDir Path temp) throws Exception {
        var input = temp.resolve("decompiled.jar");
        var output = temp.resolve("fixed.jar");
        var packageMappings = "class,package\nGuiScreen,net/minecraft/client/gui\n".getBytes(StandardCharsets.UTF_8);
        var patch = """
            diff -r -U 3 minecraft\\net\\minecraft\\src\\GuiScreen.java minecraft_patched\\net\\minecraft\\src\\GuiScreen.java
            --- minecraft\\net\\minecraft\\src\\GuiScreen.java\told
            +++ minecraft_patched\\net\\minecraft\\src\\GuiScreen.java\tnew
            @@ -2,5 +2,9 @@
             class GuiScreen {
               public Object call() {
            -    return this.callSomething();
            +    try {
            +      return this.callSomething();
            +    } catch(Exception ex) {
            +      return null;
            +    }
               }
             }
            diff -r -U 3 minecraft\\net\\minecraft\\src\\Other.java minecraft_patched\\net\\minecraft\\src\\Other.java
            --- minecraft\\net\\minecraft\\src\\Other.java\told
            +++ minecraft_patched\\net\\minecraft\\src\\Other.java\tnew
            @@ -1 +1 @@
            -old source
            +new source
            """.getBytes(StandardCharsets.UTF_8);

        try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("net/minecraft/client/gui/GuiScreen.java"));
            zip.write("""
                package net.minecraft.client.gui;
                class GuiScreen {
                  public Object call() {
                    return this.callSomething();
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        LegacyFernflowerPatches.apply(input.toFile(), output.toFile(), patch, packageMappings);

        try (var zip = new ZipFile(output.toFile())) {
            var source = new String(zip.getInputStream(zip.getEntry("net/minecraft/client/gui/GuiScreen.java")).readAllBytes(), StandardCharsets.UTF_8);
            Assertions.assertTrue(source.contains("try {"));
            Assertions.assertTrue(source.contains("return null;"));
        }
    }
}
