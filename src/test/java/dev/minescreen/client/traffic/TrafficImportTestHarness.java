package dev.minescreen.client.traffic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.minescreen.network.TrafficTemplateManifest;

/** Dependency-light regression harness for RMP normalization and the import-time JS sandbox. */
public final class TrafficImportTestHarness {
    private TrafficImportTestHarness() {
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("minescreen-traffic-import-test");
        testScript(work);
        testBrowserExportCompatibility(work);
        testScriptCannotReachJava(work);
        testScriptBudget(work);
        testRmp(work);
        testDynamicTextDocument(work);
        testForwardCompatibleManifest(work);
        if (args.length > 0 && !args[0].isBlank() && !args[0].startsWith("--")) {
            testExternalRmp(work, Path.of(args[0]));
        }
        if (args.length > 1 && !args[0].startsWith("--")
                && !args[1].isBlank() && !args[1].startsWith("--")) {
            testExternalScript(Path.of(args[1]));
        }
        for (int index = 0; index + 1 < args.length; index++) {
            if (args[index].equals("--manifest")) {
                testExternalManifest(work, Path.of(args[index + 1]));
                index++;
            }
        }
        System.out.println("trafficImportTest=passed");
    }

    private static void testScript(Path work) throws Exception {
        Path script = work.resolve("generator.js");
        Files.writeString(script, """
                function generate() {
                  return {
                    id: 'carriage_demo', layout: 'script_scene_v1',
                    elements: [
                      {type:'rect', x:0, y:0, width:1, height:1, color:'#FF001018'},
                      {type:'text', x:0.5, y:0.4, align:'center', size:1.25,
                       color:'#FFFFFFFF', text:'${next}'}
                    ],
                    traffic: {line:'A1', next:'Central'}
                  };
                }
                """, StandardCharsets.UTF_8);
        JsonObject generated = TrafficScriptSandbox.evaluate(script);
        require("carriage_demo".equals(generated.get("id").getAsString()),
                "JS generator result missing");
    }

    private static void testScriptCannotReachJava(Path work) throws Exception {
        Path script = work.resolve("java-access.js");
        Files.writeString(script,
                "function generate(){ return {id: java.lang.System.getProperty('user.home')}; }",
                StandardCharsets.UTF_8);
        try {
            TrafficScriptSandbox.evaluate(script);
            throw new AssertionError("Java bridge unexpectedly available to template script");
        } catch (java.io.IOException expected) {
            require(expected.getMessage().contains("rejected"), "Unexpected sandbox rejection");
        }
    }

    private static void testBrowserExportCompatibility(Path work) throws Exception {
        Path script = work.resolve("browser-export.js");
        Files.writeString(script, """
                (function(root){
                  var DATA={defaultProfile:'zh-en',defaultIndex:1,defaultPage:'route',direction:'up',
                    directions:{up:{stations:[{code:'01'},{code:'02'},{code:'03'}]}},
                    languageFrames:{'zh-en':[{route:{elements:[
                      {type:'text',x:0.5,y:0.5,size:1,color:'#FFFFFFFF',text:'Central'}
                    ],traffic:{next:'Central'}}}]}};
                  root.MineScreenGeneratedLcd={data:DATA};
                  function auto(){document.querySelectorAll('[data-minescreen-lcd]').forEach(function(){});}
                  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',auto);else auto();
                }(this));
                """, StandardCharsets.UTF_8);
        JsonObject generated = TrafficScriptSandbox.evaluate(script);
        require("script_scene_v1".equals(generated.get("layout").getAsString())
                        && generated.getAsJsonArray("elements").size() == 1
                        && generated.getAsJsonObject("directions").getAsJsonObject("up")
                                .getAsJsonArray("stations").size() == 3,
                "browser LCD export was not converted into a MineScreen scene");
    }

    private static void testScriptBudget(Path work) throws Exception {
        Path script = work.resolve("loop.js");
        Files.writeString(script, "function generate(){ while(true){} }", StandardCharsets.UTF_8);
        long started = System.nanoTime();
        try {
            TrafficScriptSandbox.evaluate(script);
            throw new AssertionError("Infinite script unexpectedly completed");
        } catch (java.io.IOException expected) {
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            require(elapsedMillis < 20_000L, "Script budget did not interrupt promptly");
        }
    }

