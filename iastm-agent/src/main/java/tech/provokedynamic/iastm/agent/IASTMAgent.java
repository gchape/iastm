package tech.provokedynamic.iastm.agent;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/// Java agent entry point for the IASTM bytecode instrumentation.
///
/// Registers [IASTMTransformer] with the JVM instrumentation API and immediately
/// retransforms any matching classes that were loaded before the agent attached.
/// The set of classes to instrument is controlled by a comma-separated list of
/// binary-name prefixes passed as the agent argument string (dots or slashes both
/// accepted). When no argument is supplied the default prefix
/// `tech/provokedynamic/iastm/` is used.
///
/// ## Activation modes
///
/// - **Static** — `-javaagent:iastm-agent.jar[=prefix,…]` via [#premain]
/// - **Dynamic** — attached to a running JVM via [#agentmain]
/// - **Programmatic** — [#attach(String)] for in-process use in tests
public final class IASTMAgent {

    private static final Logger log = LoggerFactory.getLogger(IASTMAgent.class);

    private static final String DEFAULT_SCAN_PREFIX = "tech/provokedynamic/iastm/";
    private static final String AGENT_INTERNAL_PREFIX = "tech/provokedynamic/iastm/agent/";

    private IASTMAgent() {
    }

    /// Called by the JVM when the agent is specified on the command line via `-javaagent`.
    ///
    /// @param args comma-separated package prefixes to instrument, or `null`
    /// @param inst the instrumentation handle provided by the JVM
    public static void premain(String args, Instrumentation inst) {
        registerTransformer(args, inst);
    }

    /// Called by the JVM when the agent is attached to an already-running process.
    ///
    /// @param args comma-separated package prefixes to instrument, or `null`
    /// @param inst the instrumentation handle provided by the JVM
    public static void agentmain(String args, Instrumentation inst) {
        registerTransformer(args, inst);
    }

    /// Programmatic entry point for in-process activation (e.g. integration tests).
    /// Uses ByteBuddy's self-attach helper to obtain an [Instrumentation] instance
    /// without an external agent JAR.
    ///
    /// @param args comma-separated package prefixes to instrument, or `null`
    public static void attach(String args) {
        registerTransformer(args, ByteBuddyAgent.install());
    }

    /// Parses the agent argument string into an immutable set of JVM internal name prefixes
    /// (slash-separated). Dots in the input are normalized to slashes so that both
    /// `com.example` and `com/example` are accepted. Returns a singleton set containing
    /// [#DEFAULT_SCAN_PREFIX] when `args` is `null` or blank.
    ///
    /// @param args raw agent argument string, may be `null`
    /// @return immutable set of slash-separated prefixes
    static Set<String> parsePrefixes(String args) {
        if (args == null || args.isBlank()) return Set.of(DEFAULT_SCAN_PREFIX);
        return Arrays.stream(args.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replace('.', '/'))
                .collect(Collectors.toUnmodifiableSet());
    }

    /// Returns `true` when `internalName` does not start with any of the given prefixes,
    /// meaning the class is outside the instrumentation scope and should be left untouched.
    ///
    /// @param internalName JVM internal class name (slash-separated)
    /// @param prefixes     the set of slash-separated prefixes to match against
    /// @return `true` if no prefix matches
    static boolean notInScanScope(String internalName, Set<String> prefixes) {
        return prefixes.stream().noneMatch(internalName::startsWith);
    }

    /// Installs [IASTMTransformer] and triggers retransformation of any matching classes
    /// already present in the JVM.
    private static void registerTransformer(String args, Instrumentation inst) {
        Set<String> scanPrefixes = parsePrefixes(args);
        inst.addTransformer(new IASTMTransformer(scanPrefixes), true);
        retransformLoadedClasses(inst, scanPrefixes);
        log.info("IASTM Agent ready, prefixes: {}", scanPrefixes);
    }

    /// Iterates all classes currently loaded by the JVM and retransforms those that fall
    /// within the scan scope. Classes belonging to the agent itself and classes the JVM
    /// does not permit retransforming are silently skipped.
    private static void retransformLoadedClasses(Instrumentation inst, Set<String> scanPrefixes) {
        Arrays.stream(inst.getAllLoadedClasses())
                .filter(c -> {
                    String internalName = c.getName().replace('.', '/');
                    return !internalName.startsWith(AGENT_INTERNAL_PREFIX)
                            && !notInScanScope(internalName, scanPrefixes)
                            && inst.isModifiableClass(c);
                })
                .forEach(c -> {
                    try {
                        inst.retransformClasses(c);
                    } catch (Exception e) {
                        log.warn("Retransform skipped for {}: {}", c.getName(), e.getMessage());
                    }
                });
    }
}
