/*
 * MineScreen JR-East/Yamanote-inspired LCD reconstruction.
 *
 * The layout is an original declarative reconstruction based on a short visual reference. It
 * contains no extracted video/audio, operator logo, official font, pictogram, or screenshot.
 */

var JrEastLcdDemo = (function () {
    "use strict";

    /* Clockwise demo order. The names are public station facts, not extracted artwork. */
    var STATIONS = [
        {code: "01", ja: "東京", kana: "とうきょう", en: "Tokyo"},
        {code: "02", ja: "神田", kana: "かんだ", en: "Kanda"},
        {code: "03", ja: "秋葉原", kana: "あきはばら", en: "Akihabara"},
        {code: "04", ja: "御徒町", kana: "おかちまち", en: "Okachimachi"},
        {code: "05", ja: "上野", kana: "うえの", en: "Ueno"},
        {code: "06", ja: "鶯谷", kana: "うぐいすだに", en: "Uguisudani"},
        {code: "07", ja: "日暮里", kana: "にっぽり", en: "Nippori"},
        {code: "08", ja: "西日暮里", kana: "にしにっぽり", en: "Nishi-Nippori"},
        {code: "09", ja: "田端", kana: "たばた", en: "Tabata"},
        {code: "10", ja: "駒込", kana: "こまごめ", en: "Komagome"},
        {code: "11", ja: "巣鴨", kana: "すがも", en: "Sugamo"},
        {code: "12", ja: "大塚", kana: "おおつか", en: "Otsuka"},
        {code: "13", ja: "池袋", kana: "いけぶくろ", en: "Ikebukuro"},
        {code: "14", ja: "目白", kana: "めじろ", en: "Mejiro"},
        {code: "15", ja: "高田馬場", kana: "たかだのばば", en: "Takadanobaba"},
        {code: "16", ja: "新大久保", kana: "しんおおくぼ", en: "Shin-Okubo"},
        {code: "17", ja: "新宿", kana: "しんじゅく", en: "Shinjuku"},
        {code: "18", ja: "代々木", kana: "よよぎ", en: "Yoyogi"},
        {code: "19", ja: "原宿", kana: "はらじゅく", en: "Harajuku"},
        {code: "20", ja: "渋谷", kana: "しぶや", en: "Shibuya"},
        {code: "21", ja: "恵比寿", kana: "えびす", en: "Ebisu"},
        {code: "22", ja: "目黒", kana: "めぐろ", en: "Meguro"},
        {code: "23", ja: "五反田", kana: "ごたんだ", en: "Gotanda"},
        {code: "24", ja: "大崎", kana: "おおさき", en: "Osaki"},
        {code: "25", ja: "品川", kana: "しながわ", en: "Shinagawa"},
        {code: "26", ja: "高輪ゲートウェイ", kana: "たかなわげーとうぇい", en: "Takanawa Gateway"},
        {code: "27", ja: "田町", kana: "たまち", en: "Tamachi"},
        {code: "28", ja: "浜松町", kana: "はままつちょう", en: "Hamamatsucho"},
        {code: "29", ja: "新橋", kana: "しんばし", en: "Shimbashi"},
        {code: "30", ja: "有楽町", kana: "ゆうらくちょう", en: "Yurakucho"}
    ];

    var GREEN = "#FF52CB00";
    var GREEN_DARK = "#FF087522";
    var RED = "#FFD31F26";
    var YELLOW = "#FFFFD72E";
    var PAPER = "#FFF0F0F0";
    var INK = "#FF111111";

    function seededRandom(seed) {
        var state = (Number(seed) || 1) >>> 0;
        return function () {
            state = (state * 1664525 + 1013904223) >>> 0;
            return state / 4294967296;
        };
    }

    function rotateRoute(route, offset) {
        var index = ((offset % route.length) + route.length) % route.length;
        return route.slice(index).concat(route.slice(0, index));
    }

    function chooseRoute(seed) {
        var random = seededRandom(seed);
        return rotateRoute(STATIONS, Math.floor(random() * STATIONS.length));
    }

    function normalizeMode(mode) {
        return mode === "japanese" || mode === "kana" ? mode : "english";
    }

    function normalizePage(page) {
        return page === "loop" || page === "time" ? page : "next";
    }

    function stationName(station, mode) {
        if (mode === "japanese") return station.ja;
        if (mode === "kana") return station.kana;
        return station.en;
    }

    function fullStationName(station) {
        return station.ja + " / " + station.en;
    }

    function addText(elements, x, y, size, color, value, align, rotation, vertical, bold) {
        elements.push({
            type: "text", x: x, y: y, size: size, color: color, text: value,
            align: align || "left", rotation: rotation || 0, vertical: vertical === true,
            bold: bold !== false
        });
    }

    function verticalTextSize(value, normalSize) {
        var length = String(value || "").length;
        if (length >= 7) return 0.38;
        if (length >= 5) return 0.44;
        return normalSize;
    }

    function addLine(elements, x1, y1, x2, y2, size, color) {
        elements.push({type: "line", x: x1, y: y1, x2: x2, y2: y2,
            size: size, color: color});
    }

    function addEllipse(elements, centerX, centerY, radiusX, radiusY, color) {
        elements.push({type: "ellipse", x: centerX - radiusX, y: centerY - radiusY,
            width: radiusX * 2, height: radiusY * 2, color: color});
    }

    function addNode(elements, x, y, outer, inner, radius) {
        var r = radius || 0.016;
        addEllipse(elements, x, y, r, r * 1.55, outer);
        addEllipse(elements, x, y, r * 0.62, r * 0.96, inner);
    }

    function addPolyline(elements, points, size, color) {
        var i;
        for (i = 1; i < points.length; i += 1) {
            addLine(elements, points[i - 1][0], points[i - 1][1], points[i][0], points[i][1],
                size, color);
        }
    }

    function quadraticPoints(start, control, end, segments) {
        var points = [];
        var i;
        for (i = 0; i <= segments; i += 1) {
            var t = i / segments;
            var inverse = 1 - t;
            points.push([
                inverse * inverse * start[0] + 2 * inverse * t * control[0] + t * t * end[0],
                inverse * inverse * start[1] + 2 * inverse * t * control[1] + t * t * end[1]
            ]);
        }
        return points;
    }

    function quadraticPoint(start, control, end, t) {
        var inverse = 1 - t;
        return [
            inverse * inverse * start[0] + 2 * inverse * t * control[0] + t * t * end[0],
            inverse * inverse * start[1] + 2 * inverse * t * control[1] + t * t * end[1]
        ];
    }

    function addChevron(elements, x, y, direction) {
        var dx = 0.013 * direction;
        addLine(elements, x - dx, y - 0.023, x, y, 2.4, GREEN);
        addLine(elements, x, y, x - dx, y + 0.023, 2.4, GREEN);
    }

    function headerElements(current, next, mode, page, clock) {
        var showCurrent = mode !== "english" && (page === "loop" || page === "time");
        var station = showCurrent ? current : next;
        var binding = showCurrent ? "${current}" : "${next}";
        var elements = [
            {type: "rect", x: 0, y: 0, width: 1, height: 0.31, color: "#FF202120"},
            {type: "rect", x: 0.238, y: 0, width: 0.043, height: 0.31, color: GREEN},
            {type: "rect", x: 0.292, y: 0.105, width: 0.085, height: 0.168,
                color: "#FF111411"},
            {type: "rect", x: 0.299, y: 0.119, width: 0.071, height: 0.140,
                color: GREEN},
            {type: "rect", x: 0.306, y: 0.130, width: 0.057, height: 0.118,
                color: "#FFF8F8F8"}
        ];

        if (mode === "english") {
            addText(elements, 0.012, 0.052, 0.58, "#FFDCDCEB", "Bound for", "left", 0, false);
            addText(elements, 0.012, 0.112, 0.82, "#FFFFFFFF", "Ueno &", "left", 0, false);
            addText(elements, 0.012, 0.190, 0.82, "#FFFFFFFF", "Ikebukuro", "left", 0, false);
            addText(elements, 0.292, 0.013, 0.76, "#FFDCDCEB", "Next", "left", 0, false);
        } else {
            addText(elements, 0.015, 0.074, 0.90, "#FFFFFFFF", "上野・池袋", "left", 0, false);
            addText(elements, 0.168, 0.217, 0.54, "#FFFFFFFF", "方面", "center", 0, false);
            addText(elements, 0.292, 0.013, 0.73, "#FFDCDCEB",
                showCurrent ? "ただいま" : mode === "kana" ? "つぎは" : "次は",
                "left", 0, false);
        }

        addText(elements, 0.334, 0.135, 0.42, INK, "JY", "center", 0, false);
        addText(elements, 0.334, 0.190, 0.56, INK, station.code, "center", 0, false);
        addText(elements, 0.420, 0.074, mode === "english" ? 2.55 : 2.40,
            "#FFFFFFFF", binding, "left", 0, false);
        addText(elements, 0.865, 0.018, 0.85, "#FFDCDCF5", clock, "right", 0, false);
        addText(elements, 0.905, 0.024, 0.38, "#FFDCDCEB",
            mode === "english" ? "Car No." : "号車", "left", 0, false);
        addText(elements, 0.982, 0.052, 0.95, "#FFFFFFFF", "11", "right", -8, false);
        return elements;
    }

    function routeElements(route, nextIndex, mode) {
        var elements = [];
        var visible = 8;
        var left = 0.07;
        var right = 0.94;
        var routeY = 0.67;
        var spacing = (right - left) / (visible - 1);
        var i;
        addLine(elements, left, routeY, right, routeY, 7.0, GREEN);
        for (i = 0; i < visible; i += 1) {
            var index = (nextIndex - 2 + i + route.length) % route.length;
            var x = left + spacing * i;
            var current = index === (nextIndex - 1 + route.length) % route.length;
            var next = index === nextIndex % route.length;
            addNode(elements, x, routeY, current ? RED : GREEN,
                next ? YELLOW : "#FFF6F6F6", 0.017);
            addText(elements, x, 0.46, 0.54, current ? "#FF777777" : INK,
                stationName(route[index], mode), "center", mode === "english" ? -48 : 0,
                mode !== "english");
            addText(elements, x, 0.735, 0.38, "#FF3B4A50", "JY" + route[index].code,
                "center", 0, false);
        }
        addText(elements, 0.50, 0.88, 0.55, "#FF4A575D",
            mode === "english" ? "Next stop route guidance" : "次駅案内", "center", 0, false);
        return elements;
    }

    function japaneseDoubleRowElements(route, nextIndex, mode) {
        var elements = [];
        var top = [];
        var bottom = [0];
        var i;
        for (i = 15; i >= 1; i -= 1) top.push(i);
        for (i = route.length - 1; i >= 16; i -= 1) bottom.push(i);

        function drawRow(indexes, y, labelY) {
            var j;
            addLine(elements, 0.045, y, 0.955, y, 10.0, GREEN);
            addChevron(elements, 0.035, y, 1);
            addChevron(elements, 0.047, y, 1);
            addChevron(elements, 0.965, y, -1);
            addChevron(elements, 0.953, y, -1);
            for (j = 0; j < indexes.length; j += 1) {
                var index = indexes[j];
                var x = indexes.length <= 1 ? 0.5 : 0.095 + 0.81 * j / (indexes.length - 1);
                var current = index === (nextIndex - 1 + route.length) % route.length;
                var next = index === nextIndex % route.length;
                addNode(elements, x, y, current ? RED : GREEN,
                    next ? YELLOW : "#FFEFEFEF", 0.015);
                var rowName = stationName(route[index], mode);
                addText(elements, x, labelY, verticalTextSize(rowName, 0.56), INK, rowName,
                    "center", 0, true);
            }
        }

        drawRow(top, 0.57, 0.36);
        drawRow(bottom, 0.88, 0.66);
        return elements;
    }

    function stadiumPoints() {
        var points = [[0.10, 0.58], [0.90, 0.58]];
        var steps = 5;
        var i;
        for (i = 1; i <= steps; i += 1) {
            var rightAngle = -Math.PI / 2 + Math.PI * i / steps;
            points.push([0.90 + Math.cos(rightAngle) * 0.065,
                0.68 + Math.sin(rightAngle) * 0.10]);
        }
        points.push([0.10, 0.78]);
        for (i = 1; i <= steps; i += 1) {
            var leftAngle = Math.PI / 2 + Math.PI * i / steps;
            points.push([0.10 + Math.cos(leftAngle) * 0.065,
                0.68 + Math.sin(leftAngle) * 0.10]);
        }
        points.push([0.10, 0.58]);
        return points;
    }

    function englishLoopElements(route, nextIndex) {
        var elements = [];
        var top = [];
        var bottom = [0];
        var i;
        for (i = 15; i >= 1; i -= 1) top.push(i);
        for (i = route.length - 1; i >= 16; i -= 1) bottom.push(i);
        addPolyline(elements, stadiumPoints(), 23.0, GREEN);

        function drawRow(indexes, y, labelY, topRow) {
            var j;
            for (j = 0; j < indexes.length; j += 1) {
                var index = indexes[j];
                var x = topRow ? 0.10 + 0.80 * j / (indexes.length - 1)
                    : 0.90 - 0.80 * j / (indexes.length - 1);
                var current = index === (nextIndex - 1 + route.length) % route.length;
                var next = index === nextIndex % route.length;
                addNode(elements, x, y, current ? RED : GREEN,
                    next ? YELLOW : "#FFEDEDED", 0.013);
                addText(elements, x, labelY, verticalTextSize(route[index].ja, 0.55), INK,
                    route[index].ja,
                    "center", 0, true);
                if (topRow) {
                    var minutes = ((index - nextIndex + route.length) % route.length + 1) * 2;
                    addText(elements, x, y - 0.022, 0.43, INK,
                        String(minutes), "center", 0, false);
                }
            }
        }

        drawRow(top, 0.58, 0.34, true);
        drawRow(bottom, 0.78, 0.815, false);
        addText(elements, 0.173, 0.592, 0.26, INK, "(min)", "left", 0, false);
        return elements;
    }

    function loopOverviewElements(route, nextIndex, mode) {
        return mode === "english" ? englishLoopElements(route, nextIndex)
            : japaneseDoubleRowElements(route, nextIndex, mode);
    }

    function travelTimeElements(route, nextIndex, mode) {
        var elements = [];
        var currentIndex = (nextIndex - 1 + route.length) % route.length;
        var current = route[currentIndex];
        /* Centreline begins below the header; the thick stroke touches, but does not cover, it. */
        var start = [0.0, 0.40];
        var control = [0.54, 0.45];
        var end = [0.70, 1.0];
        var curve = quadraticPoints(start, control, end, 14);
        var transferNames = mode === "english"
            ? ["Tohoku / Joetsu Shinkansen", "Chuo Line", "Keihin-Tohoku Line",
                "Subway connection", "Local bus"]
            : ["東北・山形・秋田・北海道新幹線", "東海道・山陽新幹線", "中央線",
                "京浜東北線", "地下鉄線"];
        var transferColors = ["#FF24A34B", "#FF277EC1", "#FFF28A00", "#FF02A7C8", "#FFE83E8C"];
        var i;

        addText(elements, 0.025, 0.48, 0.65, INK,
            mode === "english" ? current.en + " Station" : current.ja + "駅", "left", 0, false);
        addText(elements, 0.025, 0.545, 0.49, "#FF333333",
            mode === "english" ? "Transfer information" : "乗換のご案内", "left", 0, false);
        for (i = 0; i < transferNames.length; i += 1) {
            addEllipse(elements, 0.040, 0.625 + i * 0.058, 0.013, 0.022, transferColors[i]);
            addText(elements, 0.040, 0.610 + i * 0.058, 0.30, "#FFFFFFFF",
                String(i + 1), "center", 0, false);
            addText(elements, 0.060, 0.608 + i * 0.058, 0.44, INK,
                transferNames[i], "left", 0, false);
        }

        addPolyline(elements, curve, 58.0, GREEN_DARK);
        addPolyline(elements, curve, 46.0, GREEN);
        for (i = 0; i < 4; i += 1) {
            var t = 0.38 + i * 0.145;
            var point = quadraticPoint(start, control, end, t);
            var stationIndex = (nextIndex + 3 - i) % route.length;
            var station = route[stationIndex];
            addEllipse(elements, point[0], point[1], 0.023, 0.041, "#FFF7F7F7");
            addText(elements, point[0], point[1] - 0.022, 0.61, INK,
                String(8 - i * 2), "center", 0, false);
            addText(elements, point[0] + 0.035, point[1] - 0.045, 0.82, INK,
                stationName(station, mode), "left", 0, false);
            addText(elements, point[0] + 0.010, point[1] - 0.073, 0.31, GREEN_DARK,
                "JY" + station.code, "left", 0, false);
        }

        addEllipse(elements, 0.66, 0.94, 0.040, 0.066, "#FFFFFFFF");
        addEllipse(elements, 0.66, 0.94, 0.033, 0.055, RED);
        addEllipse(elements, 0.66, 0.94, 0.013, 0.022, "#FFF7F7F7");
        addText(elements, 0.79, 0.78, mode === "english" ? 1.62 : 1.72, INK,
            stationName(current, mode), "center", 0, false);
        addText(elements, 0.990, 0.962, 0.22, "#FF333333",
            mode === "english" ? "Times are estimates." : "所要時間は目安です。",
            "right", 0, false);
        return elements;
    }

    function createTemplateFromRoute(route, nextIndex, seed, mode, page) {
        var safeRoute = route && route.length >= 16 ? route : STATIONS;
        var safeMode = normalizeMode(mode);
        var safePage = normalizePage(page);
        var safeIndex = Math.max(1, Math.min(Number(nextIndex) || 1, safeRoute.length - 1));
        var current = safeRoute[safeIndex - 1];
        var next = safeRoute[safeIndex];
        var clockMinute = 5 + safeIndex;
        var clock = "00:" + (clockMinute < 10 ? "0" : "") + clockMinute;
        var body = safePage === "loop" ? loopOverviewElements(safeRoute, safeIndex, safeMode)
            : safePage === "time" ? travelTimeElements(safeRoute, safeIndex, safeMode)
            : routeElements(safeRoute, safeIndex, safeMode);
        var elements = [{type: "rect", x: 0, y: 0.31, width: 1, height: 0.69,
            color: PAPER}].concat(headerElements(current, next, safeMode, safePage, clock), body);

        return {
            id: "jr_east_yamanote_lcd_reconstruction",
            name: "Yamanote-inspired LCD reconstruction",
            layout: "script_scene_v1",
            background_color: PAPER,
            text_color: "#FF1F2427",
            accent_color: GREEN,
            elements: elements,
            traffic: {
                line: "JY Yamanote Line Demo",
                destination: "Ueno / Ikebukuro",
                current: stationName(current, safeMode),
                next: stationName(next, safeMode),
                eta: "2 min",
                status: "On time"
            },
            demo: {
                source_segment: "Tokyo-Ueno layout study plus Meguro-Shinjuku reference segment",
                seed: Number(seed) || 20260726,
                mode: safeMode,
                page: safePage,
                next_index: safeIndex,
                stations: safeRoute
            }
        };
    }

    function createTemplate(seed) {
        return createTemplateFromRoute(STATIONS, 1, seed, "english", "loop");
    }

    return {
        allStations: STATIONS,
        chooseRoute: chooseRoute,
        createTemplate: createTemplate,
        createTemplateFromRoute: createTemplateFromRoute,
        fullStationName: fullStationName,
        stationName: stationName
    };
}());

/* MineScreen import entry point: deterministic, full-line English overview. */
function generate() {
    return JrEastLcdDemo.createTemplateFromRoute(
        JrEastLcdDemo.allStations, 1, 20260726, "english", "loop");
}

if (typeof module === "object" && module && module.exports) {
    module.exports = JrEastLcdDemo;
}