    private static void testRmp(Path work) throws Exception {
        Path source = work.resolve("metro.rmp");
        String rmp = """
                {
                  "version": 77,
                  "graph": {
                    "attributes": {"name": "Demo Line"},
                    "nodes": [
                      {"key":"stn_a","attributes":{"visible":true,"x":0,"y":0,
                       "type":"basic","basic":{"names":["Alpha","A"]}}},
                      {"key":"stn_b","attributes":{"visible":true,"x":100,"y":25,
                       "type":"basic","basic":{"names":["Beta","B"]}}}
                    ],
                    "edges": [
                      {"key":"line_1","source":"stn_a","target":"stn_b",
                       "attributes":{"visible":true,"style":"single-color",
                       "single-color":{"color":["other","demo","#12AEEF","#fff"]}}}
                    ]
                  }
                }
                """;
        Files.writeString(source, rmp, StandardCharsets.UTF_8);
        JsonObject save = JsonParser.parseString(rmp).getAsJsonObject();
        Path root = work.resolve("templates");
        TrafficImportResult result = RmpTrafficImporter.importProject(source, save, root);
        require(result.stationCount() == 2 && result.lineCount() == 1,
                "RMP node/edge count mismatch");
        Path folder = root.resolve(result.templateId());
        require(Files.isRegularFile(folder.resolve("manifest.json")), "RMP manifest missing");
        require(Files.isRegularFile(folder.resolve("rmp-map.json")), "RMP map missing");
        String normalized = Files.readString(folder.resolve("rmp-map.json"));
        require(normalized.contains("Alpha") && normalized.contains("#FF12AEEF"),
                "RMP names or line color were not normalized");
    }

    private static void testDynamicTextDocument(Path work) throws Exception {
        Path source = work.resolve("passenger-notice.txt");
        Files.writeString(source, """
                @title=Passenger notice
                @loop=true
                @default_duration=4s
                @default_transition=fade
                @default_align=center
                @position=bottom
                @background_color=#08131F
                @text_color=#FFFFFFFF
                欢迎乘坐本次列车
                Welcome aboard
                ---
                @duration=2.5s
                @transition=slide_left
                @align=right
                下一站：中央港
                Next stop: Central Harbor
                ---
                @duration=60t
                @transition=typewriter
                请站稳扶好，注意车门
                """, StandardCharsets.UTF_8);
        Path root = work.resolve("dynamic-text-templates");
        TrafficImportResult result =
                TrafficTemplateRepository.importTemplateForTest(source, root);
        require(result.sourceKind().equals("text") && result.lineCount() == 3,
                "Dynamic TXT page count or source kind mismatch");
        Path installed = root.resolve(result.templateId()).resolve("manifest.json");
        JsonObject manifest = JsonParser.parseString(Files.readString(installed))
                .getAsJsonObject();
        require("script_scene_v1".equals(manifest.get("layout").getAsString()),
                "Dynamic TXT did not compile to a declarative scene");
        TrafficTemplateRepository.TextSequence sequence =
                TrafficTemplateRepository.parseTextSequence(manifest);
        require(sequence.frames().size() == 3 && sequence.frames().get(1).durationTicks() == 50
                        && sequence.frames().get(1).transition().equals("slide_left")
                        && sequence.frames().get(1).align().equals("right")
                        && sequence.position().equals("bottom"),
                "Dynamic TXT timing, transition, or alignment was not preserved");
        TrafficTemplateManifest.validate(result.templateId(), Files.readAllBytes(installed));

        Path invalid = work.resolve("invalid-notice.txt");
        Files.writeString(invalid, "@transition=spin\nUnsupported animation",
                StandardCharsets.UTF_8);
        try {
            TrafficTemplateRepository.importTemplateForTest(invalid, root);
            throw new AssertionError("Unsupported TXT transition was accepted");
        } catch (java.io.IOException expected) {
            require(expected.getMessage().contains("transition"),
                    "Unexpected dynamic TXT validation error");
        }
    }

    private static void testExternalRmp(Path work, Path source) throws Exception {
        JsonObject save = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
        TrafficImportResult result = RmpTrafficImporter.importProject(source, save,
                work.resolve("external-templates"));
        require(result.stationCount() > 0, "External RMP project imported no stations");
        System.out.println("externalRmpVersion=" + save.get("version").getAsInt()
                + " stations=" + result.stationCount() + " lines=" + result.lineCount());
    }

    private static void testExternalScript(Path source) throws Exception {
        JsonObject generated = TrafficScriptSandbox.evaluate(source);
        require("script_scene_v1".equals(generated.get("layout").getAsString()),
                "External script did not generate a scene layout");
        require(generated.getAsJsonArray("elements").size() >= 10,
                "External script generated too few LCD elements");
        require(generated.getAsJsonObject("traffic").has("next"),
                "External script omitted next-station state");
        System.out.println("externalScriptId=" + generated.get("id").getAsString()
                + " elements=" + generated.getAsJsonArray("elements").size());
    }

