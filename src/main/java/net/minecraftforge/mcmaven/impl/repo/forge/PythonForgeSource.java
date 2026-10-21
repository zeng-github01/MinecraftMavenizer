/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.mappings.LegacyPackageMappings;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.file.FileUtils;

final class PythonForgeSource {
    private static final String ROOT = "forge/";
    private static final String FML = ROOT + "fml/";
    private static final String FORMAT = "python-src-v8";
    private static final String[] THIRD_PARTY_SOURCE_PREFIXES = {
        "argo/",
        "com/jcraft/jogg/",
        "com/jcraft/jorbis/",
        "org/bouncycastle/",
        "paulscode/sound/SoundSystem",
        "paulscode/sound/libraries/",
        "paulscode/sound/codecs/CodecWav"
    };

    record Prepared(File userdev, String mcpArchive, String mcpVersion) {}

    private PythonForgeSource() {}

    static Prepared prepare(File output, File sourceZip) {
        try (var source = new ZipFile(sourceZip)) {
            var version = readMcpVersion(source);
            var mcpArchive = "mcp" + version.replace(".", "") + ".zip";
            var cache = Util.cache(output)
                .add("source", sourceZip)
                .addKnown("format", FORMAT);

            if (Mavenizer.checkCache(output, cache))
                return new Prepared(output, mcpArchive, version);

            var entries = new LinkedHashMap<String, byte[]>();
            if (!addOptional(source, entries, "dev.json", FML + "fml.json"))
                entries.put("dev.json", """
                    {"id":"FML","libraries":[
                      {"name":"com.google.guava:guava:14.0"},
                      {"name":"argo:argo:2.25_fixed"},
                      {"name":"org.bouncycastle:bcprov-jdk15on:1.47"},
                      {"name":"com.paulscode:codecjorbis:20101023"},
                      {"name":"com.paulscode:codecwav:20101023"},
                      {"name":"com.paulscode:libraryjavasound:20101123"},
                      {"name":"com.paulscode:librarylwjglopenal:20100824"},
                      {"name":"com.paulscode:soundsystem:20120107"}
                    ]}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var packages = readRequired(source, FML + "conf/packages.csv");
            entries.put("packages.csv", packages);
            addRequired(source, entries, "fernflower.patch", FML + "conf/patches/minecraft_ff.patch");
            var mappings = readOptional(source, FML + "conf/joined.srg");
            if (mappings != null)
                entries.put("merged.srg", LegacyPackageMappings.repackage(mappings, packages));
            for (var csv : new String[] { "fields.csv", "methods.csv", "params.csv" })
                addRequired(source, entries, csv, FML + "conf/" + csv);

            addRequired(source, entries, "src/main/resources/fml_at.cfg", FML + "common/fml_at.cfg");
            addRequired(source, entries, "src/main/resources/forge_at.cfg", ROOT + "common/forge_at.cfg");
            addPatchArchive(source, entries, "fmlpatches.zip", FML + "patches/minecraft/");
            addPatchArchive(source, entries, "forgepatches.zip", ROOT + "patches/minecraft/");

            for (var prefix : new String[] {
                FML + "client/", FML + "common/", ROOT + "client/", ROOT + "common/"
            })
                addSourceTree(source, entries, prefix);

            FileUtils.ensureParent(output);
            try (var out = new ZipOutputStream(new FileOutputStream(output))) {
                for (var entry : entries.entrySet()) {
                    out.putNextEntry(FileUtils.getStableEntry(entry.getKey()));
                    out.write(entry.getValue());
                    out.closeEntry();
                }
            }

            cache.save();
            return new Prepared(output, mcpArchive, version);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare Python-era Forge source archive " + sourceZip.getAbsolutePath(), e);
        }
    }

    static String readMcpVersion(File sourceZip) {
        try (var source = new ZipFile(sourceZip)) {
            return readMcpVersion(source);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Python-era Forge source archive " + sourceZip.getAbsolutePath(), e);
        }
    }

    static Task filterThirdPartySources(File build, Task sources) {
        var output = new File(build, "sources-for-recompile.jar");
        return Task.named("filter-third-party-sources", Task.deps(sources), () -> {
            var input = sources.execute();
            var cache = Util.cache(output)
                .add("input", input)
                .addKnown("format", "python-third-party-source-filter-v7");

            if (Mavenizer.checkCache(output, cache))
                return output;

            FileUtils.ensureParent(output);
            try (var zin = new ZipInputStream(new FileInputStream(input));
                 var zout = new ZipOutputStream(new FileOutputStream(output))) {
                for (ZipEntry entry; (entry = zin.getNextEntry()) != null;) {
                    var name = entry.getName();
                    if (name.endsWith(".java") && isThirdPartySource(name))
                        continue;

                    zout.putNextEntry(FileUtils.getStableEntry(name));
                    if ("paulscode/sound/codecs/CodecJOrbis.java".equals(name)) {
                        zout.write(fixCodecJOrbisSource(zin.readAllBytes()));
                    } else if ("net/minecraftforge/client/ModCompatibilityClient.java".equals(name)) {
                        zout.write(fixModCompatibilityClientSource(zin.readAllBytes()));
                    } else {
                        zin.transferTo(zout);
                    }
                    zout.closeEntry();
                }
            }

            cache.save();
            return output;
        });
    }

    private static boolean isThirdPartySource(String name) {
        for (var prefix : THIRD_PARTY_SOURCE_PREFIXES) {
            if (name.startsWith(prefix))
                return true;
        }
        return false;
    }

    private static byte[] fixCodecJOrbisSource(byte[] source) {
        var text = new String(source, java.nio.charset.StandardCharsets.UTF_8);
        // These checked exceptions are missing from methods in the decompiled source.
        text = replaceRequired(text, "protected InputStream openInputStream()", "protected InputStream openInputStream() throws IOException");
        text = replaceRequired(text, "private boolean readHeader()", "private boolean readHeader() throws IOException");
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] fixModCompatibilityClientSource(byte[] source) {
        var text = new String(source, java.nio.charset.StandardCharsets.UTF_8);
        text = replaceRequired(text,
            "SoundSystemConfig.setCodec(\"xm\",  CodecIBXM.class);",
            "try { SoundSystemConfig.setCodec(\"xm\",  CodecIBXM.class); } catch (paulscode.sound.SoundSystemException e) { throw new IllegalStateException(\"Failed to register IBXM codecs\", e); }");
        text = replaceRequired(text,
            "SoundSystemConfig.setCodec(\"s3m\", CodecIBXM.class);",
            "try { SoundSystemConfig.setCodec(\"s3m\", CodecIBXM.class); } catch (paulscode.sound.SoundSystemException e) { throw new IllegalStateException(\"Failed to register IBXM codecs\", e); }");
        text = replaceRequired(text,
            "SoundSystemConfig.setCodec(\"mod\", CodecIBXM.class);",
            "try { SoundSystemConfig.setCodec(\"mod\", CodecIBXM.class); } catch (paulscode.sound.SoundSystemException e) { throw new IllegalStateException(\"Failed to register IBXM codecs\", e); }");
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String replaceRequired(String text, String original, String replacement) {
        var index = text.indexOf(original);
        if (index < 0 || text.indexOf(original, index + original.length()) >= 0)
            throw new IllegalStateException("Unexpected decompiled Python-era source while applying compile fix: " + original);
        return text.substring(0, index) + replacement + text.substring(index + original.length());
    }

    private static String readMcpVersion(ZipFile source) throws IOException {
        var entry = source.getEntry(FML + "conf/version.cfg");
        if (entry == null)
            throw new IllegalStateException("Python-era Forge source archive is missing " + FML + "conf/version.cfg");

        var properties = new Properties();
        try (var input = source.getInputStream(entry)) {
            properties.load(input);
        }

        var version = properties.getProperty("MCPVersion");
        if (version == null || version.isBlank())
            throw new IllegalStateException("Missing MCPVersion in " + FML + "conf/version.cfg");
        version = version.trim();
        if (!version.matches("\\d+(?:\\.\\d+)*"))
            throw new IllegalStateException("Invalid MCPVersion in " + FML + "conf/version.cfg: " + version);
        return version;
    }

    private static void addRequired(ZipFile source, Map<String, byte[]> entries, String outputName, String sourceName) throws IOException {
        if (!addOptional(source, entries, outputName, sourceName))
            throw new IllegalStateException("Python-era Forge source archive is missing " + sourceName);
    }

    private static boolean addOptional(ZipFile source, Map<String, byte[]> entries, String outputName, String sourceName) throws IOException {
        var data = readOptional(source, sourceName);
        if (data == null)
            return false;
        entries.put(outputName, data);
        return true;
    }

    private static byte[] readRequired(ZipFile source, String name) throws IOException {
        var data = readOptional(source, name);
        if (data == null)
            throw new IllegalStateException("Python-era Forge source archive is missing " + name);
        return data;
    }

    private static byte[] readOptional(ZipFile source, String name) throws IOException {
        var entry = source.getEntry(name);
        if (entry == null || entry.isDirectory())
            return null;

        try (var input = source.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static void addPatchArchive(ZipFile source, Map<String, byte[]> entries, String outputName, String prefix) throws IOException {
        var patches = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(patches)) {
            for (var enumeration = source.entries(); enumeration.hasMoreElements();) {
                var entry = enumeration.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix) || !entry.getName().endsWith(".patch"))
                    continue;

                var name = safeRelative(entry.getName().substring(prefix.length()));
                try (var input = source.getInputStream(entry)) {
                    out.putNextEntry(FileUtils.getStableEntry(name));
                    input.transferTo(out);
                    out.closeEntry();
                }
            }
        }

        entries.put(outputName, patches.toByteArray());
    }

    private static void addSourceTree(ZipFile source, Map<String, byte[]> entries, String prefix) throws IOException {
        for (var enumeration = source.entries(); enumeration.hasMoreElements();) {
            var entry = enumeration.nextElement();
            if (entry.isDirectory() || !entry.getName().startsWith(prefix))
                continue;

            var relative = safeRelative(entry.getName().substring(prefix.length()));
            var output = (relative.endsWith(".java") ? "src/main/java/" : "src/main/resources/") + relative;
            try (var input = source.getInputStream(entry)) {
                entries.put(output, input.readAllBytes());
            }
        }
    }

    private static String safeRelative(String name) {
        var normalized = name.replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains(":"))
            throw new IllegalStateException("Invalid path in Python-era Forge source archive: " + name);
        for (var segment : normalized.split("/")) {
            if (segment.equals(".") || segment.equals(".."))
                throw new IllegalStateException("Invalid path in Python-era Forge source archive: " + name);
        }
        return normalized;
    }
}
