/*
 * Bounded, dependency-free reader for RMP-style JSON projects.
 *
 * It intentionally reads only graph metadata: station names/coordinates, line colors/names,
 * and graph edges. It never executes RMP scripts, SVG, expressions, images, or arbitrary HTML.
 */
var LinearLcdRmpReader = (function () {
    "use strict";

    function normalizeColor(value) {
        var color = String(value || "").trim();
        if (!/^#[0-9a-f]{6,8}$/i.test(color)) return null;
        return color.length === 7 ? "#FF" + color.substring(1).toUpperCase()
            : "#" + color.substring(1).toUpperCase();
    }

    function deepHex(value) {
        var index;
        var keys;
        var direct = normalizeColor(value);
        if (direct) return direct;
        if (Array.isArray(value)) {
            for (index = 0; index < value.length; index += 1) {
                var arrayHit = deepHex(value[index]);
                if (arrayHit) return arrayHit;
            }
        } else if (value && typeof value === "object") {
            keys = Object.keys(value);
            for (index = 0; index < keys.length; index += 1) {
                if (/color|colour/i.test(keys[index])) {
                    var colorHit = deepHex(value[keys[index]]);
                    if (colorHit) return colorHit;
                }
            }
            for (index = 0; index < keys.length; index += 1) {
                var objectHit = deepHex(value[keys[index]]);
                if (objectHit) return objectHit;
            }
        }
        return null;
    }

    function firstText(values) {
        var index;
        if (!Array.isArray(values)) values = [values];
        for (index = 0; index < values.length; index += 1) {
            if (typeof values[index] === "string" && values[index].trim()) {
                return values[index].replace(/[\r\n]+/g, " ").trim();
            }
        }
        return "";
    }

    function simplifiedChinese(value) {
        var replacements = {
            "東": "东", "駅": "站", "線": "线", "総": "总", "鉄": "铁",
            "葉": "叶", "浜": "滨", "須": "须", "蔵": "藏", "鶴": "鹤",
            "駄": "驮", "巣": "巢", "鴨": "鸭", "ヶ": "", "門": "门",
            "國": "国", "廣": "广", "澤": "泽", "龍": "龙", "島": "岛",
            "學": "学", "橋": "桥", "臺": "台", "灣": "湾", "內": "内",
            "戶": "户", "區": "区", "體": "体", "轉": "转", "車": "车"
        };
        return Array.from(String(value || "")).map(function (character) {
            return replacements[character] || character;
        }).join("");
    }

    function namesFromAttributes(attributes) {
        var candidates = [attributes && attributes.basic && attributes.basic.names,
            attributes && attributes.names, attributes && attributes.name];
        Object.keys(attributes || {}).forEach(function (key) {
            var value = attributes[key];
            if (value && typeof value === "object") candidates.push(value.names);
        });
        var index;
        for (index = 0; index < candidates.length; index += 1) {
            var candidate = candidates[index];
            if (Array.isArray(candidate)) {
                var names = candidate.filter(function (name) {
                    return typeof name === "string" && name.trim();
                }).map(function (name) {
                    return name.replace(/[\r\n]+/g, " ").trim();
                });
                if (names.length) return names;
            }
            if (typeof candidate === "string" && candidate.trim()) return [candidate.trim()];
        }
        return [];
    }

    function attributesOf(value) {
        return value && value.attributes && typeof value.attributes === "object"
            ? value.attributes : value || {};
    }

    function stringAt(object, keys) {
        var index;
        for (index = 0; index < keys.length; index += 1) {
            var value = object && object[keys[index]];
            if (typeof value === "string" && value.trim()) return value.trim();
        }
        return "";
    }

    function lineCodeFromAttributes(attributes) {
        var style = attributes && attributes.style;
        var styled = style && attributes[style] && typeof attributes[style] === "object"
            ? attributes[style] : null;
        var color = styled && styled.color ? styled.color : attributes && attributes.color;
        if (Array.isArray(color) && typeof color[1] === "string" && color[1].trim()) {
            return color[1].trim().toLowerCase();
        }
        return "";
    }

    function numberAt(object, keys, fallback) {
        var index;
        for (index = 0; index < keys.length; index += 1) {
            var value = Number(object && object[keys[index]]);
            if (isFinite(value)) return value;
        }
        return fallback;
    }

    function pointSegmentDistance(point, start, end) {
        var dx = end.x - start.x;
        var dy = end.y - start.y;
        if (dx === 0 && dy === 0) return Math.hypot(point.x - start.x, point.y - start.y);
        var ratio = ((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy);
        ratio = Math.max(0, Math.min(1, ratio));
        return Math.hypot(point.x - (start.x + ratio * dx), point.y - (start.y + ratio * dy));
    }

    function symbolFor(name, id, fallback) {
        var source = String(name || id || "").trim();
        var compact = source.replace(/[^A-Za-z0-9]/g, "");
        if (compact && compact.length <= 4) return compact.toUpperCase();
        var words = source.match(/[A-Za-z0-9]+/g);
        if (words && words.length > 1) {
            var initials = words.map(function (word) { return word.charAt(0); }).join("");
            if (initials.length <= 4) return initials.toUpperCase();
        }
        var cjk = source.match(/[\u3040-\u30ff\u3400-\u9fff]/g);
        if (cjk && cjk.length) return cjk.slice(0, 2).join("");
        return fallback || "R";
    }

    function iconFor(name, id) {
        var source = String(name || id || "").toLowerCase();
        if (/airport|terminal|aviation|airline|空港|机场|航空|飛行/.test(source)) return "✈";
        if (/metro|subway|underground|地铁|地下鉄/.test(source)) return "M";
        if (/tram|streetcar|市电|市電|電車/.test(source)) return "T";
        if (/ferry|port|harbor|boat|渡轮|港口|码头|船/.test(source)) return "≋";
        if (/bus|coach|巴士|公交/.test(source)) return "B";
        return "";
    }

    function usableStationNames(names, key, index) {
        var synthetic = function (value) {
            var text = String(value || "").trim();
            return !text || text === key || /^misc_node_/i.test(text) || /^(node|stn|station)[_-]/i.test(text)
                || /^[a-z0-9_-]{18,}$/i.test(text);
        };
        var usable = (names || []).filter(function (value) { return !synthetic(value); });
        // Empty RMP station placeholders are geometry helpers, not passenger-facing stations.
        // Showing their source array index produced labels such as "站点 427".
        if (!usable.length) return [];
        if (usable.length === 1) usable.push(usable[0]);
        return usable.slice(0, 3);
    }

    function circularLine(metadata, group, edges) {
        var name = String(metadata && metadata.name || "");
        if (/山手|環状|环线|loop|circle|circular/i.test(name)) return true;
        var degrees = {};
        (group.edgeIds || []).forEach(function (edgeIndex) {
            var edge = edges[edgeIndex];
            degrees[edge.source] = (degrees[edge.source] || 0) + 1;
            degrees[edge.target] = (degrees[edge.target] || 0) + 1;
        });
        var nodes = Object.keys(degrees);
        return nodes.length >= 4 && nodes.every(function (key) { return degrees[key] === 2; });
    }

    function orderedStations(stations, circular) {
        if (stations.length < 3) return stations.slice();
        var center = stations.reduce(function (sum, station) {
            return {x: sum.x + station.x / stations.length,
                y: sum.y + station.y / stations.length};
        }, {x: 0, y: 0});
        if (circular) {
            var ordered = stations.slice().sort(function (left, right) {
                return Math.atan2(left.y - center.y, left.x - center.x)
                    - Math.atan2(right.y - center.y, right.x - center.x);
            });
            var start = 0;
            ordered.forEach(function (station, index) {
                var current = ordered[start];
                if (station.y < current.y || station.y === current.y && station.x < current.x) {
                    start = index;
                }
            });
            return ordered.slice(start).concat(ordered.slice(0, start));
        }
        var xx = 0;
        var xy = 0;
        var yy = 0;
        stations.forEach(function (station) {
            var dx = station.x - center.x;
            var dy = station.y - center.y;
            xx += dx * dx;
            xy += dx * dy;
            yy += dy * dy;
        });
        var angle = 0.5 * Math.atan2(2 * xy, xx - yy);
        var ux = Math.cos(angle);
        var uy = Math.sin(angle);
        if (ux < 0 || Math.abs(ux) < 0.001 && uy < 0) { ux = -ux; uy = -uy; }
        return stations.slice().sort(function (left, right) {
            var lp = (left.x - center.x) * ux + (left.y - center.y) * uy;
            var rp = (right.x - center.x) * ux + (right.y - center.y) * uy;
            return lp - rp || left.key.localeCompare(right.key);
        });
    }

    function lineMetadata(attributes, fallbackName, fallbackColor, index) {
        var name = stringAt(attributes, ["lineName", "line_name", "routeName", "route_name",
            "railwayName", "railway_name", "name", "label", "title"]);
        var id = stringAt(attributes, ["lineId", "line_id", "routeId", "route_id", "railwayId",
            "railway_id", "id", "key"]);
        var color = deepHex(attributes) || fallbackColor || "#FF3A9BFF";
        name = name || fallbackName || ("Line " + (index + 1));
        return {id: id || name || ("line_" + index), name: name,
            names: {zh: simplifiedChinese(name), ja: name, en: name},
            symbol: symbolFor(name, id, "R" + (index + 1)), icon: iconFor(name, id), color: color};
    }

    function explicitLineObjects(graph, json) {
        var result = [];
        [graph && graph.lines, graph && graph.routes, graph && graph.services,
            json && json.lines, json && json.routes].forEach(function (collection) {
            if (!Array.isArray(collection)) return;
            collection.forEach(function (item) {
                if (item && typeof item === "object") result.push(item);
            });
        });
        return result;
    }

    function readProject(json) {
        if (!json || typeof json !== "object") throw new Error("RMP root must be an object");
        var graph = json.graph || json;
        var rawNodes = Array.isArray(graph.nodes) ? graph.nodes
            : Array.isArray(json.nodes) ? json.nodes : [];
        var rawEdges = Array.isArray(graph.edges) ? graph.edges
            : Array.isArray(json.edges) ? json.edges : [];
        var nodesById = {};
        var geometryById = {};
        rawNodes.forEach(function (node, index) {
            var attributes = attributesOf(node);
            if (attributes.visible === false) return;
            var key = String(node && (node.key || node.id) || "node_" + index);
            geometryById[key] = {key: key, x: numberAt(attributes, ["x", "left"], 0),
                y: numberAt(attributes, ["y", "top"], 0)};
        });
        var badges = [];
        rawNodes.forEach(function (node) {
            var attributes = attributesOf(node);
            var type = String(attributes.type || "").toLowerCase();
            if (type.indexOf("line-badge") < 0) return;
            var typed = attributes[attributes.type] && typeof attributes[attributes.type] === "object"
                ? attributes[attributes.type] : attributes;
            var color = deepHex(typed);
            var names = namesFromAttributes(typed);
            if (color && names.length) {
                var badgeCode = lineCodeFromAttributes(typed);
                badges.push({
                    code: badgeCode, color: color.toUpperCase(),
                    x: numberAt(attributes, ["x", "left"], 0), y: numberAt(attributes, ["y", "top"], 0),
                    name: names[0], secondary: names[1] || names[0],
                    names: {zh: simplifiedChinese(names[0]), ja: names[0],
                        en: names[1] || names[0]},
                    // Preserve the identifier explicitly authored in RMP. Palette/system codes
                    // (for example JY/JK) remain metadata and never silently replace it.
                    symbol: typed.num == null ? symbolFor(names[0], "", "R") : String(typed.num),
                    systemCode: badgeCode && badgeCode !== "other" ? badgeCode.toUpperCase() : "",
                    icon: iconFor(names[0], "")
                });
            }
        });
        var nodes = rawNodes.filter(function (node) {
            return !(node && node.attributes && node.attributes.visible === false);
        }).map(function (node, index) {
            var attributes = attributesOf(node);
            var key = String(node && (node.key || node.id) || "node_" + index);
            var typed = attributes[attributes.type] && typeof attributes[attributes.type] === "object"
                ? attributes[attributes.type] : attributes;
            var names = namesFromAttributes(typed);
            var type = String(attributes.type || "").toLowerCase();
            var nonStation = /line-badge|^virtual$|^text$|facilit|airport|annotation|label/.test(type);
            var likelyStation = !nonStation && (names.length > 0 || /^stn[_-]/i.test(key)
                || /station|basic|imp|interchange|transfer|int$/.test(type));
            if (!likelyStation) return null;
            var record = {
                key: key,
                x: numberAt(attributes, ["x", "left"], 0),
                y: numberAt(attributes, ["y", "top"], 0),
                names: usableStationNames(names, key, index),
                hub: /imp|interchange|transfer|int$/.test(type),
                mostImportant: typed.mostImportant === true,
                lineCount: Array.isArray(typed.lines) && typed.lines.length
                    ? typed.lines.length : 0
            };
            nodesById[key] = record;
            return record;
        }).filter(Boolean).filter(function (station) { return station.names.length > 0; });

        var edges = rawEdges.map(function (edge, index) {
            var attributes = attributesOf(edge);
            var source = String(edge && (edge.source || edge.from) || "");
            var target = String(edge && (edge.target || edge.to) || "");
            if (!geometryById[source] || !geometryById[target] || attributes.visible === false) return null;
            var metadata = lineMetadata(attributes, "", deepHex(attributes), index);
            var explicit = stringAt(attributes, ["lineId", "line_id", "routeId", "route_id",
                "railwayId", "railway_id", "line", "route", "railway", "layer"]);
            explicit = explicit || lineCodeFromAttributes(attributes);
            return {source: source, target: target, color: metadata.color, name: metadata.name,
                lineKey: explicit || metadata.color || "default", metadata: metadata};
        }).filter(Boolean);

        var explicitLines = explicitLineObjects(graph, json);
        var groups = {};
        function ensureGroup(key, metadata) {
            if (!groups[key]) groups[key] = {key: key, metadata: metadata, edgeIds: [], nodeIds: {}};
            if (metadata && !groups[key].metadata) groups[key].metadata = metadata;
            return groups[key];
        }
        edges.forEach(function (edge, index) {
            var group = ensureGroup(edge.lineKey || "default", edge.metadata);
            group.edgeIds.push(index);
            if (nodesById[edge.source]) group.nodeIds[edge.source] = true;
            if (nodesById[edge.target]) group.nodeIds[edge.target] = true;
        });
        if (!edges.length) {
            var fallback = ensureGroup("default", lineMetadata(graph.attributes || {}, "Imported line", null, 0));
            nodes.forEach(function (node) { fallback.nodeIds[node.key] = true; });
        }
        explicitLines.forEach(function (line, index) {
            var attrs = attributesOf(line);
            var metadata = lineMetadata(attrs, "Line " + (index + 1), deepHex(attrs), index);
            var key = stringAt(line, ["id", "key", "lineId", "line_id", "routeId", "route_id"])
                || stringAt(attrs, ["id", "key", "lineId", "line_id", "routeId", "route_id"])
                || metadata.name;
            var group = ensureGroup(key, metadata);
            var stationIds = line.stations || line.nodes || attrs.stations || attrs.nodes;
            if (Array.isArray(stationIds)) stationIds.forEach(function (value) {
                var id = typeof value === "object" ? value.key || value.id : value;
                if (id != null && nodesById[String(id)]) group.nodeIds[String(id)] = true;
            });
        });

        // One RMP color/code can contain several disconnected services (for example JR-East's
        // green JY code is used by both Yamanote and Yokohama sections). Split by graph connectivity
        // first, then use the nearest RMP line-badge label to recover the human-facing line name.
        var splitGroups = {};
        Object.keys(groups).forEach(function (baseKey) {
            var base = groups[baseKey];
            if (base.edgeIds.length <= 1) {
                splitGroups[baseKey] = base;
                return;
            }
            var byNode = {};
            base.edgeIds.forEach(function (edgeIndex) {
                var edge = edges[edgeIndex];
                [edge.source, edge.target].forEach(function (nodeKey) {
                    if (!byNode[nodeKey]) byNode[nodeKey] = [];
                    byNode[nodeKey].push(edgeIndex);
                });
            });
            var unseen = {};
            base.edgeIds.forEach(function (edgeIndex) { unseen[edgeIndex] = true; });
            var componentIndex = 0;
            while (Object.keys(unseen).length) {
                var seed = Number(Object.keys(unseen)[0]);
                var stack = [seed];
                var component = [];
                delete unseen[seed];
                while (stack.length) {
                    var currentEdgeIndex = stack.pop();
                    var currentEdge = edges[currentEdgeIndex];
                    component.push(currentEdgeIndex);
                    [currentEdge.source, currentEdge.target].forEach(function (nodeKey) {
                        (byNode[nodeKey] || []).forEach(function (neighbor) {
                            if (unseen[neighbor]) {
                                delete unseen[neighbor];
                                stack.push(neighbor);
                            }
                        });
                    });
                }
                splitGroups[baseKey + "#" + componentIndex] = {
                    key: baseKey + "#" + componentIndex, metadata: base.metadata,
                    edgeIds: component, nodeIds: {}
                };
                componentIndex += 1;
            }
        });
        groups = splitGroups;

        Object.keys(groups).forEach(function (key) {
            var group = groups[key];
            if (!group.edgeIds.length || !badges.length) return;
            var code = String(group.key).split("#")[0].toLowerCase();
            var matching = badges.filter(function (badge) {
                return (badge.code && badge.code.toLowerCase() === code)
                    || badge.color === String(group.metadata.color || "").toUpperCase();
            });
            if (!matching.length) matching = badges;
            function distanceToGroup(badge) {
                return group.edgeIds.reduce(function (minimum, edgeIndex) {
                    var edge = edges[edgeIndex];
                    return Math.min(minimum, pointSegmentDistance(badge,
                        geometryById[edge.source], geometryById[edge.target]));
                }, Infinity);
            }
            var badge = matching.slice().sort(function (left, right) {
                return distanceToGroup(left) - distanceToGroup(right);
            })[0];
            if (badge) group.metadata = Object.assign({}, group.metadata, badge);
        });

        var mergedGroups = {};
        Object.keys(groups).forEach(function (key) {
            var group = groups[key];
            var metadata = group.metadata || {};
            var mergeKey = String(metadata.name || metadata.symbol || key) + "|"
                + String(metadata.color || "");
            if (!mergedGroups[mergeKey]) {
                mergedGroups[mergeKey] = {key: key, metadata: metadata, edgeIds: [], nodeIds: {}};
            }
            mergedGroups[mergeKey].edgeIds = mergedGroups[mergeKey].edgeIds.concat(group.edgeIds);
            Object.keys(group.nodeIds).forEach(function (nodeKey) {
                mergedGroups[mergeKey].nodeIds[nodeKey] = true;
            });
        });
        groups = mergedGroups;

        nodes.forEach(function (station) {
            var candidates = Object.keys(groups).map(function (key) {
                var group = groups[key];
                if (!group.edgeIds.length) return null;
                var minimum = Infinity;
                group.edgeIds.forEach(function (edgeIndex) {
                    var edge = edges[edgeIndex];
                    minimum = Math.min(minimum, pointSegmentDistance(station,
                        geometryById[edge.source], geometryById[edge.target]));
                });
                return {group: group, distance: minimum};
            }).filter(Boolean).sort(function (left, right) { return left.distance - right.distance; });
            var radius = station.mostImportant ? 48 : station.hub ? 34 : 22;
            var nearby = candidates.filter(function (candidate) { return candidate.distance <= radius; });
            var limit = station.lineCount > 0 ? station.lineCount
                : station.hub ? Math.min(12, nearby.length) : 1;
            nearby.slice(0, Math.min(limit, nearby.length)).forEach(function (candidate) {
                // RMP separates parallel tracks and station labels by a few map units. The nearest
                // expected line memberships are more reliable than a broad radius. Important hubs
                // deliberately accept every nearby track so Tokyo-like stations keep all transfers.
                candidate.group.nodeIds[station.key] = true;
            });
        });

        var groupList = Object.keys(groups).map(function (key, index) {
            var group = groups[key];
            var metadata = group.metadata || lineMetadata({}, "Line " + (index + 1), null, index);
            var circular = circularLine(metadata, group, edges);
            var stationList = orderedStations(Object.keys(group.nodeIds)
                .map(function (nodeKey) { return nodesById[nodeKey]; }).filter(Boolean), circular);
            return {
                id: String(key), name: metadata.name, secondaryName: metadata.secondary || metadata.name,
                names: metadata.names || {zh: simplifiedChinese(metadata.name), ja: metadata.name,
                    en: metadata.secondary || metadata.name},
                symbol: metadata.symbol, systemCode: metadata.systemCode || "", icon: metadata.icon, color: metadata.color,
                stations: stationList, edgeCount: group.edgeIds.length, circular: circular
            };
        }).filter(function (line) { return line.stations.length > 0; });

        var memberships = {};
        groupList.forEach(function (line) {
            line.stations.forEach(function (station) {
                if (!memberships[station.key]) memberships[station.key] = [];
                memberships[station.key].push(line);
            });
        });
        groupList.forEach(function (line) {
            line.stations = line.stations.map(function (station, index) {
                var transfers = (memberships[station.key] || []).filter(function (other) {
                    return other.id !== line.id;
                }).slice(0, 12).map(function (other) {
                    var otherIndex = other.stations.findIndex(function (candidate) {
                        return candidate.key === station.key;
                    });
                    return {symbol: other.symbol, systemCode: other.systemCode || "",
                        icon: other.icon, color: other.color,
                        stationCode: otherIndex < 0 ? "" : String(otherIndex + 1).padStart(2, "0"),
                        stationNames: {
                            zh: simplifiedChinese(station.names[0] || station.key),
                            ja: station.names[0] || station.key,
                            en: station.names[1] || station.names[0] || station.key
                        },
                        names: {zh: other.names && other.names.zh
                                || simplifiedChinese(other.name),
                            ja: other.names && other.names.ja || other.name,
                            en: other.names && other.names.en
                                || other.secondaryName || other.name}};
                });
                return {key: station.key, code: String(index + 1).padStart(2, "0"),
                    x: station.x, y: station.y, names: {
                        zh: simplifiedChinese(station.names[0] || station.key),
                        ja: station.names[0] || station.key,
                        en: station.names[1] || station.names[0] || station.key
                    }, travelMinutes: index === 0 ? 0 : 2, transfers: transfers};
            });
        });

        var graphName = stringAt(graph.attributes || {}, ["name", "title"]) ||
            stringAt(json, ["name", "title"]) || "Imported RMP";
        var first = groupList[0] || {name: graphName, symbol: "RMP", color: deepHex(graph) || "#FF3A9BFF", stations: []};
        return {
            name: graphName,
            color: first.color,
            symbol: first.symbol,
            icon: first.icon || "",
            circular: first.circular === true,
            stations: first.stations,
            lines: groupList,
            warnings: groupList.length
                ? ["RMP 的节点顺序按坐标排序；请选择线路后再确认站序和路段时间。"]
                : ["没有找到可用的可见站点。"]
        };
    }

    return {readProject: readProject, deepHex: deepHex, namesFromAttributes: namesFromAttributes,
        simplifiedChinese: simplifiedChinese};
}());

if (typeof module === "object" && module && module.exports) {
    module.exports = LinearLcdRmpReader;
}
