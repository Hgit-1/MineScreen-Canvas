"use strict";

var assert = require("assert");
var fs = require("fs");
var lcd = require("./linear_lcd_generator.js");
var rmp = require("./rmp_reader.js");

function color(index) {
    var value = (0x245000 + index * 0x17395B) & 0xFFFFFF;
    return "#" + value.toString(16).padStart(6, "0");
}

function syntheticTokyoHub(lineCount) {
    var nodes = [{key: "tokyo", attributes: {type: "jr-east-imp", x: 0, y: 0,
        "jr-east-imp": {names: ["東京", "Tōkyō"], mostImportant: true}}}];
    var edges = [];
    for (var index = 0; index < lineCount; index += 1) {
        var y = (index - (lineCount - 1) / 2) * 3;
        var code = "j" + String.fromCharCode(97 + index);
        var lineColor = color(index);
        nodes.push({key: "left_" + index, attributes: {type: "jr-east-basic", x: -100,
            y: y, "jr-east-basic": {names: ["西" + index, "West " + index]}}});
        nodes.push({key: "right_" + index, attributes: {type: "jr-east-basic", x: 100,
            y: y, "jr-east-basic": {names: ["东" + index, "East " + index]}}});
        nodes.push({key: "badge_" + index, attributes: {type: "jr-east-line-badge", x: 0,
            y: y - 24, "jr-east-line-badge": {names: ["测试线" + index,
                "Stress Line " + index], color: ["tokyo", code, lineColor, "#fff"],
                num: index + 1}}});
        edges.push({source: "left_" + index, target: "right_" + index,
            attributes: {style: "jr-east", "jr-east": {color: ["tokyo", code,
                lineColor, "#fff"]}}});
    }
    nodes.push({key: "empty_helper", attributes: {type: "jr-east-basic", x: 400, y: 400,
        "jr-east-basic": {names: ["", ""]}}});
    return {graph: {nodes: nodes, edges: edges}};
}

function routeFromLine(line) {
    return {
        id: "rmp_stress_route",
        symbol: line.symbol,
        systemCode: line.systemCode || "",
        circular: line.circular === true,
        loopTravelMinutes: 2,
        lineIcon: line.icon || "",
        lineNames: line.names || {zh: line.name, ja: line.name,
            en: line.secondaryName || line.name},
        serviceNames: {zh: "普通", ja: "普通", en: "Local"},
        destinationNames: line.stations[line.stations.length - 1].names,
        arrivalNotice: {zh: "列车即将到达下一站，请提前做好下车准备。",
            ja: "まもなく次の駅に到着します。", en: "The train will stop at the next station."},
        carNumber: "7",
        visibleStations: 8,
        style: JSON.parse(JSON.stringify(lcd.route.style)),
        stations: JSON.parse(JSON.stringify(line.stations))
    };
}

function bounded(template) {
    assert(template.elements.length <= 256, "scene must stay within MineScreen element budget");
    template.elements.forEach(function (element) {
        ["x", "y", "x2", "y2", "width", "height"].forEach(function (key) {
            if (element[key] == null) return;
            assert(Number.isFinite(element[key]) && element[key] >= 0 && element[key] <= 1,
                key + " leaves the canvas");
        });
    });
}

function htmlFor(route, segment) {
    var pages = ["route", "arrival"].map(function (page) {
        return lcd.createTemplateFromRoute(route, segment, "zh", "en", page, "up");
    });
    var data = JSON.stringify(pages).replace(/</g, "\\u003c");
    return "<!doctype html><meta charset='utf-8'><title>LCD stress preview</title>" +
        "<style>body{margin:0;background:#081016;display:grid;gap:18px;padding:18px}" +
        "canvas{width:min(1280px,100%);aspect-ratio:16/9;background:#fff}</style>" +
        "<canvas width='1280' height='720'></canvas><canvas width='1280' height='720'></canvas>" +
        "<script>const P=" + data + ";function c(v){return /^#[0-9a-f]{8}$/i.test(v)?'#'+v.slice(3)+v.slice(1,3):v}" +
        ";document.querySelectorAll('canvas').forEach((cv,i)=>{const x=cv.getContext('2d'),w=cv.width,h=cv.height,t=P[i],s=t.traffic||{};x.textBaseline='top';x.lineCap='round';x.lineJoin='round';for(const e of t.elements){x.fillStyle=c(e.color);x.strokeStyle=c(e.color);if(e.type==='rect')x.fillRect(e.x*w,e.y*h,e.width*w,e.height*h);else if(e.type==='ellipse'){x.beginPath();x.ellipse((e.x+e.width/2)*w,(e.y+e.height/2)*h,e.width*w/2,e.height*h/2,0,0,Math.PI*2);x.fill()}else if(e.type==='line'){x.lineWidth=Math.max(1,(e.size||1)*h/360);x.beginPath();x.moveTo(e.x*w,e.y*h);x.lineTo(e.x2*w,e.y2*h);x.stroke()}else if(e.type==='text'){const z=Math.max(8,(e.size||1)*h/20);x.font=(e.bold?'700 ':'500 ')+z+'px Microsoft YaHei UI,Segoe UI,sans-serif';x.save();x.translate(e.x*w,e.y*h);x.rotate((e.rotation||0)*Math.PI/180);x.textAlign=e.align||'left';const v=String(e.text||'').replace(/\\$\\{(line|destination|current|next|eta|status|game_time)\\}/g,(_,k)=>k==='game_time'?'12:00':s[k]||'');if(e.vertical)[...v].forEach((g,n)=>x.fillText(g,0,n*z*1.02));else x.fillText(v,0,0,w);x.restore()}}});</script>";
}

