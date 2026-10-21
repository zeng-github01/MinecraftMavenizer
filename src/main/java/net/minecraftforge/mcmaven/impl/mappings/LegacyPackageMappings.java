/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.siegmar.fastcsv.reader.CsvReader;

public final class LegacyPackageMappings {
    private static final Pattern LEGACY_CLASS = Pattern.compile("net/minecraft/src/(\\w+)");
    private static final Pattern LEGACY_PATCH_CLASS = Pattern.compile("net[\\\\/]minecraft[\\\\/]src[\\\\/](\\w+)");

    private LegacyPackageMappings() {}

    public static byte[] repackage(byte[] mappings, byte[] packages) throws IOException {
        var classPackages = readPackages(packages);
        var input = new String(mappings, StandardCharsets.UTF_8);
        return replaceClasses(input, classPackages, LEGACY_CLASS).getBytes(StandardCharsets.UTF_8);
    }

    public static String repackagePatch(String patch, byte[] packages) throws IOException {
        var classPackages = readPackages(packages);
        patch = replaceClasses(patch, classPackages, LEGACY_PATCH_CLASS);

        var lines = patch.split("\n", -1);
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (!line.startsWith("--- ") && !line.startsWith("+++ "))
                continue;

            var pathStart = 4;
            var pathEnd = line.indexOf('\t', pathStart);
            if (pathEnd == -1)
                pathEnd = line.length();

            var path = line.substring(pathStart, pathEnd).replace('\\', '/');
            var slash = path.indexOf('/');
            if (slash != -1 && (path.substring(0, slash).equals("minecraft") || path.substring(0, slash).equals("minecraft_patched")))
                path = path.substring(slash + 1);
            lines[i] = line.substring(0, pathStart) + path + line.substring(pathEnd);
        }
        return String.join("\n", lines);
    }

    private static Map<String, String> readPackages(byte[] packages) throws IOException {
        var classPackages = new HashMap<String, String>();
        try (var reader = CsvReader.builder().ofNamedCsvRecord(
            new InputStreamReader(new ByteArrayInputStream(packages), StandardCharsets.UTF_8)
        )) {
            for (var row : reader)
                classPackages.put(row.getField("class"), row.getField("package"));
        }
        return classPackages;
    }

    private static String replaceClasses(String input, Map<String, String> classPackages, Pattern pattern) {
        var matcher = pattern.matcher(input);
        var output = new StringBuffer(input.length());
        while (matcher.find()) {
            var targetPackage = classPackages.get(matcher.group(1));
            if (targetPackage != null)
                matcher.appendReplacement(output, Matcher.quoteReplacement(targetPackage + '/' + matcher.group(1)));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
