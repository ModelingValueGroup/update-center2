package org.jvnet.hudson.update_center;

import hudson.util.*;
import org.apache.maven.artifact.resolver.*;
import org.codehaus.plexus.*;
import org.codehaus.plexus.component.repository.exception.*;
import org.kohsuke.args4j.*;
import org.kohsuke.args4j.spi.*;
import org.sonatype.nexus.index.*;
import org.sonatype.nexus.index.context.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

@SuppressWarnings("WeakerAccess")
public class MainOnlyDownload {
    @Option(name = "-version", required = true, usage = "The minimumversion number we need to consider")
    public VersionNumber version;

    @Option(name = "-maxVersions", usage = "the maximum number of versions per plugin")
    public int maxVersions = 1;

    @Option(name = "-download", required = true, usage = "Build mirrors.jenkins-ci.org layout")
    public Path download;

    private int  numPlugins;
    private int  numVersions;
    private long numBytes;

    public static void main(String[] args) {
        try {
            new MainOnlyDownload(args).run();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private MainOnlyDownload(String[] args) throws CmdLineException, IOException {
        CmdLineParser cmdLineParser = new CmdLineParser(this);
        cmdLineParser.parseArgument(args);

        if (!Files.isDirectory(download)) {
            System.out.println("creating download dir: " + download.toAbsolutePath());
            Files.createDirectories(download);
        }
        if (Files.list(download).findAny().isPresent()) {
            throw new Error("the download dir is not empty");
        }
    }

    private void run() throws Exception {
        MavenRepository repo = DefaultMavenRepositoryBuilder.getInstance();
        repo = new LimitedMavenRepository(repo, version, maxVersions);
        repo.listHudsonPlugins()
                .stream()
                .parallel()
                .forEach(hpi -> {
                    try {
                        toDownloadDir(hpi);
                        numPlugins++;
                    } catch (Exception e) {
                        System.out.println("SKIPPING " + hpi.latest().getGavId() + " (ERROR: " + e.getLocalizedMessage() + ")");
                    }
                });
        System.out.println();
        System.out.println("number of plugins  = " + numPlugins);
        System.out.println("number of versions = " + numVersions);
        System.out.println("number of Mb       = " + (numBytes / (1024 * 1024)));
    }

    private void toDownloadDir(PluginHistory hpi) throws IOException {
        Plugin plugin = new Plugin(hpi);
        if (plugin.latest != null) {
            System.out.println("=> " + hpi.artifactId + " ");
            String a = hpi.artifactId;
            hpi.artifacts.values().forEach(v -> {
                toDownloadDir(v, Paths.get(a, v.version, a + ".hpi"));
                numVersions++;
            });
        }
    }

    private void toDownloadDir(HPI v, Path rel) {
        Path dst = download.resolve(rel).toAbsolutePath();
        try {
            Path src = v.resolve().toPath();
            if (!Files.isRegularFile(src)) {
                throw new Error("repository corrupted: file does not exists: " + src.toAbsolutePath());
            }
            if (!Files.exists(dst) || !Files.isSameFile(src, dst)) {
                Files.createDirectories(dst.getParent());
                try {
                    Files.createLink(dst, src);
                } catch (Exception e) {
                    // silently try to copy...
                    Files.copy(src, dst);
                }
            }
            numBytes += Files.size(dst);
        } catch (IOException e) {
            throw new Error("could not link or copy the plugin: " + v.artifact.artifactId + " -> " + dst, e);
        }
    }

    static {
        CmdLineParser.registerHandler(VersionNumber.class, VersionNumberOptionHandler.class);
        CmdLineParser.registerHandler(Path.class, PathOptionHandler.class);
    }

    public static class VersionNumberOptionHandler extends OneArgumentOptionHandler<VersionNumber> {
        public VersionNumberOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super VersionNumber> setter) {
            super(parser, option, setter);
        }

        @Override
        protected VersionNumber parse(String argument) throws NumberFormatException {
            return new VersionNumber(argument);
        }
    }

    public static class PathOptionHandler extends OneArgumentOptionHandler<Path> {
        public PathOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super Path> setter) {
            super(parser, option, setter);
        }

        @Override
        protected Path parse(String argument) throws NumberFormatException {
            return Paths.get(argument);
        }
    }

    private static final Comparator<HPI> HPI_DESCENDING = (o1, o2) -> VersionNumber.DESCENDING.compare(o1.getVersion(), o2.getVersion());

    public class LimitedMavenRepository extends MavenRepository {
        private final VersionNumber cap;
        private final int           maxVersions;

        private LimitedMavenRepository(MavenRepository base, VersionNumber cap, int maxVersions) {
            this.maxVersions = maxVersions;
            this.cap = cap;
            setBaseRepository(base);
        }

        @Override
        public Collection<PluginHistory> listHudsonPlugins() throws PlexusContainerException, ComponentLookupException, IOException, UnsupportedExistingLuceneIndexException, AbstractArtifactResolutionException {
            return base.listHudsonPlugins()
                    .stream()
                    .map(this::capAndLimit)
                    .filter(h -> !h.artifacts.isEmpty())
                    .collect(Collectors.toList());
        }

        private PluginHistory capAndLimit(PluginHistory hist) {
            PluginHistory newHist = new PluginHistory(hist.artifactId);
            newHist.groupId.addAll(hist.groupId);
            hist.artifacts.values()
                    .stream()
                    .sorted(HPI_DESCENDING)
                    .filter(this::needInclusion)
                    .limit(maxVersions)
                    .forEach(newHist::addArtifact);
            return newHist;
        }

        private boolean needInclusion(HPI hpi) {
            try {
                VersionNumber v = new VersionNumber(hpi.getRequiredJenkinsVersion());
                return v.compareTo(cap) <= 0;
            } catch (IOException e) {
                System.out.println("ERROR: problem reading " + hpi.toString() + " (skipped)");
                return false;
            }
        }

        @Override
        public TreeMap<VersionNumber, HudsonWar> getHudsonWar() {
            throw new IllegalAccessError();
        }


        @Override
        public File resolve(ArtifactInfo a, String type, String classifier) throws AbstractArtifactResolutionException {
            return base.resolve(a, type, classifier);
        }
    }
}