var synthetic = rmp.readProject(syntheticTokyoHub(12));
assert.strictEqual(synthetic.lines.length, 12);
assert(!synthetic.lines.some(function (line) {
    return line.stations.some(function (station) {
        return /站点|Station \d/.test(Object.values(station.names).join(" "));
    });
}));
var hubLine = synthetic.lines.find(function (line) {
    return line.stations.some(function (station) { return /东京|東京/.test(station.names.zh); });
});
var hubStation = hubLine.stations.find(function (station) { return /东京|東京/.test(station.names.zh); });
assert(hubStation.transfers.length >= 10, "dense hub must preserve more than three transfers");
var hubRoute = routeFromLine(hubLine);
var hubSegment = Math.max(1, hubRoute.stations.indexOf(hubStation));
var hubArrival = lcd.createTemplateFromRoute(hubRoute, hubSegment, "zh", "en", "arrival", "up");
bounded(hubArrival);
var badgeTexts = hubArrival.elements.filter(function (element) { return element.type === "text"; })
    .map(function (element) { return String(element.text); });
assert(badgeTexts.some(function (text) { return /^J[A-Z]$/.test(text); })
    && badgeTexts.some(function (text) { return /^\d{2}$/.test(text); }),
"JR-style stacked system-code station badge is missing");
var badgeCodeElement = hubArrival.elements.find(function (element) {
    return element.type === "text" && /^J[A-Z]$/.test(String(element.text));
});
var badgeNumberElement = hubArrival.elements.find(function (element) {
    return element.type === "text" && /^\d{2}$/.test(String(element.text))
        && Math.abs(element.x - badgeCodeElement.x) < 0.0001 && element.y > badgeCodeElement.y;
});
assert(badgeNumberElement,
    "JR-style badge must place the station number below the system code, never beside it");

var samplePath = process.argv.find(function (value) { return /\.json$/i.test(value); });
var htmlArgument = process.argv.find(function (value) { return value.indexOf("--html=") === 0; });
if (samplePath && fs.existsSync(samplePath)) {
    var project = rmp.readProject(JSON.parse(fs.readFileSync(samplePath, "utf8")));
    var yamanote = project.lines.find(function (line) { return /山手/.test(line.name); });
    assert(yamanote && yamanote.circular, "sample Yamanote loop was not recognized");
    var tokyo = yamanote.stations.find(function (station) { return /东京|東京/.test(station.names.zh); });
    assert(tokyo && tokyo.transfers.length >= 6, "sample Tokyo transfers are incomplete");
    var sampleRoute = routeFromLine(yamanote);
    var stationIndex = sampleRoute.stations.findIndex(function (station) {
        return station.key === tokyo.key || station.names.zh === tokyo.names.zh;
    });
    var segment = stationIndex === 0 && sampleRoute.circular ? sampleRoute.stations.length
        : Math.max(1, stationIndex);
    bounded(lcd.createTemplateFromRoute(sampleRoute, segment, "zh", "en", "arrival", "up"));
    if (htmlArgument) fs.writeFileSync(htmlArgument.substring(7), htmlFor(sampleRoute, segment),
        "utf8");
    console.log("lcdStressTest=passed lines=" + project.lines.length
        + " tokyoTransfers=" + tokyo.transfers.length);
} else {
    if (htmlArgument) fs.writeFileSync(htmlArgument.substring(7), htmlFor(hubRoute, hubSegment),
        "utf8");
    console.log("lcdStressTest=passed syntheticTokyoTransfers=" + hubStation.transfers.length);
}
