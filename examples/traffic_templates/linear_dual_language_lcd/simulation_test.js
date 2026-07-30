"use strict";

var assert = require("assert");
var fs = require("fs");
var lcd = require("./linear_lcd_generator.js");
var rmp = require("./rmp_reader.js");

function textValues(template) {
    return template.elements.filter(function (item) { return item.type === "text"; })
        .map(function (item) { return String(item.text || ""); });
}

function assertBoundedScene(template) {
    assert(template.elements.length > 20 && template.elements.length <= 256);
    template.elements.forEach(function (item) {
        ["x", "y", "x2", "y2", "width", "height"].forEach(function (key) {
            if (item[key] == null) return;
            assert(Number.isFinite(item[key]), key + " must be finite");
            assert(item[key] >= 0 && item[key] <= 1, key + " must stay in the canvas");
        });
    });
}

var route = JSON.parse(JSON.stringify(lcd.route));
route.directions = {
    up: {direction: "up", stations: route.stations},
    down: {direction: "down", stations: route.stations.slice().reverse()}
};

var arrival = lcd.createTemplateFromRoute(route, 3, "zh", "en", "arrival", "up");
assert.strictEqual(arrival.demo.page, "arrival");
assert.strictEqual(arrival.demo.language_profile, "zh-en");
assert.strictEqual(arrival.demo.direction, "up");
assert(textValues(arrival).some(function (text) { return text.indexOf("换乘") >= 0; }));
assert(textValues(arrival).some(function (text) { return text.indexOf("机场快线") >= 0; }));
assert(textValues(arrival).some(function (text) { return text.indexOf("环线地铁") >= 0; }));
assert(!textValues(arrival).some(function (text) { return text.indexOf("候车时间") >= 0; }));
assertBoundedScene(arrival);

var longRoute = JSON.parse(JSON.stringify(route));
longRoute.stations[3].names.en = "International Airport Terminal and Exhibition Centre Station";
longRoute.lineNames.en = "Saikyo Line Kawagoe Line Rinkai Line Through Service";
var longEnglish = lcd.createTemplateFromRoute(longRoute, 3, "ja", "en", "arrival", "up");
assert.strictEqual(longEnglish.demo.language_profile, "ja-en");
assertBoundedScene(longEnglish);

var down = lcd.createTemplateFromRoute(route, 2, "zh", "ja", "next", "down");
assert.strictEqual(down.demo.direction, "down");
assert.strictEqual(down.demo.language_profile, "zh-ja");
assertBoundedScene(down);

var invalidPair = lcd.createTemplateFromRoute(route, 2, "en", "ja", "route", "up");
assert.strictEqual(invalidPair.demo.language_profile, "zh-en");
assert.throws(function () {
    lcd.createTemplateFromRoute({stations: [route.stations[0]]}, 1, "zh", "en", "route", "up");
}, /at least two stations/);

var crowdedRoute = JSON.parse(JSON.stringify(route));
delete crowdedRoute.directions;
crowdedRoute.stations[3].transfers = Array.from({length: 14}, function (_, index) {
    return {symbol: "L" + index, systemCode: index % 2 ? "JC" : "JO",
        stationCode: String(index + 1).padStart(2, "0"), color: "#FF607D8B",
        names: {zh: "换乘线" + index, ja: "乗換" + index, en: "Transfer Line " + index}};
});
var crowdedArrival = lcd.createTemplateFromRoute(crowdedRoute, 3, "zh", "en", "arrival", "up");
assertBoundedScene(crowdedArrival);
assert(textValues(crowdedArrival).some(function (text) { return text.indexOf("+4") >= 0; }));

