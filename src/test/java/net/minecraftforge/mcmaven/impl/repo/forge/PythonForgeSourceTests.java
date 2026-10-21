/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PythonForgeSourceTests {
    @Test
    void preparesWrappedPythonSourceArchive(@TempDir Path temp) throws Exception {
        var source = temp.resolve("source.zip").toFile();
        var output = temp.resolve("userdev.jar").toFile();

        try (var zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(source.toPath()))) {
            entry(zip, "forge/fml/fml.json", "{\"libraries\":[]}");
            entry(zip, "forge/fml/conf/version.cfg", "[VERSION]\nMCPVersion = 7.26a\n");
            entry(zip, "forge/fml/conf/packages.csv", "class,package\nGuiScreen,net/minecraft/client/gui\n");
            entry(zip, "forge/fml/conf/patches/minecraft_ff.patch", "fernflower fixes");
            entry(zip, "forge/fml/conf/joined.srg", "CL: awe net/minecraft/src/GuiScreen\nMD: awe/a ()Lawe; net/minecraft/src/GuiScreen/func_1_a ()Lnet/minecraft/src/GuiScreen;\n");
            entry(zip, "forge/fml/conf/fields.csv", "searge,name\nfield_1_a,field\n");
            entry(zip, "forge/fml/conf/methods.csv", "searge,name\nfunc_1_a,method\n");
            entry(zip, "forge/fml/conf/params.csv", "param,name\np_1_,param\n");
            entry(zip, "forge/fml/common/fml_at.cfg", "fml access");
            entry(zip, "forge/common/forge_at.cfg", "forge access");
            entry(zip, "forge/fml/patches/minecraft/example/Example.java.patch", "fml patch");
            entry(zip, "forge/patches/minecraft/example/Example.java.patch", "forge patch");
            entry(zip, "forge/fml/common/example/Example.java", "FML");
            entry(zip, "forge/common/example/Example.java", "Forge");
        }

        var prepared = PythonForgeSource.prepare(output, source);

        Assertions.assertEquals("7.26a", prepared.mcpVersion());
        Assertions.assertEquals("mcp726a.zip", prepared.pythonMcpArchive());
        try (var zip = new ZipFile(prepared.userdev())) {
            Assertions.assertEquals("{\"libraries\":[]}", text(zip, "dev.json"));
            Assertions.assertEquals("Forge", text(zip, "src/main/java/example/Example.java"));
            Assertions.assertEquals("fml access", text(zip, "src/main/resources/fml_at.cfg"));
            Assertions.assertEquals("fernflower fixes", text(zip, "fernflower.patch"));
            Assertions.assertEquals("class,package\nGuiScreen,net/minecraft/client/gui\n", text(zip, "packages.csv"));
            Assertions.assertEquals(
                "CL: awe net/minecraft/client/gui/GuiScreen\nMD: awe/a ()Lawe; net/minecraft/client/gui/GuiScreen/func_1_a ()Lnet/minecraft/client/gui/GuiScreen;\n",
                text(zip, "merged.srg")
            );

            try (var patches = new ZipFile(extract(zip, "fmlpatches.zip", temp.resolve("fmlpatches.zip")))) {
                Assertions.assertEquals("fml patch", text(patches, "example/Example.java.patch"));
            }
            try (var patches = new ZipFile(extract(zip, "forgepatches.zip", temp.resolve("forgepatches.zip")))) {
                Assertions.assertEquals("forge patch", text(patches, "example/Example.java.patch"));
            }
        }
    }

    @Test
    void createsMinimalUserdevConfigWhenLegacySourceHasNoFmlJson(@TempDir Path temp) throws Exception {
        var source = temp.resolve("source.zip").toFile();
        var output = temp.resolve("userdev.jar").toFile();
        try (var zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(source.toPath()))) {
            entry(zip, "forge/fml/conf/version.cfg", "[VERSION]\nMCPVersion = 7.51\n");
            entry(zip, "forge/fml/conf/packages.csv", "class,package\n");
            entry(zip, "forge/fml/conf/patches/minecraft_ff.patch", "fernflower fixes");
            entry(zip, "forge/fml/conf/fields.csv", "searge,name\n");
            entry(zip, "forge/fml/conf/methods.csv", "searge,name\n");
            entry(zip, "forge/fml/conf/params.csv", "param,name\n");
            entry(zip, "forge/fml/common/fml_at.cfg", "fml access");
            entry(zip, "forge/common/forge_at.cfg", "forge access");
        }

        PythonForgeSource.prepare(output, source);

        try (var zip = new ZipFile(output)) {
            var config = text(zip, "dev.json");
            Assertions.assertTrue(config.contains("\"name\":\"com.google.guava:guava:14.0\""));
            Assertions.assertTrue(config.contains("\"name\":\"argo:argo:2.25_fixed\""));
            Assertions.assertTrue(config.contains("\"name\":\"org.bouncycastle:bcprov-jdk15on:1.47\""));
            Assertions.assertTrue(config.contains("\"name\":\"com.paulscode:codecjorbis:20101023\""));
            Assertions.assertTrue(config.contains("\"name\":\"com.paulscode:soundsystem:20120107\""));
        }
    }

    @Test
    void filtersThirdPartySourcesOnlyFromCompilationArchive(@TempDir Path temp) throws Exception {
        var source = temp.resolve("sources.jar").toFile();
        try (var zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(source.toPath()))) {
            entry(zip, "argo/jdom/JsonNodeBuilders.java", "Argo source");
            entry(zip, "com/jcraft/jogg/Buffer.java", "JOrbis dependency source");
            entry(zip, "com/jcraft/jorbis/Comment.java", "JOrbis source");
            entry(zip, "org/bouncycastle/crypto/io/CipherInputStream.java", "BouncyCastle source");
            entry(zip, "paulscode/sound/SoundSystem.java", "Paulscode source");
            entry(zip, "paulscode/sound/libraries/LibraryJavaSound.java", "Library source");
            entry(zip, "paulscode/sound/libraries/LibraryJavaSound$MixerRanking.java", "Library inner source");
            entry(zip, "paulscode/sound/libraries/LibraryLWJGLOpenAL.java", "OpenAL library source");
            entry(zip, "paulscode/sound/codecs/CodecWav.java", "WAV codec source");
            entry(zip, "paulscode/sound/codecs/CodecJOrbis.java", """
                protected InputStream openInputStream()
                {
                }
                private boolean readHeader()
                {
                }
                """);
            entry(zip, "net/minecraftforge/client/ModCompatibilityClient.java", """
                    SoundSystemConfig.setCodec("xm",  CodecIBXM.class);
                    SoundSystemConfig.setCodec("s3m", CodecIBXM.class);
                    SoundSystemConfig.setCodec("mod", CodecIBXM.class);
                """);
            entry(zip, "paulscode/sound/codecs/CodecIBXM.java", "Bundled source without external class");
            entry(zip, "net/minecraft/Example.java", "Minecraft source");
            entry(zip, "argo/data.txt", "non-Java resource");
        }

        var filtered = PythonForgeSource.filterThirdPartySources(
            temp.toFile(),
            net.minecraftforge.mcmaven.impl.util.Task.named("sources", () -> source)
        ).execute();

        try (var zip = new ZipFile(filtered)) {
            Assertions.assertNull(zip.getEntry("argo/jdom/JsonNodeBuilders.java"));
            Assertions.assertNull(zip.getEntry("com/jcraft/jogg/Buffer.java"));
            Assertions.assertNull(zip.getEntry("com/jcraft/jorbis/Comment.java"));
            Assertions.assertNull(zip.getEntry("org/bouncycastle/crypto/io/CipherInputStream.java"));
            Assertions.assertNull(zip.getEntry("paulscode/sound/SoundSystem.java"));
            Assertions.assertNull(zip.getEntry("paulscode/sound/libraries/LibraryJavaSound.java"));
            Assertions.assertNull(zip.getEntry("paulscode/sound/libraries/LibraryJavaSound$MixerRanking.java"));
            Assertions.assertNull(zip.getEntry("paulscode/sound/libraries/LibraryLWJGLOpenAL.java"));
            Assertions.assertNull(zip.getEntry("paulscode/sound/codecs/CodecWav.java"));
            var codecSource = text(zip, "paulscode/sound/codecs/CodecJOrbis.java");
            Assertions.assertTrue(codecSource.contains("protected InputStream openInputStream() throws IOException"));
            Assertions.assertTrue(codecSource.contains("private boolean readHeader() throws IOException"));
            var forgeSource = text(zip, "net/minecraftforge/client/ModCompatibilityClient.java");
            Assertions.assertTrue(forgeSource.contains("catch (paulscode.sound.SoundSystemException e)"));
            Assertions.assertTrue(forgeSource.contains("Failed to register IBXM codecs"));
            Assertions.assertEquals("Bundled source without external class", text(zip, "paulscode/sound/codecs/CodecIBXM.java"));
            Assertions.assertEquals("Minecraft source", text(zip, "net/minecraft/Example.java"));
            Assertions.assertEquals("non-Java resource", text(zip, "argo/data.txt"));
        }
    }

    @Test
    void rejectsInvalidPathsInSourceArchive(@TempDir Path temp) throws Exception {
        var source = temp.resolve("source.zip").toFile();
        try (var zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(source.toPath()))) {
            entry(zip, "forge/fml/fml.json", "{\"libraries\":[]}");
            entry(zip, "forge/fml/conf/version.cfg", "[VERSION]\nMCPVersion = 8.11\n");
            entry(zip, "forge/fml/conf/packages.csv", "class,package\n");
            entry(zip, "forge/fml/conf/fields.csv", "searge,name\n");
            entry(zip, "forge/fml/conf/methods.csv", "searge,name\n");
            entry(zip, "forge/fml/conf/params.csv", "param,name\n");
            entry(zip, "forge/fml/common/fml_at.cfg", "fml access");
            entry(zip, "forge/common/forge_at.cfg", "forge access");
            entry(zip, "forge/patches/minecraft/../escape.patch", "invalid");
        }

        Assertions.assertThrows(IllegalStateException.class, () ->
            PythonForgeSource.prepare(temp.resolve("userdev.jar").toFile(), source)
        );
    }

    @Test
    void recognizesPythonEraForgeVersions() {
        Assertions.assertTrue(ForgeRepo.isSupported("1.6.4-9.11.1.1345"));
        Assertions.assertFalse(ForgeRepo.isSupported("1.0-1.0"));
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static File extract(ZipFile zip, String name, Path output) throws Exception {
        try (var input = zip.getInputStream(zip.getEntry(name))) {
            java.nio.file.Files.copy(input, output);
        }
        return output.toFile();
    }

    private static String text(ZipFile zip, String name) throws Exception {
        try (var input = zip.getInputStream(zip.getEntry(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
