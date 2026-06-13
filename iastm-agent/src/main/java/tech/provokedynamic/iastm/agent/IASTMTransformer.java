package tech.provokedynamic.iastm.agent;

import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;

/// Bytecode transformer that upgrades bare [IASTM#start(Runnable)] call sites to the
/// metrics-aware overload [IASTM#start(Runnable, TxMetrics)], supplying a [TxMetrics]
/// instance pre-populated with the static read and write counts of the transaction body.
///
/// ## Two-pass algorithm
///
/// 1. **Count collection** — [#collectRawCounts] walks every method in the class file,
///    tallying [IASTM#read] and [IASTM#write] invocations and recording which lambda
///    synthetic method is passed to each [IASTM#start] call site.
/// 2. **Count resolution** — [#resolveCounts] propagates the raw tallies transitively
///    through lambda nesting so that a `start` site receives the combined read/write count
///    of its entire closure tree.
/// 3. **Rewrite** — [MetricsInjectingTransform] replaces each `IASTM.start(Runnable)`
///    instruction with a sequence that constructs a [TxMetrics] and calls
///    `IASTM.start(Runnable, TxMetrics)` instead.
///
/// Classes outside the configured scan prefixes and classes belonging to the agent itself
/// are never transformed. If a class contains no plain `IASTM.start` call sites the
/// transformer returns `null` (no-op).
public final class IASTMTransformer implements ClassFileTransformer {

    private static final ClassDesc CD_IASTM = ClassDesc.of("tech.provokedynamic.iastm.atomic.IASTM");
    private static final ClassDesc CD_TX_METRICS = ClassDesc.of("tech.provokedynamic.iastm.TxMetrics");
    private static final ClassDesc CD_RUNNABLE = ClassDesc.of("java.lang.Runnable");

