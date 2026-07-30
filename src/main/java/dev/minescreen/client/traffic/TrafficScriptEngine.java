package dev.minescreen.client.traffic;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/** Rhino-only evaluator used inside the disposable worker process. */
final class TrafficScriptEngine {
    private static final long MAX_INSTRUCTIONS = 5_000_000L;
    private static final long MAX_NANOS = 5_000_000_000L;
    private static final ThreadLocal<Budget> ACTIVE_BUDGET = new ThreadLocal<>();
    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected Context makeContext() {
            Context context = super.makeContext();
            context.setLanguageVersion(Context.VERSION_ES6);
            context.setInterpretedMode(true);
            context.setMaximumInterpreterStackDepth(256);
            context.setInstructionObserverThreshold(5_000);
            return context;
        }

        @Override
        protected void observeInstructionCount(Context context, int instructionCount) {
            Budget budget = ACTIVE_BUDGET.get();
            if (budget == null) throw new EvaluatorException("Missing script budget");
            budget.instructions += instructionCount;
            if (budget.instructions > MAX_INSTRUCTIONS) {
                throw new EvaluatorException("Template script exceeded its instruction budget");
            }
            if (System.nanoTime() > budget.deadlineNanos) {
                throw new EvaluatorException("Template script exceeded its guest time budget");
            }
        }
    };

    private TrafficScriptEngine() {
    }

    static String evaluate(String script, String sourceName) {
        Budget budget = new Budget(System.nanoTime() + MAX_NANOS);
        ACTIVE_BUDGET.set(budget);
        try {
            return FACTORY.call(context -> execute(context, script, sourceName));
        } finally {
            ACTIVE_BUDGET.remove();
        }
    }

    private static String execute(Context context, String script, String sourceName) {
        context.setClassShutter(className -> false);
        ScriptableObject standards = context.initSafeStandardObjects(null, false);
        for (String property : new String[] {"Packages", "getClass", "JavaAdapter", "JavaImporter",
                "java", "javax", "org", "com", "edu", "net"}) {
            ScriptableObject.deleteProperty(standards, property);
        }
        standards.sealObject();
        Scriptable scope = context.newObject(standards);
        scope.setPrototype(standards);
        scope.setParentScope(null);
        // Browser-capable MineScreen exports use this tiny surface only for their optional
        // auto-mount pass. Returning an empty node list lets older exports be imported without
        // exposing a browser, filesystem, network, or Java bridge to the sandbox.
        context.evaluateString(scope,
                "var document={readyState:'complete',querySelectorAll:function(){return [];},"
                        + "addEventListener:function(){}};",
                sourceName + "#document", 1, null);
        context.evaluateString(scope, "'use strict';\n" + script, sourceName, 1, null);
        Object generated = context.evaluateString(scope,
                "(function(){"
                        + "if(typeof generate==='function')return JSON.stringify(generate());"
                        + "var b=(typeof MineScreenGeneratedLcd==='object')?MineScreenGeneratedLcd:null;"
                        + "var d=b&&b.data;if(d&&d.languageFrames){"
                        + "var p=d.defaultProfile||Object.keys(d.languageFrames)[0];"
                        + "var frames=d.languageFrames[p]||[];"
                        + "var i=Math.max(0,Math.min(frames.length-1,(Number(d.defaultIndex)||1)-1));"
                        + "var page=d.defaultPage==='auto'?'route':d.defaultPage;"
                        + "var frame=frames[i]&&(frames[i][page]||frames[i].route||frames[i].next);"
                        + "if(frame)return JSON.stringify({id:'minescreen_generated_lcd',"
                        + "name:'MineScreen generated LCD',layout:'script_scene_v1',"
                        + "elements:frame.elements||[],traffic:frame.traffic||{},"
                        + "active_direction:d.direction||'up',directions:d.directions||{},"
                        + "language_profiles:d.languageProfiles||[]});"
                        + "}throw new Error('Define function generate()');})()",
                sourceName + "#generate", 1, null);
        String output = Context.toString(generated);
        if (output.length() > 256 * 1024) throw new EvaluatorException("Output exceeds 256 KiB");
        return output;
    }

    private static final class Budget {
        private final long deadlineNanos;
        private long instructions;

        private Budget(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }
    }
}
