"use strict";

var childProcess = require("child_process");
var fs = require("fs");
var path = require("path");
var root = __dirname;

["linear_lcd_generator.js", "rmp_reader.js", "showcase.runtime.js", "simulation_test.js",
    "stress_test.js", "linear_lcd_showcase.js"].forEach(function (name) {
    childProcess.execFileSync(process.execPath, ["--check", path.join(root, name)],
        {stdio: "inherit"});
});

childProcess.execFileSync(process.execPath, [path.join(root, "simulation_test.js")],
    {stdio: "inherit"});
childProcess.execFileSync(process.execPath, [path.join(root, "stress_test.js")],
    {stdio: "inherit"});

var editor = fs.readFileSync(path.join(root, "visual_editor.html"), "utf8");
if (!editor.includes("function generate(){return ${importManifest};}")
        || !editor.includes('if(typeof document!=="undefined")')) {
    throw new Error("LCD Studio JS export must support both MineScreen import and browser mount");
}
var scripts = Array.from(editor.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi))
    .map(function (match) { return match[1]; }).filter(function (value) { return value.trim(); });
scripts.forEach(function (script) { new Function(script); });

var marker = "/*__MINESCREEN_GENERATOR__*/";
var template = fs.readFileSync(path.join(root, "showcase.template.html"), "utf8");
var generator = fs.readFileSync(path.join(root, "linear_lcd_generator.js"), "utf8");
var runtime = fs.readFileSync(path.join(root, "showcase.runtime.js"), "utf8");
var showcase = fs.readFileSync(path.join(root, "showcase.html"), "utf8");
var bundle = fs.readFileSync(path.join(root, "linear_lcd_showcase.js"), "utf8");
if (showcase !== template.replace(marker, generator)) {
    throw new Error("showcase.html is stale; run node build_showcase.js");
}
if (bundle !== generator + "\n" + runtime) {
    throw new Error("linear_lcd_showcase.js is stale; run node build_showcase.js");
}

var generated = require(path.join(root, "linear_lcd_generator.js")).createTemplate(1);
var textBindings = generated.elements.filter(function (element) {
    return element.type === "text";
}).map(function (element) {
    return element.text;
}).join("\n");
["${line}", "${destination}", "${service_type}", "${train_name}",
    "${carriage_number}", "${next}", "${eta}", "${direction}",
    "${arrival_time}"].forEach(function (binding) {
    if (!textBindings.includes(binding)) {
        throw new Error("carriage LCD is missing live binding " + binding);
    }
});
if (textBindings.split("\n").includes("快速") || textBindings.split("\n").includes("Rapid")) {
    throw new Error("service type is still hard-coded in the exported carriage scene");
}

console.log("lcdStudioCiTest=passed");