    private static final MethodTypeDesc MTD_START_PLAIN = MethodTypeDesc.of(ConstantDescs.CD_void, CD_RUNNABLE);
    private static final MethodTypeDesc MTD_START_METRICS = MethodTypeDesc.of(ConstantDescs.CD_void, CD_RUNNABLE, CD_TX_METRICS);
    private static final MethodTypeDesc MTD_METRICS_CTOR = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_int);

    private static final String AGENT_PREFIX = "tech/provokedynamic/iastm/agent/";

    private final Set<String> scanPrefixes;

    IASTMTransformer(Set<String> scanPrefixes) {
        this.scanPrefixes = scanPrefixes;
    }

    /// Pass 1 — walks every method in `model`, recording:
    ///
    /// - the number of [IASTM#read] and [IASTM#write] calls per method key in `rawCounts`, and
    /// - for each `IASTM.start(Runnable)` call site, a mapping from the enclosing method key
    ///   to the synthetic method key of the immediately preceding lambda in `lambdaChildren`.
    ///
    /// @param model          class to scan
    /// @param rawCounts      output map: method key → `[reads, writes]`
    /// @param lambdaChildren output map: method key → list of child lambda keys
    /// @return `true` if at least one `IASTM.start(Runnable)` call was found
    private static boolean collectRawCounts(ClassModel model,
                                            Map<String, int[]> rawCounts,
                                            Map<String, List<String>> lambdaChildren) {
        boolean hasPlainStart = false;

        for (MethodModel method : model.methods()) {
            if (method.code().isEmpty()) continue;

            String methodKey = toMethodKey(method);
            int[] counts = new int[2];
            rawCounts.put(methodKey, counts);
            String pendingLambda = null;

            for (CodeElement e : method.code().get()) {
                if (e instanceof InvokeDynamicInstruction indy) {
                    pendingLambda = extractLambdaKey(indy);
                    if (pendingLambda != null) rawCounts.putIfAbsent(pendingLambda, new int[]{0, 0});

                } else if (e instanceof InvokeInstruction inv
                        && inv.opcode() == Opcode.INVOKESTATIC
                        && inv.owner().asSymbol().equals(CD_IASTM)) {

                    switch (inv.name().stringValue()) {
                        case "read" -> counts[0]++;
                        case "write" -> counts[1]++;
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
            }
        }
        return hasPlainStart;
    }

    private static String toMethodKey(MethodModel method) {
        return method.methodName().stringValue() + method.methodType().stringValue();
    }

    private static String extractLambdaKey(InvokeDynamicInstruction indy) {
        if (!indy.typeSymbol().returnType().equals(CD_RUNNABLE)) return null;
        for (ConstantDesc arg : indy.bootstrapArgs()) {
            if (arg instanceof DirectMethodHandleDesc mhd) {
                return mhd.methodName() + mhd.invocationType().descriptorString();
            }
        }
        return null;
    }

    /// Pass 1 post-processing — resolves each method's raw counts into transitive totals by
    /// recursively summing the counts of all lambda children recorded in `lambdaChildren`.
    private static Map<String, int[]> resolveCounts(Map<String, int[]> rawCounts,
                                                    Map<String, List<String>> lambdaChildren) {
        Map<String, int[]> resolved = new HashMap<>(rawCounts.size() * 2);
        Set<String> visiting = new HashSet<>();
        for (String method : rawCounts.keySet()) {
            resolved.put(method, accumulateCounts(method, rawCounts, lambdaChildren, visiting));
        }
        return resolved;
    }

    private static int[] accumulateCounts(String method,
                                          Map<String, int[]> rawCounts,
                                          Map<String, List<String>> lambdaChildren,
                                          Set<String> visiting) {
        if (!visiting.add(method)) return new int[]{0, 0};
        int[] own = rawCounts.getOrDefault(method, new int[]{0, 0});
        int reads = own[0], writes = own[1];
        for (String child : lambdaChildren.getOrDefault(method, List.of())) {
            int[] c = accumulateCounts(child, rawCounts, lambdaChildren, visiting);
            reads += c[0];
            writes += c[1];
        }
        visiting.remove(method);
        return new int[]{reads, writes};
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain pd,
                            byte[] classfileBuffer) {

        if (className == null
                || className.startsWith(AGENT_PREFIX)
                || IASTMAgent.notInScanScope(className, scanPrefixes)) {
            return null;
        }

        try {
            ClassFile cf = ClassFile.of();
            ClassModel model = cf.parse(classfileBuffer);

            var rawCounts = new HashMap<String, int[]>();
            var lambdaChildren = new HashMap<String, List<String>>();
            if (!collectRawCounts(model, rawCounts, lambdaChildren)) return null;

            Map<String, int[]> resolved = resolveCounts(rawCounts, lambdaChildren);

            return cf.transformClass(model,
                    ClassTransform.transformingMethods(
                            MethodTransform.ofStateful(() ->
                                    MethodTransform.transformingCode(
                                            new MetricsInjectingTransform(resolved)))));

        } catch (Exception e) {
            return null;
        }
    }

    /// Pass 2 code transform — replaces each `IASTM.start(Runnable)` instruction with:
    ///
    /// ```
    /// new TxMetrics(readOps, writeOps)
    /// IASTM.start(runnable, metrics)
    /// ```
    private static final class MetricsInjectingTransform implements CodeTransform {

        private final Map<String, int[]> resolved;
        private String pendingLambda;

        MetricsInjectingTransform(Map<String, int[]> resolved) {
            this.resolved = resolved;
        }

        private static boolean isPlainStartCall(InvokeInstruction inv) {
            return inv.opcode() == Opcode.INVOKESTATIC
                    && inv.owner().asSymbol().equals(CD_IASTM)
                    && inv.name().stringValue().equals("start")
                    && inv.typeSymbol().equals(MTD_START_PLAIN);
        }

        @Override
        public void accept(CodeBuilder cb, CodeElement e) {
            if (e instanceof InvokeDynamicInstruction indy) {
                String key = extractLambdaKey(indy);
                if (key != null) pendingLambda = key;
                cb.with(e);

            } else if (e instanceof InvokeInstruction inv
                    && isPlainStartCall(inv)
                    && pendingLambda != null) {

                int[] counts = resolved.getOrDefault(pendingLambda, new int[]{0, 0});
                pendingLambda = null;

                cb.new_(CD_TX_METRICS)
                        .dup()
                        .ldc(counts[0])
                        .ldc(counts[1])
                        .invokespecial(CD_TX_METRICS, "<init>", MTD_METRICS_CTOR)
                        .invokestatic(CD_IASTM, "start", MTD_START_METRICS);

            } else {
                cb.with(e);
            }
        }
    }
}
