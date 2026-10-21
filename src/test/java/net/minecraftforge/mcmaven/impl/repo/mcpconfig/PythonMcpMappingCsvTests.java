/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.siegmar.fastcsv.reader.CsvReader;

public class PythonMcpMappingCsvTests {
    @Test
    void copiesZipEntriesWhoseSizeIsUnknownUntilRead(@TempDir Path temp) throws Exception {
        var source = temp.resolve("source.zip");
        var output = temp.resolve("output.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
            add(zip, "server/data.bin", "legacy server data");
        }

        try (var input = new ZipInputStream(Files.newInputStream(source));
             var target = new ZipOutputStream(Files.newOutputStream(output))) {
            var entry = input.getNextEntry();
            Assertions.assertNotNull(entry);
            Assertions.assertEquals(-1, entry.getSize());
            MCPLegacy.copyZipEntry(input, target, entry);
        }

        try (var zip = new ZipFile(output.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry("server/data.bin"))) {
                Assertions.assertEquals("legacy server data", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void validatesRequiredFilesAndRejectsTruncatedMcpArchives(@TempDir Path temp) throws Exception {
        var valid = temp.resolve("valid-mcp.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(valid))) {
            add(zip, "conf/client.srg", "client mappings");
            add(zip, "conf/server.srg", "server mappings");
            add(zip, "conf/fields.csv", "fields");
            add(zip, "conf/methods.csv", "methods");
            add(zip, "conf/params.csv", "params");
        }
        MCPLegacy.validatePythonMcpArchive(valid.toFile());

        var truncated = temp.resolve("truncated-mcp.zip");
        Files.write(truncated, new byte[] { 'P', 'K', 3, 4 });
        Assertions.assertThrows(ZipException.class, () ->
            MCPLegacy.validatePythonMcpArchive(truncated.toFile())
        );
    }

    @Test
    void packagesMcpCsvFilesForTheGeneratedMappingArtifact(@TempDir Path temp) throws Exception {
        var mcp = temp.resolve("mcp.zip");
        var mappings = temp.resolve("mappings.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(mcp))) {
            add(zip, "conf/fields.csv", "searge,name,side,desc\nfield_1_a,fieldName,0,field description\n");
            add(zip, "conf/methods.csv", "searge,name,side,desc\nfunc_1_a,methodName,0,method description\n");
            add(zip, "conf/params.csv", "param,name,side\np_1_,parameterName,0\n");
        }

        MCPLegacy.writePythonMappingCsv(mcp.toFile(), mappings.toFile());

        try (var zip = new ZipFile(mappings.toFile())) {
            for (var name : new String[] { "fields.csv", "methods.csv", "params.csv" }) {
                Assertions.assertNotNull(zip.getEntry(name));
            }
            try (var input = zip.getInputStream(zip.getEntry("fields.csv"))) {
                Assertions.assertEquals(
                    "searge,name,side,desc\nfield_1_a,fieldName,0,field description\n",
                    new String(input.readAllBytes(), StandardCharsets.UTF_8)
                );
            }
        }
    }

    @Test
    void mergesDuplicateRowsNamesDescriptionsAndSides(@TempDir Path temp) throws Exception {
        var mcp = temp.resolve("mcp.zip");
        var mappings = temp.resolve("mappings.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(mcp))) {
            add(zip, "conf/fields.csv",
                "searge,name,side,desc\n" +
                "field_1_a,,0,\"Short, description\"\n" +
                "field_1_b,otherField,2,\n" +
                "field_1_a,fieldName,1,\"A longer, more detailed description\"\n");
            add(zip, "conf/methods.csv",
                "searge,name,side,desc\n" +
                "func_1_a,methodName,0,method\n" +
                "func_1_a,methodName,1,more detailed method\n");
            add(zip, "conf/params.csv",
                "param,name,side\n" +
                "p_1_,parameterName,2\n" +
                "p_1_,parameterName,0\n");
        }

        MCPLegacy.writePythonMappingCsv(mcp.toFile(), mappings.toFile());

        try (var zip = new ZipFile(mappings.toFile())) {
            var fields = readCsv(zip, "fields.csv");
            Assertions.assertEquals(2, fields.size());
            Assertions.assertEquals("fieldName", fields.get(0).getField("name"));
            Assertions.assertEquals("2", fields.get(0).getField("side"));
            Assertions.assertEquals("A longer, more detailed description", fields.get(0).getField("desc"));

            var methods = readCsv(zip, "methods.csv");
            Assertions.assertEquals(1, methods.size());
            Assertions.assertEquals("2", methods.get(0).getField("side"));
            Assertions.assertEquals("more detailed method", methods.get(0).getField("desc"));

            var params = readCsv(zip, "params.csv");
            Assertions.assertEquals(1, params.size());
            Assertions.assertEquals("2", params.get(0).getField("side"));
        }
    }

    private static java.util.List<de.siegmar.fastcsv.reader.NamedCsvRecord> readCsv(ZipFile zip, String name) throws Exception {
        try (var input = zip.getInputStream(zip.getEntry(name));
             var reader = CsvReader.builder().ofNamedCsvRecord(new java.io.InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.stream().toList();
        }
    }

    private static void add(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
