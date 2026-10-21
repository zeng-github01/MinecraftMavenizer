/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.mappings.LegacyPackageMappings;
import net.minecraftforge.mcmaven.impl.util.ContextualPatch;
import net.minecraftforge.util.file.FileUtils;

final class LegacyFernflowerPatches {
    private static final Pattern PATCH_SECTION = Pattern.compile("(?m)(?=^diff -r -U \\d+ )");

    private LegacyFernflowerPatches() {}

    static File apply(File input, File output, byte[] patchData, byte[] packageMappings) throws IOException {
        var patch = LegacyPackageMappings.repackagePatch(new String(patchData, StandardCharsets.UTF_8), packageMappings);
        var changes = new HashMap<String, List<String>>();

        try (var zip = new ZipFile(input)) {
            var context = new ContextualPatch.IContextProvider() {
                    @Override
                    public List<String> getData(String target) {
                        var changed = changes.get(target);
                        if (changed != null)
                            return changed;

                        var entry = zip.getEntry(target);
                        if (entry == null)
                            return null;

                        try (var stream = zip.getInputStream(entry)) {
                            return ContextualPatch.context(stream).getData();
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to read Fernflower patch target " + target, e);
                        }
                    }

                    @Override
                    public void setData(String target, List<String> data) {
                        changes.put(target, data);
                    }
                };
            var applied = 0;
            for (var section : PATCH_SECTION.split(patch)) {
                if (section.isBlank())
                    continue;

                var candidate = ContextualPatch.create(section)
                    .setAccessC14N(true)
                    .setMaxFuzz(2);
                var preview = candidate.patch(true, context);
                if (preview.isEmpty())
                    throw new IllegalStateException("Invalid Python-era Fernflower patch section");
                if (preview.stream().anyMatch(report -> !report.getStatus().isSuccess())) {
                    Mavenizer.LOGGER.debug("Skipping incompatible Python-era Fernflower patch for " + preview.getFirst().getTarget());
                    continue;
                }

                var reports = candidate.patch(false, context);
                for (var report : reports) {
                    if (!report.getStatus().isSuccess())
                        throw new IllegalStateException("Failed to apply Python-era Fernflower patch to " + report.getTarget(), report.getFailure());
                }
                applied++;
            }
            if (applied == 0)
                throw new IllegalStateException("No Python-era Fernflower patches matched the decompiled sources");
            for (var target : changes.keySet()) {
                if (zip.getEntry(target) == null)
                    throw new IllegalStateException("Fernflower patch targeted missing source " + target);
            }

            FileUtils.ensureParent(output);
            try (var out = new ZipOutputStream(new FileOutputStream(output))) {
                for (var entries = zip.entries(); entries.hasMoreElements();) {
                    var entry = entries.nextElement();
                    var name = entry.getName();
                    var replacement = changes.get(name);
                    var target = FileUtils.getStableEntry(name);
                    target.setTime(entry.getTime());
                    out.putNextEntry(target);
                    if (replacement == null) {
                        try (var stream = zip.getInputStream(entry)) {
                            stream.transferTo(out);
                        }
                    } else {
                        for (var i = 0; i < replacement.size(); i++) {
                            if (i != 0)
                                out.write('\n');
                            out.write(replacement.get(i).getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    out.closeEntry();
                }
            }
        }
        return output;
    }
}
