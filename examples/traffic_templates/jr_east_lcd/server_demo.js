/* Node.js reference server: node server_demo.js
 * It demonstrates server-authoritative station progression. MineScreen servers should port only
 * the resulting traffic fields/template hash, never distribute JavaScript for clients to execute.
 */
"use strict";

var http = require("http");
var demo = require("./jr_east_lcd_generator.js");
var template = demo.createTemplate(20260726);
var route = template.demo.stations;
var index = template.demo.next_index;
var modes = ["english", "japanese", "kana"];
var pages = ["next", "loop", "time"];
var modeIndex = 0;
var pageIndex = 0;
var traffic = Object.assign({}, template.traffic);

function updateState() {
    index += 1;
    if (index >= route.length) {
        template = demo.createTemplate(Date.now());
        route = template.demo.stations;
        traffic = Object.assign({}, template.traffic);
        index = template.demo.next_index;
        modeIndex = 0;
        pageIndex = 0;
        return;
    }
    modeIndex = (modeIndex + 1) % modes.length;
    pageIndex = (pageIndex + 1) % pages.length;
    template = demo.createTemplateFromRoute(route, index, template.demo.seed, modes[modeIndex],
        pages[pageIndex]);
    traffic = Object.assign({}, template.traffic);
    traffic.eta = index === route.length - 1 ? "Arrived" : String(1 + (index % 4)) + " min";
    traffic.status = index === route.length - 1 ? "Terminus" : "On time";
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
        response.end(JSON.stringify({template: template, traffic: traffic, updated_at: Date.now()}));
    } else {
        response.statusCode = 404;
        response.end(JSON.stringify({error: "Use /state, /template or /health"}));
    }
}).listen(8765, "127.0.0.1", function () {
    console.log("MineScreen LCD demo: http://127.0.0.1:8765/state");
});
