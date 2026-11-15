package net.minecraftforge.mcmaven.impl.repo.forge;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks;
import net.minecraftforge.mcmaven.impl.util.*;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.MinecraftVersion;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static net.minecraftforge.mcmaven.impl.repo.forge.PythonForgeSource.safeRelative;

public class LegacyUserdev extends FG2Userdev {

    private final File build;

    private static final List<String> EMBEDDED_MCI_PACKAGES = List.of(
            "paulscode/sound/",
            "com/jcraft/jogg/",
            "com/jcraft/jorbis/",
            "ibxm/"
    );

    private final Task mciEmbeddedLibsTask;

    public LegacyUserdev(File build, ForgeRepo forge, Artifact name) {
        super(build, forge, name, FGVersion.v1);
        this.build = build;

        this.mciEmbeddedLibsTask = Task.named("extract-mci-libraries",
                Task.deps(getMcpChild().getExceptor()),
                () -> extractMciLibraries(getMcpChild().getExceptor().execute())
        );
    }

    private File extractMciLibraries(File joinedMciJar) {
        var output = new File(this.build, "mci-embedded-libs.jar");
        var cache = Util.cache(output)
                .add("mci", joinedMciJar)
                .addKnown("extractor", "mci-libs-v3");

        if (Mavenizer.checkCache(output, cache))
            return output;

        FileUtils.ensureParent(output);
        try (var zin = new ZipInputStream(new FileInputStream(joinedMciJar));
             var zout = new ZipOutputStream(new FileOutputStream(output))) {

            for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                var name = entry.getName();

                if (entry.isDirectory() || !name.endsWith(".class") || !isEmbeddedMciClass(name)) {
                    continue;
                }

                zout.putNextEntry(FileUtils.getStableEntry(name));
                zin.transferTo(zout);
                zout.closeEntry();
            }
            cache.save();
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract MCI libraries from " + joinedMciJar.getAbsolutePath(), e);
        }
    }

    private boolean isEmbeddedMciClass(String name) {
        for (var pkg : EMBEDDED_MCI_PACKAGES) {
            if (name.startsWith(pkg)) return true;
        }
        return false;
    }

    @Override
    public List<File> getClasspath() {
        var classpath = new ArrayList<>(super.getClasspath());
        classpath.add(this.mciEmbeddedLibsTask.execute());

        return classpath;
    }

    @Override
    public Task getSources() {
        var baseSources = super.getSources();

        return Task.named("finalize-legacy-sources",
                Task.deps(baseSources, this.mciEmbeddedLibsTask),
                () -> finalizeArtifact(baseSources.execute(), this.mciEmbeddedLibsTask.execute())
        );
    }

    private File finalizeArtifact(File baseSourceJar, File mciLibsJar) {
        var output = new File(this.build, "final-forge-sources.jar");
        var cache = Util.cache(output)
                .add("sources", baseSourceJar)
                .add("mciLibs", mciLibsJar)
                .addKnown("finalizer", "mci-final-v3");

        if (Mavenizer.checkCache(output, cache))
            return output;

        FileUtils.ensureParent(output);
        try (var zout = new ZipOutputStream(new FileOutputStream(output))) {
            var seen = new HashSet<String>();

            try (var zin = new ZipInputStream(new FileInputStream(baseSourceJar))) {
                for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                    seen.add(entry.getName());
                    zout.putNextEntry(FileUtils.getStableEntry(entry.getName()));
                    zin.transferTo(zout);
                    zout.closeEntry();
                }
            }

            try (var zin = new ZipInputStream(new FileInputStream(mciLibsJar))) {
                for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                    if (!seen.add(entry.getName())) continue;
                    zout.putNextEntry(FileUtils.getStableEntry(entry.getName()));
                    zin.transferTo(zout);
                    zout.closeEntry();
                }
            }

            cache.save();
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to assemble final legacy artifact", e);
        }
    }
}
