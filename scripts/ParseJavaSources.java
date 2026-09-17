import com.sun.source.util.JavacTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Public JDK compiler parser only. No symbol resolution, dependency linkage or bytecode generation. */
public final class ParseJavaSources {
    private ParseJavaSources() {}
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        List<Path> sources;
        try (var files = Files.walk(root)) {
            sources = files.filter(path -> path.toString().endsWith(".java") && path.toString().replace('\\', '/').contains("src/"))
                    .filter(path -> !path.toString().replace('\\', '/').contains("/build/")).sorted().toList();
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK 21 compiler required");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("--release", "21", "-proc:none"),
                    null, manager.getJavaFileObjectsFromPaths(sources));
            long parsed = 0; for (var ignored : task.parse()) parsed++;
            var errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
            errors.forEach(System.err::println);
            System.out.println("SYNTAX_ONLY files=" + parsed + " errors=" + errors.size());
            System.out.println("NOT a Paper/Platform compile, type check, link test, or server test.");
            if (!errors.isEmpty()) throw new AssertionError("Java syntax errors");
        }
    }
}
