"use strict";

var fs = require("fs");
var path = require("path");
var root = __dirname;
var template = fs.readFileSync(path.join(root, "showcase.template.html"), "utf8");
var generator = fs.readFileSync(path.join(root, "linear_lcd_generator.js"), "utf8");
var runtime = fs.readFileSync(path.join(root, "showcase.runtime.js"), "utf8");
var marker = "/*__MINESCREEN_GENERATOR__*/";
if (template.indexOf(marker) < 0) throw new Error("Showcase generator marker is missing");
if (generator.indexOf("</script") >= 0) throw new Error("Generator cannot be embedded safely");
fs.writeFileSync(path.join(root, "showcase.html"), template.replace(marker, generator), "utf8");
fs.writeFileSync(path.join(root, "linear_lcd_showcase.js"), generator + "\n" + runtime, "utf8");
console.log("showcase.html generated");
