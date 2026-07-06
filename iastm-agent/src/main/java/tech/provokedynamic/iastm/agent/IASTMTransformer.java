package tech.provokedynamic.iastm.agent;

import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;

public final class IASTMTransformer implements ClassFileTransformer {

    private static final ClassDesc CD_IASTM = ClassDesc.of("tech.provokedynamic.iastm.atomic.IASTM");
    private static final ClassDesc CD_METRICS = ClassDesc.of("tech.provokedynamic.iastm.TxMetrics");
    private static final ClassDesc CD_RUNNABLE = ClassDesc.of("java.lang.Runnable");

    private static final MethodTypeDesc MTD_START_PLAIN = MethodTypeDesc.of(ConstantDescs.CD_void, CD_RUNNABLE);
    private static final MethodTypeDesc MTD_START_METRICS = MethodTypeDesc.of(ConstantDescs.CD_void, CD_RUNNABLE, CD_METRICS);
    private static final MethodTypeDesc MTD_METRICS_CTOR = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_int);

    private static final ClassFile CF = ClassFile.of(
            ClassFile.DebugElementsOption.DROP_DEBUG,
            ClassFile.LineNumbersOption.DROP_LINE_NUMBERS);

    private static final String AGENT_PREFIX = "tech/provokedynamic/iastm/agent/";

    private final Set<String> scanPrefixes;

    IASTMTransformer(Set<String> scanPrefixes) {
        this.scanPrefixes = scanPrefixes;
    }

    /**
     * Returns {@code true} if at least one plain {@code IASTM.start(Runnable)} call was found
     * (i.e. this class is worth rewriting).
     */
    private static boolean collectRawCounts(ClassModel cm,
                                            Map<String, Counts> rawCounts,
                                            Map<String, List<String>> lambdaChildren) {
        boolean hasPlainStart = false;

        for (var method : cm.methods()) {
            if (method.code().isEmpty()) continue;

            var methodKey = methodKey(method);
            int reads = 0, writes = 0;
            String pendingLambda = null;

            for (var e : method.code().get()) {
                switch (e) {
                    case InvokeDynamicInstruction indy -> {
                        var lk = lambdaKey(indy);
                        if (lk != null) {
                            pendingLambda = lk;
                            rawCounts.putIfAbsent(lk, Counts.ZERO);
                        }
                    }
                    case InvokeInstruction inv
                            when inv.opcode() == Opcode.INVOKESTATIC
                            && inv.owner().asSymbol().equals(CD_IASTM) -> {
                        switch (inv.name().stringValue()) {
                            case "read" -> reads++;
                            case "write" -> writes++;
                            case "start" -> {
                                if (inv.typeSymbol().equals(MTD_START_PLAIN)) {
                                    hasPlainStart = true;
                                    if (pendingLambda != null) {
                                        lambdaChildren.computeIfAbsent(methodKey, _ -> new ArrayList<>())
                                                .add(pendingLambda);
                                        pendingLambda = null;
                                    }
                                }
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
            rawCounts.put(methodKey, new Counts(reads, writes));
        }
        return hasPlainStart;
    }

    // ── Pass 1: walk every method body, tally reads/writes, record lambda links ──
    private static Map<String, Counts> resolveCounts(Map<String, Counts> rawCounts,
                                                     Map<String, List<String>> lambdaChildren) {
        var resolved = new HashMap<String, Counts>(rawCounts.size() * 2);
        var visiting = new HashSet<String>();
        rawCounts.keySet().forEach(m -> resolved.put(m, accumulate(m, rawCounts, lambdaChildren, visiting)));
        return resolved;
    }

    // ── Pass 2: recursively merge lambda children into their enclosing methods ──
    private static Counts accumulate(String method,
                                     Map<String, Counts> raw,
                                     Map<String, List<String>> children,
                                     Set<String> visiting) {
        if (!visiting.add(method)) return Counts.ZERO;   // cycle guard
        var total = raw.getOrDefault(method, Counts.ZERO);
        for (var child : children.getOrDefault(method, List.of()))
            total = total.add(accumulate(child, raw, children, visiting));
        visiting.remove(method);
        return total;
    }

    private static String methodKey(MethodModel m) {
        return m.methodName().stringValue() + m.methodType().stringValue();
    }

    /**
     * Returns the synthetic method key for a lambda that produces a {@code Runnable}, or {@code null}.
     */
    private static String lambdaKey(InvokeDynamicInstruction indy) {
        if (!indy.typeSymbol().returnType().equals(CD_RUNNABLE)) return null;
        return indy.bootstrapArgs().stream()
                .filter(DirectMethodHandleDesc.class::isInstance)
                .map(a -> (DirectMethodHandleDesc) a)
                .map(mhd -> mhd.methodName() + mhd.invocationType().descriptorString())
                .findFirst()
                .orElse(null);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain pd,
                            byte[] classfileBuffer) {
        if (className == null
                || className.startsWith(AGENT_PREFIX)
                || IASTMAgent.notInScanScope(className, scanPrefixes)) return null;
        try {
            var cm = CF.parse(classfileBuffer);
            var rawCounts = new HashMap<String, Counts>();
            var lambdaChildren = new HashMap<String, List<String>>();
            if (!collectRawCounts(cm, rawCounts, lambdaChildren)) return null;

            var resolved = resolveCounts(rawCounts, lambdaChildren);
            return CF.transformClass(cm,
                    ClassTransform.transformingMethods(
                            MethodTransform.transformingCode(
                                    CodeTransform.ofStateful(() -> new MetricsInjectingTransform(resolved)))));
        } catch (Exception _) {
            return null;
        }
    }

    private record Counts(int reads, int writes) {
        static final Counts ZERO = new Counts(0, 0);

        Counts add(Counts o) {
            return new Counts(reads + o.reads, writes + o.writes);
        }
    }

    /**
     * Rewrites each {@code IASTM.start(Runnable)} call into
     * {@code IASTM.start(Runnable, TxMetrics)}, injecting a pre-built
     * {@code new TxMetrics(reads, writes)} from the resolved static counts.
     */
    private static final class MetricsInjectingTransform implements CodeTransform {

        private final Map<String, Counts> resolved;
        private String pendingLambda;

        MetricsInjectingTransform(Map<String, Counts> resolved) {
            this.resolved = resolved;
        }

        @Override
        public void accept(CodeBuilder cb, CodeElement e) {
            switch (e) {
                case InvokeDynamicInstruction indy -> {
                    var key = lambdaKey(indy);
                    if (key != null) pendingLambda = key;
                    cb.with(e);
                }
                // Rewrite: start(Runnable) → new TxMetrics(r,w); start(Runnable, TxMetrics)
                case InvokeInstruction inv
                        when inv.opcode() == Opcode.INVOKESTATIC
                        && inv.owner().asSymbol().equals(CD_IASTM)
                        && inv.name().stringValue().equals("start")
                        && inv.typeSymbol().equals(MTD_START_PLAIN)
                        && pendingLambda != null -> {

                    var c = resolved.getOrDefault(pendingLambda, Counts.ZERO);
                    pendingLambda = null;
                    cb.new_(CD_METRICS)
                            .dup()
                            .loadConstant(c.reads())
                            .loadConstant(c.writes())
                            .invokespecial(CD_METRICS, "<init>", MTD_METRICS_CTOR)
                            .invokestatic(CD_IASTM, "start", MTD_START_METRICS);
                }
                default -> cb.with(e);
            }
        }
    }
}
