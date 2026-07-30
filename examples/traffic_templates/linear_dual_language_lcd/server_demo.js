/* Node.js reference server: node server_demo.js
 * Sends structured state and a declarative template; it does not stream pixels or execute JS on
 * remote clients. A production MineScreen integration should synchronize route/template hashes.
 */
"use strict";

var http = require("http");
var demo = require("./linear_lcd_generator.js");
var route = demo.route;
var index = 4;
var page = "route";
var primary = "zh";
var secondary = "en";
var template = demo.createTemplateFromRoute(route, index, primary, secondary, page);

function updateState() {
    index += 1;
    if (index >= route.stations.length) {
        index = 1;
        page = page === "route" ? "next" : "route";
    }
    template = demo.createTemplateFromRoute(route, index, primary, secondary, page);
}

setInterval(updateState, 5000);

http.createServer(function (request, response) {
    response.setHeader("Access-Control-Allow-Origin", "*");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Content-Type", "application/json; charset=utf-8");
    if (request.url === "/health") {
        response.end(JSON.stringify({ok: true}));
    } else if (request.url === "/template") {
        response.end(JSON.stringify(template));
    } else if (request.url === "/state") {
        response.end(JSON.stringify({
            template: template,
            traffic: template.traffic,
            updated_at: Date.now()
        }));
    } else {
        response.statusCode = 404;
        response.end(JSON.stringify({error: "Use /state, /template or /health"}));
    }
}).listen(8766, "127.0.0.1", function () {
    console.log("MineScreen linear LCD demo: http://127.0.0.1:8766/state");
});
