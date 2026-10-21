/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraftforge.mcmaven.impl.cache.Cache;

public class ArtifactResolutionTests {
    @Test
    void resolvesLzmaFromMinecraftLibraries(@TempDir Path root) throws IOException {
        var artifact = Artifact.from("lzma:lzma:0.0.1");
        var forgeArtifact = root.resolve("maven/forge/lzma/lzma/0.0.1/lzma-0.0.1.jar");
        var minecraftArtifact = root.resolve("maven/mojang/lzma/lzma/0.0.1/lzma-0.0.1.jar");

        Files.createDirectories(forgeArtifact.getParent());
        Files.writeString(forgeArtifact, "error code: 502\n");
        Files.createDirectories(minecraftArtifact.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(minecraftArtifact))) {
            zip.finish();
        }

        var cache = new Cache(root.toFile(), root.resolve("jdks").toFile());

        Assertions.assertEquals(minecraftArtifact.toFile(), Util.getArtifact(cache, artifact, true));
    }
}
