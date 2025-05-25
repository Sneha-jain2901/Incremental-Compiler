import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.expr.MethodCallExpr;
import javax.tools.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import javax.swing.*; // Add this for SwingUtilities
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.ast.expr.*;

public class IncrementalCompiler {
    // Directories for source files, compiled classes, and dependency metadata
    public static final File SRC_DIR = new File("src");
    public static final File BIN_DIR = new File("bin");
    public static final File DEPS_DIR = new File(".deps");
    public static final File HASHES_FILE = new File("hashes.ser");

    // Hash map for file content hashes
    public static final Map<String, String> fileHashes = new HashMap<>();

    // Dependency graph: file -> set of imported files
    public static final Map<String, Set<String>> dependencyGraph = new HashMap<>();

    public static void parseSourceFolder() {
        File[] files = SRC_DIR.listFiles((d, name) -> name.endsWith(".java"));
        if (files == null)
            return;
        for (File file : files) {
            try {
                // parsed representation of java source file
                // root node for AST
                CompilationUnit cu = StaticJavaParser.parse(file);
                // analyzing parse tree (AST)
                String className = file.getName().replace(".java", "");
                Set<String> set = new HashSet<>();
                // class variables
                cu.findAll(FieldDeclaration.class).forEach(field -> {
                    field.getVariables().forEach(v -> {
                        field.getElementType().ifClassOrInterfaceType(t -> {
                            set.add(t.getNameAsString() + ".java");
                        });
                    });
                });
                // methods
                // MethodDecalaration.class return class object of type MethodDeclaration (i.e.
                // a runtime representation for it)
                cu.findAll(MethodDeclaration.class).forEach(method -> {
                    // return type
                    method.getType().ifClassOrInterfaceType(t -> set.add(t.getNameAsString() + ".java"));
                    // parameters,local variables,generic type arguments
                    method.findAll(ClassOrInterfaceType.class).forEach(t -> set.add(t.getNameAsString() + ".java"));
                });

                // static method callls or object.method()
                cu.findAll(MethodCallExpr.class).forEach(call -> {
                    call.getScope().ifPresent(scope -> {
                        if (scope.isNameExpr()) {
                            set.add(scope.asNameExpr().getNameAsString() + ".java");
                        }
                    });
                });
                dependencyGraph.put(file.getName(), set);
                saveDepsFile(file.getName(), set);
            } catch (IOException e) {
                System.err.println("Failed to parse: " + file.getName());
                e.printStackTrace();
            }
        }
    }

   /* public static void main(String[] args) throws Exception {
        if (!BIN_DIR.exists())
            BIN_DIR.mkdirs();
        if (!DEPS_DIR.exists())
            DEPS_DIR.mkdirs();

        loadHashes(); // Load previous file hashes from disk
        // buildDependencyGraph(); // Build the dependency graph and save to .deps/
        parseSourceFolder();
        List<File> changedFiles = detectChangedFiles(); // Find modified files

        Set<File> toCompile = getFilesToCompile(changedFiles); // Determine what to recompile
        for (File file : toCompile)
            System.out.println(file);
        if (!toCompile.isEmpty()) {
            compileFiles(toCompile); // Compile changed files and their dependents
            updateHashes(toCompile); // Update hashes for compiled files
            saveHashes(); // Save updated hashes to disk
            System.out.println("Compiled: " + toCompile);
        } else {
            System.out.println("No changes detected. Compilation skipped.");
        }
    }*/
   public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--ui")) {
            // Launch UI version
            SwingUtilities.invokeLater(() -> {
                CompilerUI ui = new CompilerUI();
                ui.setVisible(true);
            });
        } else {
            // Original CLI version
            if (!BIN_DIR.exists()) BIN_DIR.mkdirs();
            if (!DEPS_DIR.exists()) DEPS_DIR.mkdirs();

            loadHashes();
            parseSourceFolder();
            List<File> changedFiles = detectChangedFiles();
            Set<File> toCompile = getFilesToCompile(changedFiles);

            if (!toCompile.isEmpty()) {
                compileFiles(toCompile);
                updateHashes(toCompile);
                saveHashes();
                System.out.println("Compiled: " + toCompile);
            } else {
                System.out.println("No changes detected. Compilation skipped.");
            }
        }
    }

    // Save dependencies of a source file to a .deps file
    public static void saveDepsFile(String fileName, Set<String> deps) throws IOException {
        File depFile = new File(DEPS_DIR, fileName + ".deps");
        Files.write(depFile.toPath(), deps);
    }

    // Detect files whose content has changed by comparing SHA-256 hashes
    public static List<File> detectChangedFiles() throws Exception {
        List<File> changed = new ArrayList<>();
        for (File file : Objects.requireNonNull(SRC_DIR.listFiles((d, name) -> name.endsWith(".java")))) {
            String hash = computeFileHash(file);
            if (!hash.equals(fileHashes.get(file.getName()))) {
                changed.add(file);
            }
        }
        return changed;
    }


    // Determine which files need to be recompiled based on changed files
    public static Set<File> getFilesToCompile(List<File> changedFiles) {
        Set<String> toCompileNames = new HashSet<>();
        Queue<String> queue = new LinkedList<>();

        for (File f : changedFiles) {
            queue.add(f.getName());
            toCompileNames.add(f.getName());
        }

        // Traverse dependency graph to find dependents of changed files
        while (!queue.isEmpty()) {
            String changed = queue.poll();
            for (Map.Entry<String, Set<String>> entry : dependencyGraph.entrySet()) {
                if (entry.getValue().contains(changed) && !toCompileNames.contains(entry.getKey())) {
                    toCompileNames.add(entry.getKey());
                    queue.add(entry.getKey());
                }
            }
        }

        return Arrays.stream(Objects.requireNonNull(SRC_DIR.listFiles((d, n) -> n.endsWith(".java"))))
                .filter(f -> toCompileNames.contains(f.getName()))
                .collect(Collectors.toSet());
    }

    // Compile the given set of source files
    public static void compileFiles(Set<File> files) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);

        // Set output directory for compiled classes
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(BIN_DIR));

        // Add BIN_DIR to classpath so that existing compiled classes can be used
        fileManager.setLocation(StandardLocation.CLASS_PATH, List.of(BIN_DIR));

        // Prepare source files for compilation
        Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(files);
        compiler.getTask(null, fileManager, null, null, null, compilationUnits).call();
        fileManager.close();
    }

    // Load file hashes from disk (from previous run)
    public static void loadHashes() {
        if (!HASHES_FILE.exists())
            return;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(HASHES_FILE))) {
            fileHashes.putAll((Map<String, String>) ois.readObject());
            System.out.println("Loaded previous hashes.");
        } catch (Exception e) {
            System.out.println("No previous hash state found.");
        }
    }

    // Save updated file hashes to disk
    public static void saveHashes() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(HASHES_FILE))) {
            oos.writeObject(fileHashes);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Update hashes for a collection of compiled files
    public static void updateHashes(Collection<File> files) throws Exception {
        for (File file : files) {
            fileHashes.put(file.getName(), computeFileHash(file));
        }
    }

    // Compute SHA-256 hash of a file's contents
    public static String computeFileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file.toPath());
        byte[] hashBytes = digest.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static Map<String, Set<String>> getDependencyGraph() {
        return dependencyGraph;
    }

    public static CompilationUnit parseFileForAST(File file) throws IOException {
        return StaticJavaParser.parse(file);
    }

    public static File getSrcDir() {
        return SRC_DIR;
    }
}
