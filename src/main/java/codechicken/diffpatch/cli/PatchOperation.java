package codechicken.diffpatch.cli;

import codechicken.diffpatch.match.FuzzyLineMatcher;
import codechicken.diffpatch.patch.Patcher;
import codechicken.diffpatch.util.*;
import codechicken.diffpatch.util.archiver.ArchiveFormat;
import codechicken.diffpatch.util.archiver.ArchiveReader;
import codechicken.diffpatch.util.archiver.ArchiveWriter;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static codechicken.diffpatch.util.Utils.*;
import static org.apache.commons.lang3.StringUtils.appendIfMissing;
import static org.apache.commons.lang3.StringUtils.removeStart;

/**
 * Created by covers1624 on 11/8/20.
 */
public class PatchOperation extends CliOperation<PatchOperation.PatchesSummary> {

    private final boolean summary;
    private final InputPath basePath;
    private final InputPath patchesPath;
    private final String bPrefix;
    private final OutputPath outputPath;
    private final OutputPath rejectsPath;
    private final float minFuzz;
    private final int maxOffset;
    private final PatchMode mode;
    private final String patchesPrefix;
    private final String lineEnding;

    @Deprecated
    public PatchOperation(PrintStream logger, Consumer<PrintStream> helpCallback, boolean verbose, boolean summary, InputPath basePath, InputPath patchesPath, String aPrefix, String bPrefix, OutputPath outputPath, OutputPath rejectsPath, float minFuzz, int maxOffset, PatchMode mode, String patchesPrefix) {
        this(logger, helpCallback, verbose ? Level.ALL : Level.WARNING, summary, basePath, patchesPath, aPrefix, bPrefix, outputPath, rejectsPath, minFuzz, maxOffset, mode, patchesPrefix, System.lineSeparator());
    }
    private PatchOperation(PrintStream logger, Consumer<PrintStream> helpCallback, Level level, boolean summary, InputPath basePath, InputPath patchesPath, String aPrefix, String bPrefix, OutputPath outputPath, OutputPath rejectsPath, float minFuzz, int maxOffset, PatchMode mode, String patchesPrefix, String lineEnding) {
        super(logger, helpCallback, level);
        this.summary = summary;
        this.basePath = basePath;
        this.patchesPath = patchesPath;
        this.bPrefix = bPrefix;
        this.outputPath = outputPath;
        this.rejectsPath = rejectsPath;
        this.minFuzz = minFuzz;
        this.maxOffset = maxOffset;
        this.mode = mode;
        this.patchesPrefix = patchesPrefix;
        this.lineEnding = lineEnding;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Result<PatchesSummary> operate() throws IOException {
        if (!basePath.exists()) {
            log(Level.SEVERE, "Err: Base file doesn't exist.");
            return new Result<>(-1);
        }
        if (!patchesPath.exists()) {
            log(Level.SEVERE, "Err: Patch file doesn't exist.");
            return new Result<>(-1);
        }

        FileCollector outputCollector = new FileCollector();
        FileCollector rejectCollector = new FileCollector();
        PatchesSummary summary = new PatchesSummary();
        boolean patchSuccess;

        //Base path and patch path are both singular files.
        if (basePath.isFile() && patchesPath.isFile() && basePath.getFormat() == null && patchesPath.getFormat() == null) {
            if (outputPath.getFormat() != null) {
                log(Level.SEVERE, "Err: Can't specify output format when patching regular file.");
                printHelp();
                return new Result<>(-1);
            }
            if (outputPath.getType().isPath()) {
                Path out = outputPath.toPath();
                if (Files.exists(out) && !Files.isRegularFile(out)) {
                    log(Level.SEVERE, "Err: Output already exists and is not a file.");
                    printHelp();
                    return new Result<>(-1);
                }
            }
            if (rejectsPath.exists()) {
                if (rejectsPath.getFormat() != null) {
                    log(Level.SEVERE, "Err: Can't specify reject format when patching regular file.");
                    printHelp();
                    return new Result<>(-1);
                }
                if (rejectsPath.getType().isPath()) {
                    Path out = rejectsPath.toPath();
                    if (Files.exists(out) && !Files.isRegularFile(out)) {
                        log(Level.SEVERE, "Err: Reject already exists and is not a file.");
                        printHelp();
                        return new Result<>(-1);
                    }
                }
            }

            PatchFile patchFile = PatchFile.fromLines(patchesPath.toString(), patchesPath.readAllLines(), true);
            boolean success = doPatch(outputCollector, rejectCollector, summary, basePath.toString(), basePath.readAllLines(), patchFile, minFuzz, maxOffset, mode);
            List<String> output = outputCollector.getSingleFile();
            List<String> reject = rejectCollector.getSingleFile();
            try (PrintWriter out = new PrintWriter(outputPath.open())) {
                out.println(String.join(lineEnding, output));
            }

            if (rejectsPath.exists() && !reject.isEmpty()) {
                try (PrintWriter out = new PrintWriter(rejectsPath.open())) {
                    out.println(String.join(lineEnding, reject + lineEnding));
                }
            }
            if (this.summary) {
                summary.print(logger, true);
            }
            return new Result<>(success ? 0 : 1, summary);
        }

        if (outputPath.getType().isPipe() && outputPath.getFormat() == null) {
            log(Level.SEVERE, "Err: Output detected as pipe but no format is specified.");
            printHelp();
            return new Result<>(-1);
        }

        if (outputPath.getType().isPath()) {
            Path out = outputPath.toPath();
            if (outputPath.getFormat() != null) {
                if (Files.exists(out) && !Files.isRegularFile(out)) {
                    log(Level.SEVERE, "Err: Output already exists and is not a file.");
                    printHelp();
                    return new Result<>(-1);
                }

            } else {
                if (Files.exists(out) && !Files.isDirectory(out)) {
                    log(Level.SEVERE, "Err: Output already exists and is not a directory.");
                    printHelp();
                    return new Result<>(-1);
                }
            }
        }

        //Both inputs are still files, both must be archives.
        if (basePath.isFile() && patchesPath.isFile()) {
            if (basePath.getFormat() == null) {
                log(Level.SEVERE, "Err: Base path is in an unknown archive format");
                printHelp();
                return new Result<>(-1);
            }
            if (patchesPath.getFormat() == null) {
                log(Level.SEVERE, "Err: Patches path is in an unknown archive format");
                printHelp();
                return new Result<>(-1);
            }

            try (ArchiveReader baseReader = basePath.getFormat().createReader(basePath.open())) {
                try (ArchiveReader patchesReader = patchesPath.getFormat().createReader(patchesPath.open(), patchesPrefix)) {
                    patchSuccess = doPatch(outputCollector, rejectCollector, summary, baseReader.getEntries(), patchesReader.getEntries(), sneakF(baseReader::readLines), sneakF(patchesReader::readLines), minFuzz, maxOffset, mode);
                }
            }
        } else {
            //Both inputs are directories.
            if (!basePath.isFile() && !patchesPath.isFile()) {
                Map<String, Path> baseIndex = indexChildren(basePath.toPath());
                Map<String, Path> patchIndex = indexChildren(patchesPath.toPath(), patchesPrefix);
                patchSuccess = doPatch(outputCollector, rejectCollector, summary, baseIndex.keySet(), patchIndex.keySet(), sneakF(e -> Files.readAllLines(baseIndex.get(e))), sneakF(e -> Files.readAllLines(patchIndex.get(e))), minFuzz, maxOffset, mode);
            } else {
                //One input is a directory, the other is a file.
                Set<String> baseIndex;
                Function<String, List<String>> baseFunc;
                Set<String> patchIndex;
                Function<String, List<String>> patchFunc;
                if (!basePath.isFile()) {
                    if (patchesPath.getFormat() == null) {
                        log(Level.SEVERE, "Err: Patches file is in an unknown format, whilst Base file is a directory.");
                        printHelp();
                        return new Result<>(-1);
                    }
                    Map<String, Path> pathIndex = indexChildren(basePath.toPath());
                    baseIndex = pathIndex.keySet();
                    baseFunc = sneakF(e -> Files.readAllLines(pathIndex.get(e)));
                    //ArchiveReaders should Greedy load all data inside the archive into memory, this is safe.
                    try (ArchiveReader reader = patchesPath.getFormat().createReader(patchesPath.open(), patchesPrefix)) {
                        patchIndex = reader.getEntries();
                        patchFunc = sneakF(reader::readLines);
                    }
                } else {
                    if (basePath.getFormat() == null) {
                        log(Level.SEVERE, "Err: Base file is in an unknown format, whilst Patches file is a directory.");
                        printHelp();
                        return new Result<>(-1);
                    }
                    Map<String, Path> pathIndex = indexChildren(patchesPath.toPath(), patchesPrefix);
                    patchIndex = pathIndex.keySet();
                    patchFunc = sneakF(e -> Files.readAllLines(pathIndex.get(e)));
                    //ArchiveReaders should Greedy load all data inside the archive into memory, this is safe.
                    try (ArchiveReader reader = basePath.getFormat().createReader(basePath.open())) {
                        baseIndex = reader.getEntries();
                        baseFunc = sneakF(reader::readLines);
                    }
                }
                patchSuccess = doPatch(outputCollector, rejectCollector, summary, baseIndex, patchIndex, baseFunc, patchFunc, minFuzz, maxOffset, mode);
            }
        }

        if (outputPath.getFormat() != null) {
            try (ArchiveWriter writer = outputPath.getFormat().createWriter(outputPath.open())) {
                for (Map.Entry<String, List<String>> entry : outputCollector.get().entrySet()) {
                    String file = String.join(lineEnding, entry.getValue());
                    writer.writeEntry(entry.getKey(), file.getBytes(StandardCharsets.UTF_8));
                }
            }
        } else {
            if (Files.exists(outputPath.toPath())) {
                Utils.deleteFolder(outputPath.toPath());
            }
            for (Map.Entry<String, List<String>> entry : outputCollector.get().entrySet()) {
                Path path = outputPath.toPath().resolve(entry.getKey());
                String file = String.join(lineEnding, entry.getValue());
                Files.write(makeParentDirs(path), file.getBytes(StandardCharsets.UTF_8));
            }
        }

        if (!rejectsPath.getType().isNull()) {
            if (rejectsPath.getFormat() != null) {
                try (ArchiveWriter writer = rejectsPath.getFormat().createWriter(rejectsPath.open())) {
                    for (Map.Entry<String, List<String>> entry : rejectCollector.get().entrySet()) {
                        String file = String.join(lineEnding, entry.getValue()) + lineEnding;
                        writer.writeEntry(entry.getKey(), file.getBytes(StandardCharsets.UTF_8));
                    }
                }
            } else {
                if (Files.exists(rejectsPath.toPath())) {
                    Utils.deleteFolder(rejectsPath.toPath());
                }
                for (Map.Entry<String, List<String>> entry : rejectCollector.get().entrySet()) {
                    Path path = rejectsPath.toPath().resolve(entry.getKey());
                    String file = String.join(lineEnding, entry.getValue());
                    Files.write(makeParentDirs(path), file.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        if (this.summary) {
            summary.print(logger, false);
        }
        return new Result<>(patchSuccess ? 0 : 1, summary);
    }

    public boolean doPatch(FileCollector oCollector, FileCollector rCollector, PatchesSummary summary, Set<String> bEntries, Set<String> pEntries, Function<String, List<String>> bFunc, Function<String, List<String>> pFunc, float minFuzz, int maxOffset, PatchMode mode) {
        Map<String, PatchFile> patchFiles = pEntries.stream()
                .map(e -> PatchFile.fromLines(e, pFunc.apply(e), true))
                .collect(Collectors.toMap(e -> {
                            if (e.patchedPath == null) {
                                return e.name.substring(0, e.name.lastIndexOf(".patch"));
                            } else if (e.patchedPath.startsWith("b/")) {
                                return e.patchedPath.substring(2);
                            } else if (e.patchedPath.startsWith(bPrefix)) {
                                return removeStart(e.patchedPath.substring(bPrefix.length()), "/");
                            }
                            return e.patchedPath;
                        },
                        Function.identity()));

        List<String> notPatched = bEntries.stream().filter(e -> !patchFiles.containsKey(e)).sorted().collect(Collectors.toList());
        List<String> patchedFiles = bEntries.stream().filter(patchFiles::containsKey).sorted().collect(Collectors.toList());
        List<String> removedFiles = patchFiles.keySet().stream().filter(e -> !bEntries.contains(e)).sorted().collect(Collectors.toList());

        boolean result = true;
        for (String file : notPatched) {
            summary.unchangedFiles++;
            oCollector.consume(file, bFunc.apply(file));
        }

        for (String file : patchedFiles) {
            summary.changedFiles++;
            PatchFile patchFile = patchFiles.get(file);
            List<String> baseLines = bFunc.apply(file);
            result &= doPatch(oCollector, rCollector, summary, file, baseLines, patchFile, minFuzz, maxOffset, mode);
        }

        for (String file : removedFiles) {
            summary.missingFiles++;
            PatchFile patchFile = patchFiles.get(file);
            List<String> lines = new ArrayList<>(patchFile.toLines(false));
            lines.add(0, "++++ Target missing");
            log(Level.WARNING, "Missing patch target for %s", patchFile.name);
            rCollector.consume(patchFile.name, lines);
            result = false;
        }

        return result;
    }

    public boolean doPatch(FileCollector outputCollector, FileCollector rejectCollector, PatchesSummary summary, String baseName, List<String> base, PatchFile patchFile, float minFuzz, int maxOffset, PatchMode mode) {
        Patcher patcher = new Patcher(patchFile, base, minFuzz, maxOffset);
        log(Level.FINE, "Patching: " + baseName);
        List<Patcher.Result> results = patcher.patch(mode).collect(Collectors.toList());
        List<String> rejectLines = new ArrayList<>();
        boolean first = true;
        for (int i = 0; i < results.size(); i++) {
            Patcher.Result result = results.get(i);
            if (result.mode != null) {
                switch (result.mode) {
                    case EXACT:
                        summary.exactMatches++;
                        summary.overallQuality += 100;
                        break;
                    case ACCESS:
                        summary.accessMatches++;
                        summary.overallQuality += 100;
                        break;
                    case OFFSET:
                        summary.offsetMatches++;
                        summary.overallQuality += 100;
                        break;
                    case FUZZY:
                        summary.fuzzyMatches++;
                        summary.overallQuality += (result.fuzzyQuality * 100);
                        break;
                }
            } else {
                summary.failedMatches++;
            }
            
            if (!result.success) {
                if (!first) {
                    rejectLines.add("");
                } else if (Level.FINE.intValue() < this.level.intValue()) {
                	log(Level.WARNING, "Patching: " + baseName);
                }
                log(Level.WARNING, " Hunk %d: %s", i, result.summary());
                first = false;
                rejectLines.add("++++ REJECTED HUNK: " + (i + 1));
                rejectLines.add(result.patch.getHeader());
                result.patch.diffs.stream().map(Diff::toString).forEach(rejectLines::add);
                rejectLines.add("++++ END HUNK");
            } else {
                log(Level.FINE, " Hunk %d: %s", i, result.summary());	
            }
        }
        List<String> lines = patcher.lines;
        if (!lines.isEmpty()) {
            if (lines.get(lines.size() - 1).isEmpty()) {
                if (!patchFile.noNewLine) {//if we end in a new line and shouldn't have one
                    lines.remove(lines.size() - 1);
                }
            } else {
                lines.add("");
            }
        }
        outputCollector.consume(baseName, lines);
        if (!rejectLines.isEmpty()) {
            rejectCollector.consume(patchFile.name + ".rej", rejectLines);
            return false;
        }
        return true;
    }

    @Deprecated
    public static void bakePatches(InputPath input, OutputPath output) throws IOException {
        bakePatches(input, output, System.lineSeparator());
    }

    @Deprecated
    public static void bakePatches(InputPath input, OutputPath output, String lineEnding) throws IOException {
        bakePatches(input, "", output, lineEnding);
    }

    @Deprecated
    public static void bakePatches(InputPath input, String prefix, OutputPath output) throws IOException {
        bakePatches(input, prefix, output, System.lineSeparator());
    }

    public static void bakePatches(InputPath input, String prefix, OutputPath output, String lineEnding) throws IOException {
        if (!input.exists()) {
            throw new IllegalArgumentException("Expected input to exist.");
        }
        if (output.getType().isNull()) {
            throw new IllegalArgumentException("Expected non-null output.");
        }
        Map<String, List<String>> patchLines = new HashMap<>();
        if (input.isFile()) {
            if (input.getFormat() == null) { throw new IllegalArgumentException("Input is single file or unknown ArchiveFormat."); }
            try (ArchiveReader reader = input.getFormat().createReader(input.open(), prefix)) {
                for (String entry : reader.getEntries()) {
                    patchLines.put(entry, reader.readLines(entry));
                }
            }
        } else {
            Map<String, Path> index = indexChildren(input.toPath(), prefix);
            for (Map.Entry<String, Path> entry : index.entrySet()) {
                patchLines.put(entry.getKey(), Files.readAllLines(entry.getValue()));
            }
        }
        Map<String, byte[]> bakedPatches = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : patchLines.entrySet()) {
            PatchFile patchFile = PatchFile.fromLines(entry.getKey(), entry.getValue(), true);
            List<String> lines = patchFile.toLines(false);
            String joined = String.join(lineEnding, lines) + lineEnding;
            bakedPatches.put(entry.getKey(), joined.getBytes(StandardCharsets.UTF_8));
        }
        if (output.getFormat() != null) {
            try (ArchiveWriter writer = output.getFormat().createWriter(output.open())) {
                for (Map.Entry<String, byte[]> entry : bakedPatches.entrySet()) {
                    String path;
                    if (!StringUtils.isEmpty(prefix)) {
                        path = appendIfMissing(prefix, "/") + removeStart(entry.getKey(), "/");
                    } else {
                        path = entry.getKey();
                    }
                    writer.writeEntry(path, entry.getValue());
                }
            }
        } else {
            if (Files.exists(output.toPath())) {
                Utils.deleteFolder(output.toPath());
            }
            for (Map.Entry<String, byte[]> entry : bakedPatches.entrySet()) {
                Path path;
                if (!StringUtils.isEmpty(prefix)) {
                    path = output.toPath().resolve(appendIfMissing(prefix, "/") + removeStart(entry.getKey(), "/"));
                } else {
                    path = output.toPath().resolve(entry.getKey());
                }
                Files.write(makeParentDirs(path), entry.getValue());
            }
        }
    }

    @Deprecated
    public static String bakePatch(PatchFile patchFile) {
        return bakePatch(patchFile, System.lineSeparator());
    }

    @Deprecated
    public static String bakePatch(PatchFile patchFile, String lineEnding) {
        List<String> lines = patchFile.toLines(false);
        return String.join(lineEnding + lines) + lineEnding;
    }

    public static class PatchesSummary {

        public int unchangedFiles;
        public int changedFiles;
        public int missingFiles;
        public int failedMatches;
        public int exactMatches;
        public int accessMatches;
        public int offsetMatches;
        public int fuzzyMatches;

        public double overallQuality;

        public void print(PrintStream logger, boolean slim) {
            logger.println("Patch Summary:");
            if (!slim) {
                logger.println(" Un-changed files: " + unchangedFiles);
                logger.println(" Changed files:    " + changedFiles);
                logger.println(" Missing files:    " + missingFiles);
            }
            logger.println();
            logger.println(" Failed matches:   " + failedMatches);
            logger.println(" Exact matches:    " + exactMatches);
            logger.println(" Access matches:   " + accessMatches);
            logger.println(" Offset matches:   " + offsetMatches);
            logger.println(" Fuzzy matches:    " + fuzzyMatches);

            logger.println(String.format("Overall Quality   %.2f%%", overallQuality / (failedMatches + exactMatches + accessMatches + offsetMatches + fuzzyMatches)));
        }
    }

    public static class Builder {

        private static final Consumer<PrintStream> NULL_CALLBACK = e -> {};
        private static final PrintStream NULL_STREAM = new PrintStream(NullOutputStream.INSTANCE);

        private PrintStream logger = NULL_STREAM;
        private Consumer<PrintStream> helpCallback = NULL_CALLBACK;
        private Level level = Level.WARNING;
        private boolean summary;
        private InputPath basePath;
        private InputPath patchesPath;
        private OutputPath outputPath;
        private OutputPath rejectsPath = OutputPath.NullPath.INSTANCE;
        private float minFuzz = FuzzyLineMatcher.DEFAULT_MIN_MATCH_SCORE;
        private int maxOffset = FuzzyLineMatcher.MatchMatrix.DEFAULT_MAX_OFFSET;
        private PatchMode mode = PatchMode.EXACT;
        private String patchesPrefix = "";

        private String aPrefix = "a/";
        private String bPrefix = "b/";
        private String lineEnding = System.lineSeparator();

        private Builder() {
        }

        public Builder logTo(PrintStream logger) {
            this.logger = Objects.requireNonNull(logger);
            return this;
        }

        public Builder logTo(OutputStream logger) {
            return logTo(new PrintStream(logger));
        }

        public Builder helpCallback(Consumer<PrintStream> helpCallback) {
            this.helpCallback = Objects.requireNonNull(helpCallback);
            return this;
        }

        public Builder verbose(boolean verbose) {
        	return this.level(verbose ? Level.ALL : Level.WARNING);
        }
        
        public Builder level(Level level) {
        	this.level = level;
        	return this;
        }

        public Builder summary(boolean summary) {
            this.summary = summary;
            return this;
        }

        public Builder basePath(InputPath basePath) {
            this.basePath = Objects.requireNonNull(basePath);
            return this;
        }

        public Builder basePath(Path basePath) {
            return basePath(basePath, ArchiveFormat.findFormat(basePath.getFileName()));
        }

        public Builder basePath(Path basePath, ArchiveFormat format) {
            return basePath(new InputPath.FilePath(Objects.requireNonNull(basePath), format));
        }

        public Builder basePath(byte[] basePath, ArchiveFormat format) {
            InputStream is = new ByteArrayInputStream(Objects.requireNonNull(basePath));
            return basePath(new InputPath.PipePath(is, Objects.requireNonNull(format)));
        }

        public Builder patchesPath(InputPath patchesPath) {
            this.patchesPath = Objects.requireNonNull(patchesPath);
            return this;
        }

        public Builder patchesPath(Path patchesPath) {
            return patchesPath(patchesPath, ArchiveFormat.findFormat(patchesPath.getFileName()));
        }

        public Builder patchesPath(Path patchesPath, ArchiveFormat format) {
            return patchesPath(new InputPath.FilePath(Objects.requireNonNull(patchesPath), format));
        }

        public Builder patchesPath(byte[] patchesPath, ArchiveFormat format) {
            InputStream is = new ByteArrayInputStream(Objects.requireNonNull(patchesPath));
            return patchesPath(new InputPath.PipePath(is, Objects.requireNonNull(format)));
        }

        public Builder aPrefix(String aPrefix) {
            this.aPrefix = aPrefix;
            return this;
        }

        public Builder bPrefix(String bPrefix) {
            this.bPrefix = bPrefix;
            return this;
        }

        public Builder outputPath(OutputPath outputPath) {
            this.outputPath = Objects.requireNonNull(outputPath);
            return this;
        }

        public Builder outputPath(Path output) {
            return outputPath(output, ArchiveFormat.findFormat(output.getFileName()));
        }

        public Builder outputPath(Path output, ArchiveFormat format) {
            return outputPath(new OutputPath.FilePath(Objects.requireNonNull(output), format));
        }

        public Builder outputPath(OutputStream output, ArchiveFormat format) {
            return outputPath(new OutputPath.PipePath(Objects.requireNonNull(output), Objects.requireNonNull(format)));
        }

        public Builder rejectsPath(OutputPath rejectsPath) {
            this.rejectsPath = Objects.requireNonNull(rejectsPath);
            return this;
        }

        public Builder rejectsPath(Path rejects) {
            return rejectsPath(rejects, ArchiveFormat.findFormat(rejects.getFileName()));
        }

        public Builder rejectsPath(Path rejects, ArchiveFormat format) {
            return rejectsPath(new OutputPath.FilePath(Objects.requireNonNull(rejects), format));
        }

        public Builder rejectsPath(OutputStream rejects, ArchiveFormat format) {
            return rejectsPath(new OutputPath.PipePath(Objects.requireNonNull(rejects), Objects.requireNonNull(format)));
        }

        public Builder minFuzz(float minFuzz) {
            this.minFuzz = minFuzz;
            return this;
        }

        public Builder maxOffset(int maxOffset) {
            this.maxOffset = maxOffset;
            return this;
        }

        public Builder mode(PatchMode mode) {
            this.mode = Objects.requireNonNull(mode);
            return this;
        }

        public Builder patchesPrefix(String patchesPrefix) {
            this.patchesPrefix = Objects.requireNonNull(patchesPrefix);
            return this;
        }

        public Builder lineEnding(String value) {
            this.lineEnding = value;
            return this;
        }

        public PatchOperation build() {
            if (basePath == null) {
                throw new IllegalStateException("basePath not set.");
            }
            if (patchesPath == null) {
                throw new IllegalStateException("patchesPath not set.");
            }
            if (outputPath == null) {
                throw new IllegalStateException("output not set.");
            }
            return new PatchOperation(logger, helpCallback, level, summary, basePath, patchesPath, aPrefix, bPrefix, outputPath, rejectsPath, minFuzz, maxOffset, mode, patchesPrefix, lineEnding);
        }

    }
}
