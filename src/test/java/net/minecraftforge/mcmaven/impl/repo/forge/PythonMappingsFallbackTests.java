/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.util.Artifact;

public class PythonMappingsFallbackTests {
    @Test
    void usesMcpVersionWhenForgeHasNoDefaultMappings(@TempDir Path root) throws Exception {
        var version = "1.4.7-6.6.2.534";
        var source = Artifact.from("net.minecraftforge", "forge", version, "src", "zip");
        var sourceFile = root.resolve("maven").resolve("forge").resolve(source.getPath());
        Files.createDirectories(sourceFile.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(sourceFile))) {
            zip.putNextEntry(new ZipEntry("forge/fml/conf/version.cfg"));
            zip.write("[VERSION]\nMCPVersion = 7.26a\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        var cache = new Cache(root.toFile(), root.resolve("jdks").toFile());
        var repo = new ForgeRepo(cache, new MCPConfigRepo(cache, false));
        var mappings = repo.getDefaultMappings(version);

        Assertions.assertEquals("mcp", mappings.channel());
        Assertions.assertEquals("7.26a", mappings.version());
    }

    @Test
    void usesSnapshotMappingsAcrossForge164Builds(@TempDir Path root) {
        var cache = new Cache(root.toFile(), root.resolve("jdks").toFile());
        var repo = new ForgeRepo(cache, new MCPConfigRepo(cache, false));
        for (var version : new String[] {
            "1.6.4-9.11.0.884",
            "1.6.4-9.11.1.964",
            "1.6.4-9.11.1.1345"
        }) {
            var mappings = repo.getDefaultMappings(version);
            Assertions.assertEquals("snapshot", mappings.channel(), version);
            Assertions.assertEquals("20130918-1.6.3", mappings.version(), version);
        }
    }
}