var circularRoute = JSON.parse(JSON.stringify(route));
delete circularRoute.directions;
circularRoute.circular = true;
circularRoute.loopTravelMinutes = 3;
circularRoute.lineNames = {zh: "山手线", ja: "山手線", en: "Yamanote Line"};
circularRoute.symbol = "9";
circularRoute.systemCode = "JY";
circularRoute.stations = Array.from({length: 30}, function (_, index) {
    return {code: String(index + 1).padStart(2, "0"),
        names: {zh: "环站" + (index + 1), ja: "環駅" + (index + 1), en: "Loop " + (index + 1)},
        travelMinutes: index ? 2 : 0, transfers: []};
});
var circularLast = lcd.createTemplateFromRoute(circularRoute, 30, "zh", "en", "route", "up");
assert.strictEqual(circularLast.demo.circular, true);
assert.strictEqual(circularLast.demo.next_index, 30);
assert(textValues(circularLast).some(function (text) { return text.indexOf("环站1") >= 0; }));

var formationRoute = JSON.parse(JSON.stringify(route));
delete formationRoute.directions;
formationRoute.stations[3].operation = {type: "detach",
    destinationNames: {zh: "成田", ja: "成田", en: "Narita"}};
var formation = lcd.createTemplateFromRoute(formationRoute, 3, "zh", "en", "arrival", "up");
assert(textValues(formation).some(function (text) { return text.indexOf("解挂") >= 0; }));

var explicit = rmp.readProject({graph: {nodes: [
    {key: "a", attributes: {type: "jr-east-basic", x: 0, y: 0,
        "jr-east-basic": {names: ["甲", "Alpha"]}}},
    {key: "b", attributes: {type: "jr-east-basic", x: 100, y: 0,
        "jr-east-basic": {names: ["乙", "Beta"]}}}
], edges: [], lines: [{id: "local", name: "Local Line", stations: ["a", "b"]}]}});
assert(explicit.lines.some(function (line) { return line.name === "Local Line"; }));

var authoredBadge = rmp.readProject({graph: {nodes: [
    {key: "s1", attributes: {type: "jr-east-basic", x: 0, y: 0,
        "jr-east-basic": {names: ["一", "One"]}}},
    {key: "s2", attributes: {type: "jr-east-basic", x: 100, y: 0,
        "jr-east-basic": {names: ["二", "Two"]}}},
    {key: "badge", attributes: {type: "jr-east-line-badge", x: 50, y: -20,
        "jr-east-line-badge": {names: ["测试线", "Test Line"],
            color: ["tokyo", "jy", "#7bab4f", "#000"], num: 9}}}
], edges: [{source: "s1", target: "s2", attributes: {style: "jr-east",
    "jr-east": {color: ["tokyo", "jy", "#7bab4f", "#000"]}}}]}});
var authoredLine = authoredBadge.lines.find(function (line) { return line.name === "测试线"; });
assert(authoredLine, "authored RMP line badge must be matched");
assert.strictEqual(authoredLine.symbol, "9");
assert.strictEqual(authoredLine.systemCode, "JY");

var sample = process.argv[2];
if (sample && fs.existsSync(sample)) {
    var project = rmp.readProject(JSON.parse(fs.readFileSync(sample, "utf8")));
    var yamanote = project.lines.find(function (line) { return /山手/.test(line.name); });
    assert(yamanote, "RMP sample must contain Yamanote Line");
    assert.strictEqual(yamanote.symbol, "9", "RMP authored num must remain the display symbol");
    assert.strictEqual(yamanote.systemCode, "JY", "palette code is retained only as metadata");
    assert.strictEqual(yamanote.circular, true, "Yamanote must be recognized as a loop");
    assert(yamanote.stations.every(function (station) {
        return !/站点|Station \d/.test(Object.values(station.names).join(" "));
    }), "synthetic station indexes must not leak into LCD");
    var tokyoMemberships = project.lines.filter(function (line) {
        return line.stations.some(function (station) { return /东京|東京/.test(station.names.zh); });
    });
    assert(tokyoMemberships.length >= 6, "Tokyo hub should retain multiple nearby railway lines");
    yamanote.stations.forEach(function (station) {
        station.transfers.forEach(function (transfer) {
            assert(Object.prototype.hasOwnProperty.call(transfer, "stationCode"));
        });
    });
}

console.log("linearLcdSimulationTest=passed");