    private static void testExternalManifest(Path work, Path source) throws Exception {
        TrafficImportResult result = TrafficTemplateRepository.importTemplateForTest(source,
                work.resolve("external-manifest-templates"));
        require(!result.templateId().isBlank(), "External manifest id missing");
        Path installed = work.resolve("external-manifest-templates")
                .resolve(result.templateId()).resolve("manifest.json");
        require(Files.isRegularFile(installed), "External manifest was not installed");
        JsonObject normalized = JsonParser.parseString(Files.readString(installed))
                .getAsJsonObject();
        require(normalized.has("layout"), "External manifest layout missing");
        JsonObject original = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
        JsonObject expected = unwrapForComparison(original);
        require(normalized.equals(expected),
                "External manifest JSON or style fields changed during import");
        if (normalized.has("elements")) {
            var parsedScene = TrafficTemplateRepository.parseScene(normalized);
            require(parsedScene.size() <= normalized.getAsJsonArray("elements").size(),
                    "External manifest scene element count is invalid");
            double expectedLineWidth = normalized.getAsJsonArray("elements").asList().stream()
                    .filter(element -> element.isJsonObject()
                            && "line".equals(element.getAsJsonObject().get("type").getAsString()))
                    .mapToDouble(element -> lineWidth(element.getAsJsonObject())).max()
                    .orElse(0.0D);
            double actualLineWidth = parsedScene.stream()
                    .filter(element -> element.type().equals("line"))
                    .mapToDouble(TrafficTemplateRepository.SceneElement::size).max()
                    .orElse(0.0D);
            require(Math.abs(expectedLineWidth - actualLineWidth) < 0.0001D,
                    "External manifest line width was altered while compiling the scene");
        }
        TrafficTemplateManifest.validate(result.templateId(), Files.readAllBytes(installed));
        System.out.println("externalManifestId=" + result.templateId()
                + " stations=" + result.stationCount());
    }

    private static void testForwardCompatibleManifest(Path work) throws Exception {
        Path source = work.resolve("wrapped-manifest.json");
        Files.writeString(source, """
                {
                  "manifest_version": 2,
                  "placeholder_defaults": {"operator_name": "Demo Railway"},
                  "vendor_style": {"corner_radius": 0.12, "font_family": "future:sans"},
                  "manifest": {
                    "id": "forward_style",
                    "layout": "script_scene_v1",
                    "elements": [
                      {"type":"text","x":0.1,"y":0.2,"text":"${next} · ${operator_name}",
                       "future_font_feature":"tabular-nums"},
                      {"type":"future_glow","optional":true,"x":0.0,"y":0.0}
                    ]
                  }
                }
                """, StandardCharsets.UTF_8);
        TrafficImportResult result = TrafficTemplateRepository.importTemplateForTest(source,
                work.resolve("forward-compatible-templates"));
        require(result.templateId().equals("forward_style"),
                "Wrapped forward-compatible manifest was not imported");
        JsonObject installed = JsonParser.parseString(Files.readString(work
                .resolve("forward-compatible-templates/forward_style/manifest.json")))
                .getAsJsonObject();
        require(installed.getAsJsonArray("elements").size() == 2
                        && installed.getAsJsonObject("placeholder_defaults")
                                .has("operator_name")
                        && installed.getAsJsonObject("vendor_style").has("corner_radius")
                        && installed.getAsJsonArray("elements").get(0).getAsJsonObject()
                                .has("future_font_feature"),
                 "Optional future layers or custom bindings were not preserved");
        TrafficTemplateManifest.validate(result.templateId(), Files.readAllBytes(work
                .resolve("forward-compatible-templates/forward_style/manifest.json")));
    }

    private static JsonObject unwrapForComparison(JsonObject original) {
        if (original.has("layout") || original.has("elements") || original.has("id")) {
            return original;
        }
        JsonObject nested = original.has("manifest") && original.get("manifest").isJsonObject()
                ? original.getAsJsonObject("manifest").deepCopy()
                : original.has("template") && original.get("template").isJsonObject()
                        ? original.getAsJsonObject("template").deepCopy() : original.deepCopy();
        for (var entry : original.entrySet()) {
            if (entry.getKey().equals("manifest") || entry.getKey().equals("template")
                    || nested.has(entry.getKey())) continue;
            nested.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return nested;
    }

    private static double lineWidth(JsonObject element) {
        if (element.has("stroke_width")) return element.get("stroke_width").getAsDouble();
        if (element.has("line_width")) return element.get("line_width").getAsDouble();
        return element.has("size") ? element.get("size").getAsDouble() : 1.0D;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
