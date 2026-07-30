/* Browser runtime for linear_lcd_showcase.js. The build step prepends the declarative generator. */
(function (root) {
    "use strict";

    function mount(target, options) {
        options = options || {};
        var element = typeof target === "string" ? document.querySelector(target) : target;
        if (!element) throw new Error("MineScreenLinearLcd.mount target was not found");
        if (!root.LinearLcdDemo) throw new Error("linear_lcd_generator.js is missing from the bundle");
        var route = options.route || root.LinearLcdDemo.route;
        var direction = options.direction === "down" ? "down" : options.direction === "up" ? "up" : (route.activeDirection || "up");
        var profiles = [
            {id: "zh-en", primary: "zh", secondary: "en"},
            {id: "ja-en", primary: "ja", secondary: "en"},
            {id: "zh-ja", primary: "zh", secondary: "ja"}
        ];
        var profile = profiles.find(function (item) { return item.id === options.profile; }) || profiles[0];
        var primary = profile.primary;
        var secondary = profile.secondary;
        var pageMode = options.page === "route" || options.page === "next" || options.page === "arrival" ? options.page : "auto";
        var visiblePage = pageMode === "next" || pageMode === "arrival" ? pageMode : "route";
        var index = Math.max(1, Math.min(Number(options.index) || 1, segmentCount()));
        var interval = Math.max(2_000, Number(options.interval) || 9_000);
        var playing = options.autoplay !== false;
        var languageCycle = options.languageCycle === true;
        var languageInterval = Math.max(2_000, Number(options.languageInterval) || 8_000);
        var languageTimer = null;
        var timer = null;
        var clockTimer = null;
        var stage = document.createElement("div");
        var canvas = document.createElement("canvas");
        var motionCanvas = document.createElement("canvas");
        var context = canvas.getContext("2d");
        var motionContext = motionCanvas.getContext("2d");
        canvas.width = 1920;
        canvas.height = 1080;
        stage.style.cssText = "position:relative;width:100%;aspect-ratio:16/9;background:#050708;overflow:hidden;border-radius:10px;";
        canvas.style.cssText = "position:absolute;inset:0;display:block;width:100%;height:100%;";
        motionCanvas.width = canvas.width;
        motionCanvas.height = canvas.height;
        motionCanvas.style.cssText = "position:absolute;inset:0;display:block;width:100%;height:100%;pointer-events:none;opacity:0;";
        stage.appendChild(canvas);
        stage.appendChild(motionCanvas);
        element.appendChild(stage);

        var status;
        var toolbar;
        if (options.controls) {
            toolbar = document.createElement("div");
            toolbar.style.cssText = "position:absolute;left:12px;right:12px;bottom:12px;display:flex;gap:7px;align-items:center;padding:8px;border:1px solid #ffffff24;border-radius:11px;background:#081014dd;backdrop-filter:blur(10px);font:13px 'Microsoft YaHei UI','Segoe UI',sans-serif;color:#eef4f6;z-index:2;";
            toolbar.innerHTML = "<button data-ms-prev aria-label='上一站'>←</button><button data-ms-pause>暂停</button>" +
                "<button data-ms-next aria-label='下一站'>→</button><button data-ms-page>切换版面</button>" +
                "<span data-ms-status style='margin-left:auto;color:#d5e3e8;white-space:nowrap'></span>";
            Array.prototype.forEach.call(toolbar.querySelectorAll("button"), function (button) {
                button.style.cssText = "min-height:32px;min-width:34px;padding:5px 10px;border:1px solid #ffffff2c;border-radius:7px;background:#21313a;color:#eef4f6;font:inherit;cursor:pointer;transition:background .15s ease,border-color .15s ease;";
                button.addEventListener("mouseenter", function () { button.style.background = "#304650"; button.style.borderColor = "#ff9b42"; });
                button.addEventListener("mouseleave", function () { button.style.background = "#21313a"; button.style.borderColor = "#ffffff2c"; });
            });
            stage.appendChild(toolbar);
            status = toolbar.querySelector("[data-ms-status]");
            toolbar.querySelector("[data-ms-prev]").onclick = function () { api.previous(); };
            toolbar.querySelector("[data-ms-next]").onclick = function () { api.next(); };
            toolbar.querySelector("[data-ms-pause]").onclick = function () {
                playing ? api.pause() : api.play();
            };
            toolbar.querySelector("[data-ms-page]").onclick = function () { api.setPage(); };
        }

        function selectedRoute() {
            var selected = route.directions && route.directions[direction];
            return selected && Array.isArray(selected.stations) && selected.stations.length > 1
                ? selected : route;
        }
        function stationCount() {
            return selectedRoute().stations.length;
        }
        function segmentCount() {
            return selectedRoute().circular === true || route.circular === true
                ? stationCount() : Math.max(1, stationCount() - 1);
        }

        function clockText() {
            if (typeof options.gameTimeProvider === "function") return options.gameTimeProvider();
            var now = new Date();
            return String(now.getHours()).padStart(2, "0") + ":" + String(now.getMinutes()).padStart(2, "0");
        }
        function cssColor(value) {
            var color = String(value || "#FFFFFFFF");
            return /^#[0-9a-f]{8}$/i.test(color) ? "#" + color.slice(3) + color.slice(1, 3) : color;
        }
        function animateArrival() {
            if (!motionCanvas.animate || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
            motionContext.clearRect(0, 0, motionCanvas.width, motionCanvas.height);
            motionContext.drawImage(canvas, 0, 0);
            motionCanvas.animate([
                {opacity: 0.82, clipPath: "inset(18% 0 0 0)", transform: "translateX(22px)", filter: "blur(1.2px)"},
                {opacity: 0, clipPath: "inset(18% 0 0 0)", transform: "translateX(-8px)", filter: "blur(0)"}
            ], {duration: 520, easing: "cubic-bezier(.22,.72,.22,1)"});
        }
        function render(animate) {
            if (animate) animateArrival();
            var template = root.LinearLcdDemo.createTemplateFromRoute(route, index, primary,
                secondary, visiblePage, direction);
            var state = template.traffic || {};
            var width = canvas.width;
            var height = canvas.height;
            context.clearRect(0, 0, width, height);
            context.textBaseline = "top";
            context.lineCap = "round";
            context.lineJoin = "round";
            template.elements.forEach(function (scene) {
                context.fillStyle = cssColor(scene.color);
                context.strokeStyle = cssColor(scene.color);
                if (scene.type === "rect") {
                    context.fillRect(scene.x * width, scene.y * height,
                        scene.width * width, scene.height * height);
                } else if (scene.type === "ellipse") {
                    context.beginPath();
                    context.ellipse((scene.x + scene.width / 2) * width,
                        (scene.y + scene.height / 2) * height,
                        scene.width * width / 2, scene.height * height / 2, 0, 0, Math.PI * 2);
                    context.fill();
                } else if (scene.type === "line") {
                    context.lineWidth = Math.max(1, (Number(scene.size) || 1) * height / 360);
                    context.beginPath();
                    context.moveTo(scene.x * width, scene.y * height);
                    context.lineTo(scene.x2 * width, scene.y2 * height);
                    context.stroke();
                } else if (scene.type === "text") {
                    var size = Math.max(11, (Number(scene.size) || 1) * height / 20);
                    context.font = (scene.bold ? "700 " : "500 ") + size +
                        "px \"Microsoft YaHei UI\", \"Noto Sans CJK SC\", \"Yu Gothic UI\", sans-serif";
                    context.save();
                    context.translate(scene.x * width, scene.y * height);
                    context.rotate((Number(scene.rotation) || 0) * Math.PI / 180);
                    context.textAlign = scene.align || "left";
                    var value = String(scene.text || "").replace(
                        /\$\{(line|destination|current|next|eta|status|game_time|train_name|service_type|carriage_number|carriage_count|upcoming_stops|direction|arrival_time)\}/g,
                        function (match, key) { return key === "game_time" ? clockText() : state[key] || ""; });
                    if (scene.vertical) {
                        Array.from(value).slice(0, 32).forEach(function (glyph, glyphIndex) {
                            context.fillText(glyph, 0, glyphIndex * size * 1.02);
                        });
                    } else {
                        var available = scene.align === "right" ? scene.x : scene.align === "center" ? Math.min(scene.x, 1 - scene.x) * 2 : 1 - scene.x;
                        context.fillText(value, 0, 0, Math.max(size * 2, available * width * 0.96));
                    }
                    context.restore();
                }
            });
            if (status) status.textContent = route.lineNames[primary] + " · " + index + "/" +
                segmentCount() + " · " + (playing ? "运行中" : "已暂停");
        }
        function rotateLanguage() {
            if (!languageCycle) return;
            profile = profiles[(profiles.indexOf(profile) + 1) % profiles.length];
            primary = profile.primary;
            secondary = profile.secondary;
            render(true);
        }
        function schedule() {
            clearTimeout(timer);
            if (!playing) return;
            timer = setTimeout(function () {
                if (pageMode === "auto" && visiblePage === "route") {
                    visiblePage = "next";
                } else if (pageMode === "auto" && visiblePage === "next") {
                    visiblePage = "arrival";
                } else {
                    index = index >= segmentCount() ? 1 : index + 1;
                    if (pageMode === "auto") visiblePage = "route";
                }
                render(true);
                schedule();
            }, pageMode === "auto" ? interval * (visiblePage === "route" ? 0.45
                    : visiblePage === "next" ? 0.30 : 0.25) : interval);
        }
        var api = {
            canvas: canvas,
            render: render,
            next: function () { index = index >= segmentCount() ? 1 : index + 1; render(true); schedule(); },
            previous: function () { index = index <= 1 ? segmentCount() : index - 1; render(true); schedule(); },
            play: function () { playing = true; render(true); schedule(); },
            pause: function () { playing = false; clearTimeout(timer); render(true); },
            setPage: function (page) {
                visiblePage = page === "next" ? "next"
                    : page === "arrival" ? "arrival"
                    : page === "route" ? "route" : visiblePage === "route" ? "next" : visiblePage === "next" ? "arrival" : "route";
                render(true); schedule();
            },
            setIndex: function (value) { index = Math.max(1, Math.min(Number(value) || 1, segmentCount())); render(true); schedule(); },
            setLanguages: function (main, extra) {
                profile = profiles.find(function (item) { return item.primary === main && item.secondary === extra; }) || profiles[0];
                primary = profile.primary; secondary = profile.secondary; render(true);
            },
            setLanguageProfile: function (id) {
                profile = profiles.find(function (item) { return item.id === id; }) || profile;
                primary = profile.primary; secondary = profile.secondary; render(true);
            },
            setDirection: function (value) {
                direction = value === "down" ? "down" : "up";
                index = Math.max(1, Math.min(index, segmentCount()));
                render(true); schedule();
            },
            destroy: function () {
                clearTimeout(timer); clearInterval(clockTimer); clearInterval(languageTimer);
                if (stage.parentNode === element) element.removeChild(stage);
            }
        };
        clockTimer = setInterval(render, 1_000);
        if (languageCycle) languageTimer = setInterval(rotateLanguage, languageInterval);
        render();
        schedule();
        return api;
    }

    root.MineScreenLinearLcd = {mount: mount};
}(typeof window === "undefined" ? this : window));
