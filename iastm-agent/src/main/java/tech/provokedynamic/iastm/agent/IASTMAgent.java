package tech.provokedynamic.iastm.agent;

import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class IASTMAgent {

    private static final String AGENT_INTERNAL_PREFIX = "tech/provokedynamic/iastm/agent/";

    private IASTMAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        registerTransformer(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        registerTransformer(args, inst);
    }

    public static void attach(String args) {
        registerTransformer(args, ByteBuddyAgent.install());
    }

    static Set<String> parsePrefixes(String args) {
        if (args == null || args.isBlank()) {
            throw new IllegalArgumentException("IASTM agent requires at least one scan prefix, e.g. -javaagent:iastm-agent.jar=com/example/myapp/");
        }
        return Arrays.stream(args.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replace('.', '/'))
                .collect(Collectors.toUnmodifiableSet());
    }

    static boolean notInScanScope(String internalName, Set<String> prefixes) {
        return prefixes.stream().noneMatch(internalName::startsWith);
    }

    private static void registerTransformer(String args, Instrumentation inst) {
        var prefixes = parsePrefixes(args);
        inst.addTransformer(new IASTMTransformer(prefixes), true);
        retransformLoaded(inst, prefixes);
    }

    private static void retransformLoaded(Instrumentation inst, Set<String> prefixes) {
        var targets = Arrays.stream(inst.getAllLoadedClasses())
                .filter(c -> {
                    var name = c.getName().replace('.', '/');
                    return !name.startsWith(AGENT_INTERNAL_PREFIX)
                            && !notInScanScope(name, prefixes)
                            && inst.isModifiableClass(c);
                })
                .toArray(Class<?>[]::new);
        if (targets.length == 0) {
            return;
        }
        try {
            inst.retransformClasses(targets);
        } catch (Exception _) {
        }
    }
}
