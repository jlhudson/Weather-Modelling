// The map: every hexagon held, "now" and the forecast kept apart, and one question at a time.
//
// One side panel (W-23) with three tabs - Now from the ground, the Forecast from the model with the
// fire picture, the Drought behind the indices - each with what to colour by, the controls it turns
// (the station reach and what counts as a wind change under Now; the drought's rule under Drought,
// with its feed drawn as spokes), what else to draw, the figures and the legend. Outlines are drawn
// around regions - the outermost hexagons of a class, of the drought, of a wind change - not around
// every cell, unless Borders is on. The timeline under the map runs a week back over the ground's
// record and three days ahead over the forecasts: behind now the layer is what was "now" then,
// ahead it is the series read at that hour, and the tab follows (the future has no "now"). A click
// opens everything held for a hexagon - or, on the Drought tab's feed, spins its drought up.
// Deferred, so it runs after Leaflet and console.js.
(function () {
    'use strict';
    var $ = function (id) { return document.getElementById(id); };
    var esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]; }); };
    var fmt = function (v, d) { return v == null ? '—' : (typeof v === 'number' ? (d == null ? v : v.toFixed(d)) : String(v)); };
    var when = function (iso) { if (!iso) return '—'; var d = new Date(iso); return isNaN(d) ? String(iso) : d.toLocaleString(undefined, {weekday: 'short', day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit'}); };
    var clock = function (iso) { if (!iso) return '—'; var d = new Date(iso); return isNaN(d) ? String(iso) : d.toLocaleTimeString(undefined, {hour: '2-digit', minute: '2-digit'}); };
    var ago = function (iso) { if (!iso) return '—'; var m = Math.round((Date.now() - Date.parse(iso)) / 60000); return m < 1 ? 'just now' : m < 120 ? m + ' min ago' : m < 2880 ? Math.round(m / 60) + ' h ago' : Math.round(m / 1440) + ' d ago'; };
    var in_ = function (iso) { if (!iso) return '—'; var m = Math.round((Date.parse(iso) - Date.now()) / 60000); return m <= 0 ? 'on the next ask' : m < 120 ? 'in ' + m + ' min' : 'in ' + Math.round(m / 60) + ' h'; };
    var icon = function (id) { return '<svg class="ico"><use href="#i-' + id + '"/></svg>'; };
    var cap = function (s) { return s.charAt(0).toUpperCase() + s.slice(1); };

    // ---- the catalogue: every layer, by group. A side group's variables read now*, fc* or diff* properties.
    var VARS = {
        now: [
            {id: 'from', name: 'Source', hint: 'where "now" comes from', icon: 'source', kind: 'from'},
            {id: 'temperatureC', name: 'Temperature', unit: '°C', icon: 'temp', range: [0, 45]},
            {id: 'humidityPct', name: 'Humidity', unit: '%', icon: 'humidity', range: [0, 100], reverse: true},
            {id: 'windKmh', name: 'Wind', unit: 'km/h', icon: 'wind', range: [0, 80]},
            {id: 'gustKmh', name: 'Gust', unit: 'km/h', icon: 'gust', range: [0, 110]},
            {id: 'rainMm', name: 'Rain', hint: 'since 9 am', unit: 'mm', icon: 'rain', range: [0, 25]},
            {id: 'age', name: 'Age', hint: 'of the observation', unit: 'min', icon: 'clock', range: [0, 180], reverse: true}
        ],
        fc: [
            {id: 'life', name: 'Life', hint: 'fading to expiry', icon: 'clock', kind: 'life'},
            {id: 'temperatureC', name: 'Temperature', unit: '°C', icon: 'temp', range: [0, 45]},
            {id: 'humidityPct', name: 'Humidity', unit: '%', icon: 'humidity', range: [0, 100], reverse: true},
            {id: 'windKmh', name: 'Wind', unit: 'km/h', icon: 'wind', range: [0, 80]},
            {id: 'gustKmh', name: 'Gust', unit: 'km/h', icon: 'gust', range: [0, 110]},
            {id: 'rainMm', name: 'Rain', hint: 'this hour', unit: 'mm', icon: 'rain', range: [0, 10]},
            {id: 'age', name: 'Age', hint: 'of the fetch', unit: 'min', icon: 'clock', range: [0, 300], reverse: true}
        ],
        diff: [
            {id: 'temperatureC', name: 'Temperature', hint: 'now − forecast', unit: '°C', icon: 'temp', lim: 5},
            {id: 'humidityPct', name: 'Humidity', hint: 'now − forecast', unit: 'pts', icon: 'humidity', lim: 25},
            {id: 'windKmh', name: 'Wind', hint: 'now − forecast', unit: 'km/h', icon: 'wind', lim: 20},
            {id: 'drift', name: 'Drift', hint: 'stations vs forecast', icon: 'drift', kind: 'drift'},
            {id: 'drift24h', name: 'Drift', hint: '24 h mean', icon: 'drift', kind: 'drift'}
        ],
        fire: [
            {id: 'ffdi', name: 'FFDI', hint: 'forest', icon: 'fire', kind: 'rating', ratingOf: 'ffdiRating', range: [0, 100]},
            {id: 'gfdi', name: 'GFDI', hint: 'grass', icon: 'grass', kind: 'rating', ratingOf: 'gfdiRating', range: [0, 150]},
            {id: 'fbi', name: 'FBI', hint: 'AFDRS grass', icon: 'fire', kind: 'rating', ratingOf: 'afdrsRating', range: [0, 100]},
            {id: 'officialRating', name: 'Official', hint: 'CFS rating', icon: 'badge', kind: 'category', palette: 'RATING'},
            {id: 'droughtFactor', name: 'Drought factor', icon: 'drought', range: [0, 10]},
            {id: 'kbdiMm', name: 'KBDI', unit: 'mm', icon: 'drought', range: [0, 203]},
            {id: 'curingPct', name: 'Curing', unit: '%', icon: 'grass', range: [0, 100]}
        ],
        ground: [
            {id: 'elevationM', name: 'Elevation', unit: 'm', icon: 'elevation', range: [0, 1500]},
            {id: 'landDominant', name: 'Land use', hint: 'largest share', icon: 'land', kind: 'category', palette: 'LAND'},
            {id: 'leads', name: 'Leads', hint: 'which index', icon: 'leads', kind: 'category', palette: 'LEADS'},
            {id: 'burnablePct', name: 'Burnable', unit: '%', icon: 'grass', range: [0, 100]}
        ],
        shift: [
            {id: 'shift', name: 'Both', hint: 'the higher grade', icon: 'wind', kind: 'category', palette: 'SHIFT'},
            {id: 'shiftDeg', name: 'Direction', hint: 'swing, degrees', unit: '°', icon: 'arrow', range: [0, 180]},
            {id: 'shiftKmh', name: 'Speed', hint: 'now − then', unit: 'km/h', icon: 'gust', lim: 30, diverging: true}
        ],
        asks: [
            {id: 'asked', name: 'Last asked', unit: 'min ago', icon: 'ask', range: [0, 120], reverse: true},
            {id: 'asks', name: 'Asks', hint: 'this run', icon: 'count', range: [0, 50]},
            {id: 'kind', name: 'Kind', icon: 'kind', kind: 'category', palette: 'KIND'}
        ],
        // The coverage (W-18): the stations counting for each hexagon at the reach on the slider - one is "now"
        // for free, two or more are blended - and of those, the ones reporting. Drawn from its own feed, not the layer.
        coverage: [
            {id: 'stations', name: 'Within reach', hint: 'stations counting for it', icon: 'station', kind: 'count'},
            {id: 'reporting', name: 'Reporting', hint: 'of those, fresh now', icon: 'clock', kind: 'count'}
        ],
        // The drought as a thing of its own (W-22): the deficit and the factor, the rain in its window, where its
        // inputs came from; and its feed - every spun-up hexagon with a spoke to each station feeding it - from its own feed.
        drought: [
            {id: 'droughtFactor', name: 'Drought factor', icon: 'drought', range: [0, 10]},
            {id: 'kbdiMm', name: 'KBDI', unit: 'mm', icon: 'drought', range: [0, 203]},
            {id: 'rain7dMm', name: 'Rain 7 d', unit: 'mm', icon: 'rain', range: [0, 50], reverse: true},
            {id: 'rain20dMm', name: 'Rain 20 d', unit: 'mm', icon: 'rain', range: [0, 100], reverse: true},
            {id: 'droughtFrom', name: 'Inputs', hint: 'where its days came from', icon: 'source', kind: 'category', palette: 'INPUTS'},
            {id: 'droughtDays', name: 'Days', hint: 'integrated over', icon: 'count', range: [0, 400]},
            {id: 'curingPct', name: 'Curing', unit: '%', icon: 'grass', range: [0, 100]},
            {id: 'feed', name: 'Feed', hint: 'what feeds each drought', icon: 'station', kind: 'feed'}
        ]
    };
    // The tabs (W-23): which chips each shows, in order; the Hexagon group (ground, asks) folds under every tab.
    var TABS = {
        now: [['now', 'from'], ['now', 'temperatureC'], ['now', 'humidityPct'], ['now', 'windKmh'], ['now', 'gustKmh'], ['now', 'rainMm'], ['now', 'age'],
            ['shift', 'shift'], ['shift', 'shiftDeg'], ['shift', 'shiftKmh'], ['coverage', 'stations'], ['coverage', 'reporting']],
        forecast: [['fc', 'life'], ['fc', 'temperatureC'], ['fc', 'humidityPct'], ['fc', 'windKmh'], ['fc', 'gustKmh'], ['fc', 'rainMm'], ['fc', 'age'],
            ['diff', 'temperatureC'], ['diff', 'humidityPct'], ['diff', 'windKmh'], ['diff', 'drift'], ['diff', 'drift24h'],
            ['fire', 'ffdi'], ['fire', 'gfdi'], ['fire', 'fbi'], ['fire', 'officialRating']],
        drought: [['drought', 'droughtFactor'], ['drought', 'kbdiMm'], ['drought', 'rain7dMm'], ['drought', 'rain20dMm'], ['drought', 'droughtFrom'], ['drought', 'droughtDays'], ['drought', 'curingPct'], ['drought', 'feed']]
    };
    var HEXAGON_CHIPS = [['ground', 'elevationM'], ['ground', 'landDominant'], ['ground', 'leads'], ['ground', 'burnablePct'], ['asks', 'asked'], ['asks', 'asks'], ['asks', 'kind']];
    var GROUP_NAMES = {now: 'Now · from the ground', fc: 'Forecast · the model', diff: 'Compare · now minus forecast', shift: 'Wind change · last hour', fire: 'Fire', ground: 'Ground', asks: 'Requests', coverage: 'Coverage · stations within reach', drought: 'Drought'};
    var SHIFT = {slight: '#eab308', marked: '#f97316', sharp: '#ef4444'};
    var TAB_CAPTIONS = {
        now: '<b>Now</b> is what the ground says: the station in each hexagon, several blended, or the neighbours brought to its height. Blue. The wind change and the coverage are turned here.',
        forecast: '<b>Forecast</b> is what the model says for this hour, for the hexagons holding one, and the fire picture drawn from it. Amber. Drag the timeline to see the hours ahead.',
        drought: '<b>Drought</b> is the soil moisture deficit behind the fire indices: its own for a hexagon spun up, its neighbours\' for one that was not. Purple. The feed shows what feeds each, and the rule is turned here.'
    };

    // ---- colour: the ground in blues, the model in amber, ratings as published, categories fixed, numbers on a ramp.
    var FROM = {station: '#2563eb', stations: '#1e3a8a', neighbours: '#0d9488', model: '#f59e0b', none: '#9ca3af'};
    var FROM_WORDS = {station: 'station in it', stations: 'stations in it, blended', neighbours: 'neighbours, brought to its height', model: 'model standing in', none: 'nothing yet'};
    var PALETTES = {
        RATING: {'LOW-MODERATE': '#9bc466', 'HIGH': '#f7e463', 'VERY HIGH': '#f0a04b', 'SEVERE': '#e35d3c', 'EXTREME': '#c1272d', 'CATASTROPHIC': '#6d2077',
            'No Rating': '#dddddd', 'Moderate': '#7fc47f', 'High': '#f7e463', 'Extreme': '#f0a04b', 'Catastrophic': '#c1272d'},
        KIND: {station: '#3b82f6', forecast: '#f59e0b', both: '#a855f7', bare: '#9ca3af'},
        LEADS: {forest: '#15803d', grass: '#ca8a04'},
        LAND: {forest: '#14532d', scrub: '#4d7c0f', grassland: '#ca8a04', cropland: '#eab308', built_up: '#6b7280', water: '#2563eb', bare: '#a16207', unknown: '#9ca3af'},
        SHIFT: {slight: '#eab308', marked: '#f97316', sharp: '#ef4444'},
        INPUTS: {stations: '#2563eb', 'stations+archive': '#7c3aed', archive: '#a855f7', interpolated: '#d8b4fe'}
    };
    var DROUGHT = '#a855f7';
    // The coverage's count: none in grey, one in the ground's blue, two and more darker - past one it is a blend.
    var COUNT = ['#9ca3af', '#2563eb', '#1e3a8a', '#4c1d95'];
    var COUNT_WORDS = ['none', 'one · its "now"', 'two · blended', 'three or more · blended'];
    function ramp(t) {
        t = Math.max(0, Math.min(1, t));
        var stops = [[0, [33, 102, 172]], [.5, [247, 247, 190]], [1, [178, 24, 43]]];
        for (var i = 1; i < stops.length; i++) {
            if (t <= stops[i][0]) {
                var f = (t - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0]), a = stops[i - 1][1], b = stops[i][1];
                return 'rgb(' + Math.round(a[0] + (b[0] - a[0]) * f) + ',' + Math.round(a[1] + (b[1] - a[1]) * f) + ',' + Math.round(a[2] + (b[2] - a[2]) * f) + ')';
            }
        }
        return 'rgb(178,24,43)';
    }
    // The stations against the forecast: green agrees, amber halfway to the tolerance, red at it (thrown out), dark red beyond.
    function driftColour(score) {
        if (score == null) return null;
        if (score >= 1.25) return '#7f1d1d';
        if (score >= 1) return '#dc2626';
        if (score >= .5) return 'rgb(' + Math.round(245 + (220 - 245) * (score - .5) * 2) + ',' + Math.round(158 + (38 - 158) * (score - .5) * 2) + ',11)';
        return 'rgb(' + Math.round(34 + (245 - 34) * score * 2) + ',' + Math.round(197 + (158 - 197) * score * 2) + ',' + Math.round(94 + (11 - 94) * score * 2) + ')';
    }
    // How much of a forecast's life is left: 1 just fetched, 0 at expiry or once stale.
    function freshness(p) {
        if (p.stale) return 0;
        if (!p.fcFetchedAt || !p.fcExpiresAt) return 1;
        var from = Date.parse(p.fcFetchedAt), to = Date.parse(p.fcExpiresAt);
        if (isNaN(from) || isNaN(to) || to <= from) return 1;
        return Math.max(0, Math.min(1, (to - Date.now()) / (to - from)));
    }

    // ---- state
    var map = L.map('map', {zoomControl: false}).setView([-34.93, 138.6], 7);
    L.control.zoom({position: 'bottomright'}).addTo(map);
    window.gullyBaseLayer(map);
    var canvas = L.canvas({padding: .3});
    var hexLayer = L.geoJSON(null, {style: styleOf, onEachFeature: onHexagon, filter: shown}).addTo(map);
    var pointLayer = L.layerGroup().addTo(map);
    var gridLayer = L.geoJSON(null, {style: {color: '#888', weight: .6, fill: false, opacity: .6}, interactive: false});
    // The coverage stands in for the hexagons while its group is chosen: thousands of cells, so on the canvas.
    var coverageLayer = L.geoJSON(null, {style: coverageStyle, onEachFeature: onCoverage, renderer: canvas}).addTo(map);
    // Region outlines (W-23) over the fills, the drought's feed (W-22) over those, the stations and the glyphs on top.
    var markLayer = L.layerGroup().addTo(map);
    var feedLayer = L.layerGroup().addTo(map);
    var stationLayer = L.layerGroup().addTo(map);
    var glyphLayer = L.layerGroup().addTo(map);
    var state = {tab: 'now', group: 'now', id: 'from', hours: 0, mode: 'now', playing: null};
    var togs = {stations: true, wind: true, trend: true, labels: true, regions: true, borders: false, grid: false, points: false, forecasts: false};
    var inView = true;
    var etag = null, lastFc = null, lastStations = null, lastSources = null, lastCoverage = null, lastFeed = null;

    function current() {
        var list = VARS[state.group] || [];
        for (var i = 0; i < list.length; i++) if (list[i].id === state.id) return list[i];
        return list[0];
    }
    // The tab a chip belongs to; the Hexagon chips belong to whichever tab is open.
    function tabOf(g, id) {
        var tabs = Object.keys(TABS);
        for (var i = 0; i < tabs.length; i++) if (TABS[tabs[i]].some(function (c) { return c[0] === g && c[1] === id; })) return tabs[i];
        return state.tab;
    }
    // Which groups the timeline allows: behind now the ground's record holds "now" and nothing else (W-19); ahead only the forecast.
    function allowed(g) {
        if (state.mode === 'ahead') return g === 'fc' || g === 'fire' || g === 'ground';
        if (state.mode === 'history') return g === 'now' || g === 'ground' || g === 'asks';
        return true;
    }
    function tabAllowed(t) { return t === 'now' ? state.mode !== 'ahead' : t === 'forecast' ? state.mode !== 'history' : state.mode === 'now'; }
    function isCoverage() { return state.group === 'coverage'; }
    function isFeed() { return state.group === 'drought' && state.id === 'feed'; }
    function shiftColour(grade) { return grade ? SHIFT[grade] : null; }
    function valueOf(p, v, g) {
        v = v || current(); g = g || state.group;
        if (g === 'coverage' || g === 'drought') return v.kind === 'feed' ? (p.hasDrought ? 1 : null) : p[v.id];
        if (g === 'now') return v.id === 'from' ? (p.from || 'none') : v.id === 'age' ? p.nowAgeMinutes : p['now' + cap(v.id)];
        if (g === 'fc') return v.id === 'life' ? (p.hasForecast ? freshness(p) : null) : v.id === 'age' ? p.fcAgeMinutes : p['fc' + cap(v.id)];
        if (g === 'diff') return v.kind === 'drift' ? p[v.id] : p['diff' + cap(v.id)];
        if (g === 'shift') return v.id === 'shift' ? p.windShift : v.id === 'shiftDeg' ? (p.windShiftSwing ? p.windShiftDeg : null) : (p.windShiftSpeed ? p.windShiftKmh : null);
        if (v.id === 'asked') return p.askedMinutesAgo;
        return p[v.id];
    }
    function colourOf(p) {
        var v = current(), x = valueOf(p, v);
        if (x == null) return null;
        if (v.kind === 'from') return FROM[x] || FROM.none;
        if (v.kind === 'count') return COUNT[Math.min(3, x)];
        if (v.kind === 'feed') return p.droughtInterpolated ? PALETTES.INPUTS.interpolated : DROUGHT;
        if (v.kind === 'life') return '#f59e0b';
        if (v.kind === 'drift') return driftColour(x);
        if (v.kind === 'category') return PALETTES[v.palette][x] || '#9ca3af';
        if (v.kind === 'rating') { var r = p[v.ratingOf]; return (r && PALETTES.RATING[r]) || ramp(x / v.range[1]); }
        if (state.group === 'diff' || v.diverging) return ramp(.5 + Math.max(-1, Math.min(1, x / v.lim)) / 2);
        var t = (x - v.range[0]) / (v.range[1] - v.range[0]);
        return ramp(v.reverse ? 1 - t : t);
    }
    function opacityOf(p, c) {
        var v = current();
        if (v.kind === 'from') return valueOf(p, v) === 'none' ? (p.hasStation ? .12 : .03) : valueOf(p, v) === 'model' ? .1 + .4 * freshness(p) : .5;
        if (v.kind === 'life') return p.hasForecast ? .1 + .5 * freshness(p) : .03;
        if (v.kind === 'feed') return c ? .35 : .03;
        if (!c) return .04;
        return state.group === 'fc' && state.mode !== 'ahead' ? .25 + .35 * freshness(p) : .55;
    }
    function shown(f) { return !togs.forecasts || f.properties.hasForecast; }
    function styleOf(f) {
        var p = f.properties, c = colourOf(p);
        if (current().kind === 'from' && valueOf(p) === 'none' && p.hasStation) c = FROM.station;
        // The hexagon's own outline only when Borders is on: the marks - a wind change, the drought, the warm ones -
        // are drawn around their regions instead (W-23), unless Regions is off, in which case they ring each cell.
        var shift = state.mode === 'now' ? shiftColour(p.windShift) : null;
        var line = togs.borders ? {color: p.warm ? '#111' : '#777', weight: p.warm ? 1.2 : .6, opacity: .5} : {color: '#000', weight: 0, opacity: 0};
        if (!togs.regions) {
            if (shift) line = {color: shift, weight: 2.4, opacity: 1};
            else if (p.hasDrought) line = {color: DROUGHT, weight: 1.6, opacity: .9};
        }
        return {color: line.color, weight: line.weight, opacity: line.opacity, dashArray: p.stale && state.mode === 'now' && togs.borders ? '4 3' : null, fillColor: c || '#000', fillOpacity: opacityOf(p, c)};
    }
    // Region outlines (W-23): an edge is drawn only where the hexagon across it is not in the same class, so a
    // region is outlined once, by its outermost hexagons. The classes: the drought stepped (purple), a wind change
    // by grade, and - for a categorical layer - each class of the layer in its own colour.
    function regionEdges(features, classOf) {
        var edges = {};
        features.forEach(function (f) {
            var cls = classOf(f.properties);
            if (cls == null) return;
            var ring = f.geometry.coordinates[0];
            for (var i = 0; i < ring.length - 1; i++) {
                var a = ring[i][1].toFixed(4) + ',' + ring[i][0].toFixed(4), b = ring[i + 1][1].toFixed(4) + ',' + ring[i + 1][0].toFixed(4);
                var key = a < b ? a + '|' + b : b + '|' + a;
                var e = edges[key] || (edges[key] = {classes: [], a: [ring[i][1], ring[i][0]], b: [ring[i + 1][1], ring[i + 1][0]]});
                e.classes.push(cls);
            }
        });
        var out = {};
        Object.keys(edges).forEach(function (k) {
            var e = edges[k];
            e.classes.forEach(function (cls) {
                // Shared by two hexagons of the class: inside the region. Once: on its edge.
                if (e.classes.filter(function (x) { return x === cls; }).length === 1) (out[cls] || (out[cls] = [])).push([e.a, e.b]);
            });
        });
        return out;
    }
    function marks() {
        markLayer.clearLayers();
        if (!togs.regions || !lastFc || pointsMode() || isCoverage()) return;
        var feats = lastFc.features.filter(shown), v = current();
        function drawRegions(byClass, colourOf, weight, dash) {
            Object.keys(byClass).forEach(function (cls) {
                var col = colourOf(cls);
                if (!col) return;
                L.polyline(byClass[cls], {renderer: canvas, color: col, weight: weight, opacity: .95, dashArray: dash || null, interactive: false, lineCap: 'round', lineJoin: 'round'}).addTo(markLayer);
            });
        }
        // A categorical layer: each class outlined in its colour, a little bolder than the fill.
        if (v.kind === 'from' || v.kind === 'category' || v.kind === 'rating' || v.kind === 'feed') {
            drawRegions(regionEdges(feats, function (p) { var x = valueOf(p, v); return x == null || x === 'none' ? null : String(x); }), function (cls) {
                return v.kind === 'from' ? FROM[cls] : v.kind === 'feed' ? DROUGHT : v.kind === 'rating' ? PALETTES.RATING[cls] : PALETTES[v.palette][cls];
            }, 1.6);
        }
        // The drought stepped, whatever the layer (purple); and the wind changes by grade, when the map is at now.
        if (state.group !== 'drought') drawRegions(regionEdges(feats, function (p) { return p.hasDrought ? 'drought' : null; }), function () { return DROUGHT; }, 1.4, '6 4');
        if (state.mode === 'now') drawRegions(regionEdges(feats, function (p) { return p.windShift || null; }), function (cls) { return SHIFT[cls]; }, 2.4);
    }
    // A coverage cell: the count's colour, faint at none; a cell nothing holds yet is outlined dashed.
    function coverageStyle(f) {
        var p = f.properties, x = valueOf(p), c = COUNT[Math.min(3, x || 0)];
        var line = togs.borders ? {color: p.held ? '#777' : '#999', weight: p.held ? .6 : .5, opacity: .5, dashArray: p.held ? null : '2 3'} : {color: '#000', weight: 0, opacity: 0, dashArray: null};
        return {color: line.color, weight: line.weight, opacity: line.opacity, dashArray: line.dashArray, fillColor: c, fillOpacity: x ? .55 : (p.held ? .12 : .06)};
    }

    // ---- the rail
    function varOf(g, id) { return (VARS[g] || []).filter(function (x) { return x.id === id; })[0]; }
    function buildSide() {
        var vars = $('varChips');
        vars.innerHTML = '';
        $('varsTitle').textContent = 'Colour by';
        TABS[state.tab].forEach(function (c) { var x = varOf(c[0], c[1]); if (x) vars.appendChild(chip(x, c[0])); });
        var hex = document.querySelector('.chips[data-group=hexagon]');
        hex.innerHTML = '';
        HEXAGON_CHIPS.forEach(function (c) { var x = varOf(c[0], c[1]); if (x) hex.appendChild(chip(x, c[0])); });
        // The tab's controls: shown for the chip that turns them, lit while that layer is what the map shows.
        $('reach').classList.toggle('hidden', !isCoverage());
        $('reach').classList.toggle('on', isCoverage());
        $('windChange').classList.toggle('hidden', state.group !== 'shift');
        $('windChange').classList.toggle('on', state.group === 'shift');
        $('droughtRule').classList.toggle('hidden', !isFeed());
        $('droughtRule').classList.toggle('on', isFeed());
        ['reachKm', 'reachSet', 'windSwing', 'windSpeed', 'windSet', 'droughtRings', 'droughtKm', 'droughtSet'].forEach(function (id) { $(id).disabled = state.mode !== 'now'; });
        document.querySelectorAll('#tabs button').forEach(function (b) {
            b.classList.toggle('on', b.dataset.tab === state.tab);
            b.disabled = !tabAllowed(b.dataset.tab);
        });
        $('side').dataset.tab = state.tab;
        $('sideCaption').innerHTML = TAB_CAPTIONS[state.tab];
        Object.keys(togs).forEach(function (k) { var b = document.querySelector('.tog[data-tog=' + k + ']'); if (b) b.classList.toggle('on', togs[k]); });
    }
    function chip(x, g) {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'chip' + (g === state.group && x.id === state.id ? ' on' : '');
        b.disabled = !allowed(g);
        b.innerHTML = icon(x.icon) + '<span>' + esc(x.name) + (x.hint ? '<small>' + esc(x.hint) + '</small>' : x.unit ? '<small>' + esc(x.unit) + '</small>' : '') + '</span>';
        b.addEventListener('click', function () { choose(g, x.id); });
        return b;
    }
    function choose(g, id) {
        var was = state.group, wasFeed = isFeed();
        state.group = g; state.id = id;
        state.tab = tabOf(g, id);
        buildSide();
        // Into or out of the coverage, the map swaps what it draws; within it, the feed is asked again at the slider.
        if (g === 'coverage' || was === 'coverage') draw();
        if (g === 'coverage') coverage();
        // The drought's feed is its own layer over the hexagons, asked at the sliders' rule.
        if (isFeed()) droughtFeed(); else if (wasFeed) feedLayer.clearLayers();
        legend(); restyle(); glyphs(); stations(); tiles();
    }
    function pickTab(tab) {
        if (!tabAllowed(tab)) { note(state.mode === 'ahead' ? 'ahead of now there is only the forecast' : 'behind now there is only what the ground recorded'); return; }
        // The same variable on the new tab where it exists, else the tab's first.
        var chips = TABS[tab], same = chips.filter(function (c) { return c[1] === state.id; })[0], temp = chips.filter(function (c) { return c[1] === 'temperatureC'; })[0];
        var pick = same || temp || chips[0];
        choose(pick[0], pick[1]);
        var v = current(), n = drawnProps().filter(function (p) { return valueOf(p, v) != null; }).length;
        note(cap(tab) + ' · ' + v.name.toLowerCase() + ' — ' + n + ' hexagon' + (n === 1 ? '' : 's') + (tab === 'forecast' ? ' hold a forecast' : tab === 'drought' ? ' hold a drought' : ' from the ground'));
    }
    // The timeline moved: the rail follows what the layer can show then.
    function reconcile() {
        if (!allowed(state.group)) {
            var wasFeed = isFeed();
            if (state.mode === 'ahead') {
                note('the future has no "now": showing the forecast');
                state.tab = 'forecast'; state.group = 'fc'; state.id = VARS.fc.some(function (x) { return x.id === state.id; }) ? state.id : 'temperatureC';
            } else {
                note('behind now the layer is what the ground recorded then');
                state.tab = 'now'; state.group = 'now'; state.id = VARS.now.some(function (x) { return x.id === state.id; }) ? state.id : 'from';
            }
            if (wasFeed) feedLayer.clearLayers();
        }
        buildSide();
    }

    // ---- the legend: the scale, with the distribution of what is drawn - the hexagons in view, or every one held
    function layerProps() {
        var out = [];
        if (!lastFc) return out;
        lastFc.features.forEach(function (f) { if (shown(f)) out.push(f.properties); });
        return out;
    }
    // What the map is drawing: the coverage's cells while that group is chosen, else the layer's hexagons.
    function drawnProps() {
        if (isCoverage()) return lastCoverage ? lastCoverage.features.map(function (f) { return f.properties; }) : [];
        return layerProps();
    }
    function viewProps() {
        var all = drawnProps();
        if (!inView) return all;
        var b = map.getBounds();
        return all.filter(function (p) { return p.lat != null && b.contains([p.lat, p.lon]); });
    }
    var bins = 28, binOf = null;
    function hotBin(x) {
        var rects = document.querySelectorAll('#legendBody .hist rect');
        rects.forEach(function (r) { r.classList.remove('hot'); });
        if (x == null || binOf == null || !rects.length) return;
        var i = binOf(x);
        if (rects[i]) rects[i].classList.add('hot');
    }
    function legend() {
        var v = current(), props = viewProps(), title = $('legendTitle'), body = $('legendBody'), count = $('legendCount');
        binOf = null;
        title.innerHTML = icon(v.icon) + ' ' + esc(v.name) + (v.hint ? ' <span class="muted">' + esc(v.hint) + '</span>' : '') + ' <span class="muted">· ' + esc(GROUP_NAMES[state.group].split(' · ')[0].toLowerCase()) + '</span>';
        var vals = [], counts = {};
        props.forEach(function (p) { var x = valueOf(p, v); if (x != null) { vals.push(x); counts[x] = (counts[x] || 0) + 1; } });
        count.textContent = vals.length + ' of ' + props.length + (inView ? ' in view' : ' held');
        if (v.kind === 'from') {
            var html = '<div class="swatches">';
            Object.keys(FROM).forEach(function (k) { html += '<span class="swatch"><i style="background:' + FROM[k] + (k === 'none' ? ';opacity:.35' : '') + '"></i>' + esc(FROM_WORDS[k]) + ' <b>' + (counts[k] || 0) + '</b></span>'; });
            html += '<span class="swatch"><i style="border:2px solid ' + DROUGHT + ';background:transparent"></i>drought stepped <b>' + props.filter(function (p) { return p.hasDrought; }).length + '</b></span></div>';
            body.innerHTML = html;
            return;
        }
        if (v.kind === 'feed') {
            var fm = (lastFeed && lastFeed.meta) || {};
            body.innerHTML = '<div class="swatches"><span class="swatch"><i style="background:' + DROUGHT + '"></i>spun up, its own <b>' + (fm.own || 0) + '</b></span><span class="swatch"><i style="background:' + PALETTES.INPUTS.interpolated + '"></i>from its neighbours <b>' + (fm.interpolated || 0) + '</b></span>'
                + '<span class="swatch"><i style="border-top:2px solid ' + DROUGHT + ';background:transparent;height:0"></i>spoke to a feeding station</span><span class="swatch"><i style="border-top:2px dashed ' + PALETTES.INPUTS.interpolated + ';background:transparent;height:0"></i>to a hexagon it borrows from</span>'
                + (fm.unfed ? '<span class="swatch"><i style="background:#9ca3af;opacity:.5"></i>archive alone <b>' + fm.unfed + '</b></span>' : '') + '</div>'
                + '<div class="muted reach-note">' + (fm.rings != null ? 'at <b>' + fm.rings + ' rings</b>, <b>' + fm.kmPer100m + ' km</b> per 100 m' + (fm.rings === fm.savedRings && fm.kmPer100m === fm.savedKmPer100m ? ' · the rule in force' : ' · in force ' + fm.savedRings + ' rings, ' + fm.savedKmPer100m + ' km; press set to make this it') : 'asking…') + '</div>';
            return;
        }
        if (v.kind === 'count') {
            // None, one, two, three or more; and the reach the cells were counted at against the one in force.
            var by = [0, 0, 0, 0];
            vals.forEach(function (x) { by[Math.min(3, x)]++; });
            var cm = (lastCoverage && lastCoverage.meta) || {};
            var h3 = '<div class="swatches">';
            COUNT.forEach(function (c, i) { h3 += '<span class="swatch"><i style="background:' + c + (i === 0 ? ';opacity:.35' : '') + '"></i>' + esc(COUNT_WORDS[i]) + ' <b>' + by[i] + '</b></span>'; });
            h3 += '<span class="swatch"><i class="count-key many">2</i>blended</span>';
            h3 += '</div><div class="muted reach-note">' + (cm.reachKm != null ? 'at a reach of <b>' + cm.reachKm + ' km</b>' : '') + (cm.savedKm != null ? (cm.reachKm === cm.savedKm ? ' · the reach in force' : ' · in force <b>' + cm.savedKm + ' km</b>; press set to make this it') : '') + (cm.stations != null ? ' · ' + cm.stations + ' stations' : '') + '</div>';
            body.innerHTML = h3;
            return;
        }
        if (v.kind === 'life') {
            body.innerHTML = '<div class="ramp" style="background:linear-gradient(to right, rgba(245,158,11,.1), rgba(245,158,11,.6))"></div><div class="ramp-labels"><span>past its life</span><span>' + props.filter(function (p) { return p.hasForecast; }).length + ' held</span><span>just fetched</span></div>';
            return;
        }
        if (v.kind === 'category' || v.kind === 'rating') {
            var pal = v.kind === 'rating' ? PALETTES.RATING : PALETTES[v.palette], keys = Object.keys(pal), h2 = '<div class="swatches">';
            if (v.kind === 'rating') { counts = {}; props.forEach(function (p) { var r = p[v.ratingOf]; if (r) counts[r] = (counts[r] || 0) + 1; }); keys = v.id === 'fbi' ? ['No Rating', 'Moderate', 'High', 'Extreme', 'Catastrophic'] : ['LOW-MODERATE', 'HIGH', 'VERY HIGH', 'SEVERE', 'EXTREME', 'CATASTROPHIC']; }
            keys.forEach(function (k) { h2 += '<span class="swatch"><i style="background:' + pal[k] + '"></i>' + esc(k.replace('_', ' ').toLowerCase()) + ' <b>' + (counts[k] || 0) + '</b></span>'; });
            // The station glyph's key: the three arrows the trend toggle draws at every fresh station.
            if (state.group === 'shift') h2 += '<span class="swatch wt-key">' + trendGlyph({windMeanDeg: 335, windMeanKmh: 22, windDirectionDeg: 250, windSpeedKmh: 30, fc1hWindDeg: 225, fc1hWindKmh: 34}) + '<span class="mean">mean of the five before</span> · <span class="latest">latest</span> · <span class="fc">model an hour ahead</span>' + (togs.trend ? '' : ' <span class="muted">(Trend is off)</span>') + '</span>';
            body.innerHTML = h2 + '</div>';
            return;
        }
        // A number: its distribution as bars over the ramp, the range under it.
        var lo, hi, div = state.group === 'diff' || !!v.diverging;
        if (div) { lo = -v.lim; hi = v.lim; } else { lo = v.range[0]; hi = v.range[1]; }
        var hist = new Array(bins).fill(0), hot = -1;
        binOf = function (x) { return Math.max(0, Math.min(bins - 1, Math.floor((x - lo) / (hi - lo) * bins))); };
        vals.forEach(function (x) { hist[binOf(x)]++; });
        var max = Math.max.apply(null, hist.concat([1]));
        var svg = '<svg class="hist" viewBox="0 0 ' + bins * 10 + ' 30" preserveAspectRatio="none">';
        hist.forEach(function (n, i) { var h = n ? Math.max(2, n / max * 30) : 0; svg += '<rect x="' + (i * 10 + 1) + '" y="' + (30 - h) + '" width="8" height="' + h + '"' + (i === hot ? ' class="hot"' : '') + '><title>' + n + '</title></rect>'; });
        svg += '</svg>';
        var grad = 'linear-gradient(to right';
        for (var i = 0; i <= 10; i++) grad += ',' + ramp(v.reverse ? 1 - i / 10 : i / 10);
        grad += ')';
        var mean = vals.length ? vals.reduce(function (a, b) { return a + b; }, 0) / vals.length : null;
        body.innerHTML = svg + '<div class="ramp" style="background:' + grad + '"></div><div class="ramp-labels"><span>' + (div ? (state.group === 'diff' ? 'now −' : '−') + v.lim : lo) + '</span><span>' + (mean == null ? '' : 'mean ' + mean.toFixed(1)) + (v.unit ? ' ' + esc(v.unit) : '') + '</span><span>' + (div ? (state.group === 'diff' ? 'now +' : '+') + v.lim : hi) + '</span></div>';
    }

    // ---- the figures
    function tiles() {
        var m = (lastFc && lastFc.meta) || {}, nf = m.nowFrom || {}, props = layerProps();
        var ground = (nf.station || 0) + (nf.stations || 0) + (nf.neighbours || 0);
        var drifted = props.filter(function (p) { return p.drifted; }).length;
        var a = lastSources && lastSources.allowance;
        var t = [
            {v: m.hexagons || 0, k: 'hexagons held', t: 'created by asks; ' + (m.active || 0) + ' asked about'},
            {v: ground, k: 'now from the ground', cls: 'ground', t: (nf.station || 0) + ' station · ' + (nf.stations || 0) + ' blended · ' + (nf.neighbours || 0) + ' neighbours'},
            {v: m.withForecast || 0, k: 'forecasts held' + (m.lifeMinutes ? ' · ' + m.lifeMinutes + ' min life' : ''), cls: 'forecast', t: 'each fetched on an ask, kept for its life'},
            {v: nf.model || 0, k: 'model standing in', cls: 'forecast', t: 'hexagons whose "now" is the series read at this moment'},
            {v: drifted, k: 'thrown out', cls: drifted ? 'bad' : '', t: 'forecasts the stations disagreed with'},
            {v: m.withWindShift || 0, k: 'wind changes', cls: m.withWindShift ? 'shift' : '', t: 'hexagons whose station measured a change of wind in the last hour'},
            {v: lastStations && lastStations.meta ? lastStations.meta.fresh : '—', k: 'stations fresh', cls: 'ground', t: 'stations with an observation under seventy minutes old'},
            {v: a ? Math.round((a.dayFraction || 0) * 100) : '—', sub: a ? '%' : '', k: 'allowance today', t: 'Open-Meteo units used of the day\'s'}
        ];
        t.push({v: m.withDrought || 0, k: 'droughts held', cls: 'drought', t: 'hexagons with a deficit stepped, their own or their neighbours\''});
        $('tiles').innerHTML = t.map(function (x) { return '<div class="tile ' + (x.cls || '') + '" title="' + esc(x.t || '') + '"><div class="v">' + esc(x.v) + (x.sub ? '<small>' + esc(x.sub) + '</small>' : '') + '</div><div class="k">' + esc(x.k) + '</div></div>'; }).join('');
        $('figuresHint').textContent = (m.hexagons || 0) + ' held · ' + ground + ' from the ground · ' + (m.withForecast || 0) + ' forecasts';
        if (lastSources) {
            var last = lastSources.reads && lastSources.reads[0];
            $('sourcesHint').textContent = last ? '· last read ' + ago(last.at) : '';
        }
    }

    // ---- the layer
    var loadTimer = null, loading = false, wanted = null;
    function atIso() { return state.hours === 0 ? null : new Date(Date.now() + state.hours * 3600000).toISOString(); }
    function load() {
        var at = atIso();
        var mode = state.hours === 0 ? 'now' : state.hours > 0 ? 'ahead' : 'history', modeMoved = mode !== state.mode;
        state.mode = mode;
        $('timeline').classList.toggle('ahead', state.mode === 'ahead');
        reconcile();
        // The trend glyphs are a "now" thing: they go when the timeline leaves now and come back with it.
        if (modeMoved) stations();
        if (loading) { wanted = at; return; }
        loading = true;
        var url = '/console/map/layer.geojson' + (at ? '?at=' + encodeURIComponent(at) : '');
        fetch(url, {headers: etag && !at ? {'If-None-Match': etag} : {}}).then(function (r) {
            if (r.status === 304) return null;
            if (!at) etag = r.headers.get('ETag');
            return r.json();
        }).then(function (fc) {
            loading = false;
            if (fc) { lastFc = fc; draw(); legend(); tiles(); }
            lastLoadAt = new Date().toISOString();
            if (wanted !== null && wanted !== at) { wanted = null; load(); } else wanted = null;
        }).catch(function (e) { loading = false; note('layer failed: ' + e); });
    }
    function draw() {
        hexLayer.clearLayers();
        pointLayer.clearLayers();
        coverageLayer.clearLayers();
        markLayer.clearLayers();
        if (isCoverage()) { if (lastCoverage) coverageLayer.addData(lastCoverage); glyphs(); return; }
        if (!lastFc) return;
        if (pointsMode()) points(); else hexLayer.addData(lastFc);
        marks();
        glyphs();
    }
    function pointsMode() { return togs.points || map.getZoom() <= 5; }
    function restyle() { if (isCoverage()) coverageLayer.setStyle(coverageStyle); else if (pointsMode()) { pointLayer.clearLayers(); points(); } else { hexLayer.setStyle(styleOf); marks(); } }

    // ---- the drought's feed (W-22): every spun-up hexagon as a dot at its centre with a spoke to each station feeding
    // it under the sliders' rule; one made from its neighbours with dashed spokes to them. Set writes the rule and every
    // drought is made again under it; a click on a hexagon spins its drought up on its own.
    var feedSeq = 0, feedTimer = null;
    function droughtFeed() {
        var seq = ++feedSeq;
        fetch('/console/map/drought-feed.geojson?rings=' + $('droughtRings').value + '&kmPer100m=' + $('droughtKm').value).then(function (r) { return r.json(); }).then(function (fc) {
            if (seq !== feedSeq) return;
            lastFeed = fc;
            if (isFeed()) { drawFeed(); legend(); }
        }).catch(function (e) { note('drought feed failed: ' + e); });
    }
    function drawFeed() {
        feedLayer.clearLayers();
        if (!lastFeed || !isFeed()) return;
        var z = map.getZoom();
        lastFeed.features.forEach(function (f) {
            var p = f.properties;
            if (f.geometry.type === 'LineString') {
                var c = f.geometry.coordinates, interp = p.spoke === 'hexagon';
                L.polyline([[c[0][1], c[0][0]], [c[1][1], c[1][0]]], {renderer: canvas, color: interp ? PALETTES.INPUTS.interpolated : DROUGHT, weight: interp ? 1.2 : 1.6, opacity: .85, dashArray: interp ? '5 4' : null, interactive: false}).addTo(feedLayer);
                return;
            }
            var r = Math.max(3, Math.min(7, z * .8)), col = p.interpolated ? PALETTES.INPUTS.interpolated : DROUGHT;
            L.circleMarker([p.lat, p.lon], {renderer: canvas, radius: r, color: '#fff', weight: 1, opacity: .9, fillColor: col, fillOpacity: .95})
                .bindTooltip(function () {
                    return '<b>' + esc(p.id) + '</b> <span class="muted">' + (p.interpolated ? 'from its neighbours' : 'its own · ' + esc(p.from)) + ' · KBDI ' + esc(p.kbdiMm) + ' mm · DF ' + esc(p.droughtFactor) + ' · ' + esc(p.days) + ' days to ' + esc(p.computedFor) + (p.elevationM != null ? ' · ' + Math.round(p.elevationM) + ' m' : '') + '</span>'
                        + (p.stations && p.stations.length ? '<br><span class="feed-line">' + p.stations.map(esc).join('<br>') + '</span>' : p.interpolated ? '<br><span class="muted">made from ' + p.fromHexagons.map(esc).join(', ') + '</span>' : '<br><span class="muted">no station within the rule: the archive alone feeds it</span>');
                }, {sticky: true, className: 'hx-tip'})
                .on('click', function (e) { L.DomEvent.stopPropagation(e); detail(p.id); })
                .addTo(feedLayer);
        });
    }
    function droughtMoved(immediate) {
        $('droughtRingsValue').textContent = $('droughtRings').value;
        $('droughtKmValue').textContent = $('droughtKm').value;
        if (!isFeed()) { choose('drought', 'feed'); return; }
        clearTimeout(feedTimer);
        feedTimer = setTimeout(droughtFeed, immediate ? 0 : 150);
    }
    function droughtSaved(rings, km, by, since) {
        $('droughtSaved').innerHTML = 'set to <b>' + esc(rings) + ' rings</b>, <b>' + esc(km) + ' km</b>' + (by ? ' <span class="muted">by ' + esc(by) + (since ? ', ' + when(since) : '') + '</span>' : ' <span class="muted">(the defaults)</span>');
    }
    function setDroughtRule() {
        if (!window.gullyCsrf) return;
        var headers = window.gullyCsrf();
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        $('droughtSet').disabled = true;
        note('setting the rule and remaking every drought from the record…');
        fetch('/console/map/drought/rule', {method: 'POST', headers: headers, body: 'rings=' + $('droughtRings').value + '&kmPer100m=' + $('droughtKm').value}).then(function (r) { return r.json(); }).then(function (o) {
            $('droughtSet').disabled = false;
            droughtSaved(o.rings, o.kmPer100m, o.by, o.since);
            note('rule set: ' + o.rings + ' rings, ' + o.kmPer100m + ' km per 100 m · ' + o.remade + ' droughts remade');
            etag = null; load(); droughtFeed();
        }).catch(function (e) { $('droughtSet').disabled = false; note('set failed: ' + e); });
    }
    function spinDrought(lat, lon) {
        var headers = window.gullyCsrf();
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        note('spinning the drought up: a year of the archive…');
        fetch('/console/map/drought/spin', {method: 'POST', headers: headers, body: 'lat=' + lat + '&lon=' + lon}).then(function (r) { return r.json(); }).then(function (o) {
            note(o.spunUp ? o.id + ': its own drought, KBDI ' + o.kbdiMm + ' mm, DF ' + o.droughtFactor + ' over ' + o.days + ' days' : o.id + ': the year could not be fed - out of allowance, or the upstream is off; it is tried again on a later ask');
            etag = null; load(); droughtFeed(); sourcesPanel();
        }).catch(function (e) { note('spin-up failed: ' + e); });
    }

    // ---- what counts as a wind change (W-23): the sliders preview on the stations' rings, from the widest swing and
    // biggest speed change each has measured; set writes the thresholds, and the hexagons' outlines follow.
    function windThresholds() { return {swing: Number($('windSwing').value), speed: Number($('windSpeed').value)}; }
    function windMoved() {
        $('windSwingValue').textContent = $('windSwing').value;
        $('windSpeedValue').textContent = $('windSpeed').value;
        if (state.group !== 'shift') { choose('shift', 'shift'); return; }
        stations();
    }
    function windSaved(swing, speed, by, since) {
        $('windSaved').innerHTML = 'set to <b>' + esc(swing) + '°</b> or <b>' + esc(speed) + ' km/h</b>' + (by ? ' <span class="muted">by ' + esc(by) + (since ? ', ' + when(since) : '') + '</span>' : ' <span class="muted">(the defaults)</span>');
    }
    function setWindChange() {
        if (!window.gullyCsrf) return;
        var headers = window.gullyCsrf();
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        $('windSet').disabled = true;
        fetch('/console/map/wind-change', {method: 'POST', headers: headers, body: 'swingDeg=' + $('windSwing').value + '&speedKmh=' + $('windSpeed').value}).then(function (r) { return r.json(); }).then(function (o) {
            $('windSet').disabled = false;
            windSaved(o.swingDeg, o.speedKmh, o.by, o.since);
            note('a wind change counts from ' + o.swingDeg + '° or ' + o.speedKmh + ' km/h · ' + o.windShifts + ' stations have one');
            etag = null; load(); lastStations = null; stations();
        }).catch(function (e) { $('windSet').disabled = false; note('set failed: ' + e); });
    }
    // Whether a station lights up under the sliders, and at what grade, from its widest swing and biggest speed change.
    function previewShift(p) {
        if (state.group !== 'shift' || state.mode !== 'now') return p.windShift;
        var t = windThresholds(), swing = p.windMaxSwingDeg, delta = p.windMaxDeltaKmh == null ? null : Math.abs(p.windMaxDeltaKmh);
        var sw = swing != null && swing >= t.swing ? (swing >= 90 ? 'sharp' : swing >= 60 ? 'marked' : 'slight') : null;
        var sp = delta != null && delta >= t.speed ? (delta >= 30 ? 'sharp' : delta >= 20 ? 'marked' : 'slight') : null;
        var rank = {slight: 1, marked: 2, sharp: 3};
        return !sw && !sp ? null : (rank[sw] || 0) >= (rank[sp] || 0) ? sw : sp;
    }

    // ---- the coverage (W-18): the cells every station would count for at the reach on the slider, asked
    // as it moves; only the latest answer is drawn. Set makes the slider's reach the one in force.
    var coverageSeq = 0, coverageTimer = null;
    function reachOnSlider() { return Number($('reachKm').value); }
    function coverage() {
        var km = reachOnSlider(), seq = ++coverageSeq;
        fetch('/console/map/coverage.geojson?reachKm=' + km).then(function (r) { return r.json(); }).then(function (fc) {
            if (seq !== coverageSeq) return;
            lastCoverage = fc;
            if (isCoverage()) { draw(); legend(); }
        }).catch(function (e) { note('coverage failed: ' + e); });
    }
    function reachMoved(immediate) {
        $('reachValue').textContent = reachOnSlider();
        if (!isCoverage()) { choose('coverage', 'stations'); return; }
        clearTimeout(coverageTimer);
        coverageTimer = setTimeout(coverage, immediate ? 0 : 150);
    }
    function reachSaved(km, by, since) {
        $('reachSaved').innerHTML = 'set to <b>' + esc(km) + ' km</b>' + (by ? ' <span class="muted">by ' + esc(by) + (since ? ', ' + when(since) : '') + '</span>' : ' <span class="muted">(the default)</span>');
    }
    function setReach() {
        if (!window.gullyCsrf) return;
        var km = reachOnSlider(), headers = window.gullyCsrf();
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        $('reachSet').disabled = true;
        fetch('/console/map/reach', {method: 'POST', headers: headers, body: 'km=' + km}).then(function (r) { return r.json(); }).then(function (o) {
            $('reachSet').disabled = false;
            reachSaved(o.reachKm, o.by, o.since);
            note('reach set to ' + o.reachKm + ' km · ' + o.hexagonsChanged + ' hexagons re-linked or created · ' + o.hexagons + ' held');
            etag = null; load(); coverage();
        }).catch(function (e) { $('reachSet').disabled = false; note('set failed: ' + e); });
    }
    function onCoverage(f, layer) {
        var p = f.properties;
        layer.bindTooltip(function () {
            var s = '<b>' + esc(p.id) + '</b> <span class="muted">' + (p.held ? 'held' : 'not held yet') + '</span><br>' + p.stations + ' station' + (p.stations === 1 ? '' : 's') + ' within reach' + (p.stations ? ', ' + p.reporting + ' reporting' : '') + (p.stations > 1 ? ' · <b>blended</b>' : p.stations === 1 ? ' · its "now"' : '');
            if (p.names && p.names.length) s += '<br><span class="muted">' + p.names.map(esc).join(' · ') + '</span>';
            return s;
        }, {sticky: true, className: 'hx-tip'});
        layer.on('click', function (e) { L.DomEvent.stopPropagation(e); if (p.held) detail(p.id); else note(p.id + ' is not held: nothing has asked about it, and no station reaches it at the reach in force'); });
        layer.on('mouseover', function () { hotBin(valueOf(p)); });
        layer.on('mouseout', function () { hotBin(null); });
    }
    // The count in every cell from zoom 8 (a digit needs the room): bold in a red ring past one, where "now" is a blend.
    function countGlyphs() {
        var z = map.getZoom();
        if (z < 8 || !lastCoverage) return;
        var b = map.getBounds().pad(.2);
        lastCoverage.features.forEach(function (f) {
            var p = f.properties;
            if (p.lat == null || !b.contains([p.lat, p.lon])) return;
            var n = valueOf(p);
            L.marker([p.lat, p.lon], {icon: L.divIcon({className: 'hx-glyph', html: '<span class="hx-count' + (n > 1 ? ' many' : n === 0 ? ' none' : '') + '">' + n + '</span>', iconSize: [24, 24], iconAnchor: [12, 12]}), interactive: false, keyboard: false}).addTo(glyphLayer);
        });
    }
    // Hexagons as points: one dot at each centre, the fill colour, sized by zoom. What a continent looks like.
    function points() {
        var z = map.getZoom(), r = Math.max(2.5, Math.min(9, z * 1.1));
        lastFc.features.forEach(function (f) {
            if (!shown(f)) return;
            var p = f.properties, s = styleOf(f);
            L.circleMarker([p.lat, p.lon], {renderer: canvas, radius: r, color: s.color, weight: p.hasDrought ? 1.5 : .6, opacity: s.opacity, fillColor: s.fillColor, fillOpacity: Math.min(.95, s.fillOpacity * 1.6)})
                .bindTooltip(function () { return tip(p); }, {sticky: true, className: 'hx-tip'})
                .on('click', function (e) { L.DomEvent.stopPropagation(e); detail(p.id); })
                .on('mouseover', function () { hotBin(valueOf(p)); })
                .on('mouseout', function () { hotBin(null); })
                .addTo(pointLayer);
        });
    }
    function onHexagon(f, layer) {
        var p = f.properties;
        layer.bindTooltip(function () { return tip(p); }, {sticky: true, className: 'hx-tip'});
        layer.on('click', function (e) { L.DomEvent.stopPropagation(e); detail(p.id); });
        layer.on('mouseover', function () { hotBin(valueOf(p)); });
        layer.on('mouseout', function () { hotBin(null); });
    }
    function line(t, rh, w, dir, gust) {
        if (t == null && rh == null && w == null) return null;
        return esc(fmt(t, 1)) + ' °C · ' + esc(fmt(rh)) + ' % · ' + esc(fmt(w == null ? null : Math.round(w))) + ' km/h' + (dir != null ? ' from ' + dir + '°' : '') + (gust != null ? ' gust ' + Math.round(gust) : '');
    }
    function tip(p) {
        var s = '<b>' + esc(p.id) + '</b> <span class="muted">' + esc(p.kind) + (p.hasDrought ? ' · drought stepped' : '') + (p.fireBanDistrict ? ' · ' + esc(p.fireBanDistrict) : '') + '</span>';
        if (p.ahead) s += '<br><span class="muted">+' + p.aheadHours + ' h · ' + when(p.fcAt) + '</span>';
        var now = line(p.nowTemperatureC, p.nowHumidityPct, p.nowWindKmh, p.nowWindDeg, p.nowGustKmh);
        if (now) s += '<br><span class="row-now"><b>now</b></span> ' + now + ' <span class="muted">' + esc(FROM_WORDS[p.from] || p.from) + (p.nowStations > 1 ? ' (' + p.nowStations + (p.nowRing === 0 ? ' in it' : p.nowRing != null ? ', ring ' + p.nowRing : '') + ')' : '') + (p.stationId && p.from === 'station' ? ' ' + esc(p.stationId) : '') + ' · ' + clock(p.nowAt) + '</span>';
        else if (p.ahead) s += '';
        else if (p.hasStation) s += '<br><span class="row-now"><b>now</b></span> <span class="muted">station ' + esc(p.stationId) + ' in it, no fresh values: the file for its state has not been asked for lately</span>';
        else s += '<br><span class="row-now"><b>now</b></span> <span class="muted">nothing from the ground' + (p.nearestStationId ? ' · nearest station ' + esc(p.nearestStationId) : '') + '</span>';
        if (p.stationsInReach != null) s += '<br><span class="muted">' + p.stationsInReach + ' station' + (p.stationsInReach === 1 ? '' : 's') + ' within reach' + (p.stationsInReach ? ', ' + p.stationsReporting + ' reporting' : '') + '</span>';
        var fc = line(p.fcTemperatureC, p.fcHumidityPct, p.fcWindKmh, p.fcWindDeg, p.fcGustKmh);
        if (fc) s += '<br><span class="row-fc"><b>forecast</b></span> ' + fc + ' <span class="muted">' + esc(p.upstream || '') + ' · fetched ' + clock(p.fcFetchedAt) + (p.ahead ? '' : p.stale ? ' · <b>past its life</b>' : p.fcMinutesLeft != null ? ' · ' + p.fcMinutesLeft + ' min left' : '') + '</span>';
        else if (p.hasForecast && !p.ahead) s += '<br><span class="row-fc"><b>forecast</b></span> <span class="muted">held, nothing for this hour</span>';
        if (p.diffTemperatureC != null || p.diffHumidityPct != null || p.diffWindKmh != null) s += '<br>Δ now − forecast: ' + esc(fmt(p.diffTemperatureC, 1)) + ' °C, ' + esc(fmt(p.diffHumidityPct)) + ' pts, ' + esc(fmt(p.diffWindKmh, 1)) + ' km/h';
        if (p.windShift) s += '<br><span class="shift-line ' + esc(p.windShift) + '"><b>wind change</b> ' + esc(p.windShiftText || p.windShift) + '</span>';
        if (p.drift != null) s += '<br>drift ' + esc(p.drift) + (p.drifted ? ' <b>thrown out</b>' : '') + (p.driftWorst ? ' (' + esc(p.driftWorst) + ')' : '') + ' <span class="muted">at ' + clock(p.driftAt) + (p.drift24h != null ? ' · 24 h mean ' + esc(p.drift24h) : '') + '</span>';
        if (p.ffdi != null) s += '<br>FFDI ' + esc(p.ffdi) + ' ' + esc(p.ffdiRating || '') + (p.fbi != null ? ' · FBI ' + esc(p.fbi) + ' ' + esc(p.afdrsRating || '') : '') + (p.droughtFactor != null ? ' · DF ' + esc(p.droughtFactor) : '') + (p.officialRating ? ' · CFS ' + esc(p.officialRating) : '');
        var ground = [];
        if (p.elevationM != null) ground.push(Math.round(p.elevationM) + ' m' + (p.elevationFrom ? ' (' + esc(p.elevationFrom) + ')' : ''));
        if (p.landUse) ground.push(Object.keys(p.landUse).map(function (k) { return k.replace('_', ' ') + ' ' + p.landUse[k] + '%'; }).join(', ') + (p.leads ? ' → ' + p.leads : ''));
        if (ground.length) s += '<br><span class="muted">' + ground.join(' · ') + '</span>';
        var v = current(), x = valueOf(p, v);
        if (state.group !== 'now' && state.group !== 'fc' && state.group !== 'diff' && x != null && v.id !== 'ffdi' && v.id !== 'fbi' && v.id !== 'elevationM') s += '<br>' + esc(v.name.toLowerCase()) + ': ' + esc(fmt(x, 1)) + (v.unit ? ' ' + esc(v.unit) : '');
        s += '<br><span class="muted">' + (p.lastAskedAt ? 'last asked ' + ago(p.lastAskedAt) + ' · ' + p.asks + ' asks this run' : 'never asked') + '</span>';
        return s;
    }

    // ---- the weather on each hexagon: an arrow the way the wind blows, its length by the speed (from zoom 7);
    // the values as a label (from zoom 9). Black for the ground, amber for the model.
    function arrow(fromDeg, speed, cls) {
        if (speed < 1) return '<svg class="hx-arrow ' + cls + '" width="24" height="24" viewBox="-12 -12 24 24"><circle r="2"/></svg>';
        var len = 7 + Math.min(1, speed / 60) * 13, to = (fromDeg + 180) % 360, h = len / 2;
        return '<svg class="hx-arrow ' + cls + '" width="24" height="24" viewBox="-12 -12 24 24" style="transform:rotate(' + to + 'deg)">'
            + '<line x1="0" y1="' + h + '" x2="0" y2="' + (-h) + '"/><polyline points="-3.5,' + (-h + 4) + ' 0,' + (-h) + ' 3.5,' + (-h + 4) + '"/></svg>';
    }
    // ---- the wind trend at a station: three arrows from one point, each the way the wind blows and as
    // long as it is strong - where it has mostly been (the mean of the readings before the latest, grey),
    // where it is (the latest, black, or the grade's colour when the station has measured a change), and
    // where the model says it is going (an hour ahead, amber, dashed). A steady wind is one arrow; a
    // change is a fan. A calm is a dot.
    function trendArrow(deg, kmh, cls) {
        if (deg == null || kmh == null) return '';
        if (kmh < 1) return '<circle class="' + cls + '" r="2.2"/>';
        var len = 9 + Math.min(1, kmh / 60) * 17, to = (deg + 180) % 360;
        return '<g class="' + cls + '" transform="rotate(' + to + ')"><line x1="0" y1="0" x2="0" y2="' + (-len) + '"/><polyline points="-3.6,' + (-len + 4.5) + ' 0,' + (-len) + ' 3.6,' + (-len + 4.5) + '"/></g>';
    }
    function trendGlyph(p) {
        var latest = trendArrow(p.windDirectionDeg, p.windSpeedKmh, 'latest' + (p.windShift ? ' ' + p.windShift : ''));
        if (!latest) return '';
        // Mean under, latest over it, the model on top: where they agree the amber dashes ride the black arrow.
        return '<svg class="wt" width="56" height="56" viewBox="-28 -28 56 56">'
            + trendArrow(p.windMeanDeg, p.windMeanKmh, 'mean') + latest + trendArrow(p.fc1hWindDeg, p.fc1hWindKmh, 'fc') + '</svg>';
    }
    // The three in words, for the tooltip and the drawer: "mean of 5 (50 min) 355° 25 → now 225° 30 g 45 → +1 h 230° 28".
    function dirWord(deg) { return deg == null ? '—' : ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'][Math.round(deg / 22.5) % 16]; }
    function windWords(deg, kmh, gust) { return deg == null && kmh == null ? '—' : (deg != null ? dirWord(deg) + ' ' + deg + '°' : '—') + ' ' + (kmh != null ? Math.round(kmh) : '—') + (gust != null ? ' <span class="muted">g ' + Math.round(gust) + '</span>' : ''); }
    function trendLines(p) {
        if (p.windMeanOver == null && p.fc1hWindKmh == null && p.fcChangeAt == null) return '';
        var s = '<div class="trend">';
        if (p.windMeanOver != null) s += '<span class="mean">mean of ' + p.windMeanOver + ' <span class="muted">(' + p.windMeanMinutes + ' min)</span> ' + windWords(p.windMeanDeg, p.windMeanKmh, p.windMeanGustKmh) + '</span>';
        s += '<span class="latest' + (p.windShift ? ' ' + esc(p.windShift) : '') + '">now ' + windWords(p.windDirectionDeg, p.windSpeedKmh, p.windGustKmh)
            + (p.windTrendSwingDeg != null || p.windTrendDeltaKmh != null ? ' <span class="muted">' + (p.windTrendSwingDeg != null ? 'swung ' + p.windTrendSwingDeg + '°' : '') + (p.windTrendDeltaKmh != null ? (p.windTrendSwingDeg != null ? ', ' : '') + (p.windTrendDeltaKmh > 0 ? '+' : '') + Math.round(p.windTrendDeltaKmh) + ' km/h' : '') + ' on the mean</span>' : '') + '</span>';
        if (p.fc1hWindKmh != null || p.fc3hWindKmh != null || p.fc6hWindKmh != null) s += '<span class="fc">model +1 h ' + windWords(p.fc1hWindDeg, p.fc1hWindKmh, p.fc1hGustKmh) + ' <span class="muted">· +3 h</span> ' + windWords(p.fc3hWindDeg, p.fc3hWindKmh, null) + ' <span class="muted">· +6 h</span> ' + windWords(p.fc6hWindDeg, p.fc6hWindKmh, null) + '</span>';
        if (p.fcChangeAt) s += '<span class="fc change"><b>change expected ' + clock(p.fcChangeAt) + '</b> <span class="muted">(' + in_(p.fcChangeAt) + ')</span> ' + dirWord(p.fcChangeFromDeg) + ' ' + p.fcChangeFromDeg + '° → ' + dirWord(p.fcChangeToDeg) + ' ' + p.fcChangeToDeg + '° at ' + Math.round(p.fcChangeKmh) + ' km/h' + (p.fcChangeGustKmh != null ? ' <span class="muted">g ' + Math.round(p.fcChangeGustKmh) + '</span>' : '') + '</span>';
        return s + '</div>';
    }

    // The station point the map holds, by id, for the drawer.
    function stationProps(id) {
        if (!id || !lastStations) return null;
        for (var i = 0; i < lastStations.features.length; i++) if (lastStations.features[i].properties.id === id) return lastStations.features[i].properties;
        return null;
    }
    function trendRow(label, cls, deg, kmh, gust) {
        return '<tr class="' + cls + '"><td>' + label + '</td><td class="dir">' + (deg != null ? '<span class="arrow" style="transform:rotate(' + ((deg + 180) % 360) + 'deg)">↑</span> ' + dirWord(deg) + ' ' + deg + '°' : '—') + '</td><td class="num">' + (kmh != null ? Math.round(kmh) : '—') + '</td><td class="num">' + (gust != null ? Math.round(gust) : '—') + '</td></tr>';
    }

    function glyphs() {
        glyphLayer.clearLayers();
        if (isCoverage()) { countGlyphs(); return; }
        var z = map.getZoom();
        if ((!togs.wind && !togs.labels) || z < 7 || !lastFc) return;
        var fc = state.tab === 'forecast' || state.mode === 'ahead', cls = fc ? 'fc' : 'now';
        lastFc.features.forEach(function (f) {
            if (!shown(f)) return;
            var p = f.properties;
            if (p.lat == null) return;
            var t = fc ? p.fcTemperatureC : p.nowTemperatureC, rh = fc ? p.fcHumidityPct : p.nowHumidityPct, w = fc ? p.fcWindKmh : p.nowWindKmh, dir = fc ? p.fcWindDeg : p.nowWindDeg;
            if (!fc && p.from === 'model') return;
            var html = '';
            if (togs.wind && dir != null && w != null) html += arrow(dir, w, cls);
            if (togs.labels && z >= 9 && (t != null || rh != null)) html += '<span class="hx-label ' + cls + '">' + (t != null ? Math.round(t) + '°' : '') + (rh != null ? ' ' + rh + '%' : '') + (w != null ? ' ' + Math.round(w) : '') + '</span>';
            if (!html) return;
            L.marker([p.lat, p.lon], {icon: L.divIcon({className: 'hx-glyph', html: html, iconSize: [24, 24], iconAnchor: [12, 12]}), interactive: false, keyboard: false}).addTo(glyphLayer);
        });
    }

    // ---- the tessellation
    function grid() {
        if (!togs.grid) { map.removeLayer(gridLayer); return; }
        var b = map.getBounds();
        fetch('/console/map/grid.geojson?south=' + b.getSouth() + '&west=' + b.getWest() + '&north=' + b.getNorth() + '&east=' + b.getEast())
            .then(function (r) { return r.json(); }).then(function (fc) {
                gridLayer.clearLayers();
                if (fc.meta && fc.meta.tooMany) { note('zoom in to draw the tessellation'); return; }
                gridLayer.addData(fc).addTo(map);
                gridLayer.bringToBack();
            });
    }

    // ---- the stations: a point cloud. Filled where the observation is fresh, hollow where its state's
    // file has not been asked for lately; coloured by the variable shown where the station measures it.
    var STATION_FIELD = {temperatureC: 'temperatureC', humidityPct: 'humidityPct', windKmh: 'windSpeedKmh', gustKmh: 'windGustKmh', rainMm: 'rainSince9amMm'};
    function stations() {
        stationLayer.clearLayers();
        if (!togs.stations) return;
        if (!lastStations) {
            fetch('/console/map/stations.geojson').then(function (r) { return r.json(); }).then(function (fc) { lastStations = fc; stations(); tiles(); });
            return;
        }
        var v = current(), field = (state.group === 'now' || state.group === 'fc') ? STATION_FIELD[v.id] : null, z = map.getZoom();
        var byShift = state.group === 'shift';
        var r = Math.max(2, Math.min(4.5, z * .55));
        lastStations.features.forEach(function (f) {
            var p = f.properties, ll = [f.geometry.coordinates[1], f.geometry.coordinates[0]];
            var c = FROM.station;
            if (field && p.fresh && p[field] != null) { var t = (p[field] - v.range[0]) / (v.range[1] - v.range[0]); c = ramp(v.reverse ? 1 - t : t); }
            var shiftNow = previewShift(p);
            if (byShift) {
                var sv = v.id === 'shift' ? shiftNow : v.id === 'shiftDeg' ? (shiftNow ? p.windMaxSwingDeg : null) : (shiftNow ? p.windMaxDeltaKmh : null);
                c = sv == null ? '#6b7280' : v.id === 'shift' ? SHIFT[sv] : v.diverging ? ramp(.5 + Math.max(-1, Math.min(1, sv / v.lim)) / 2) : ramp(sv / v.range[1]);
            }
            if (shiftNow && p.fresh) {
                var px = Math.round(r * 7);
                L.marker(ll, {icon: L.divIcon({className: 'st-shift ' + shiftNow, html: '<i></i>', iconSize: [px, px], iconAnchor: [px / 2, px / 2]}), interactive: false, keyboard: false}).addTo(stationLayer);
            }
            // The trend: the three arrows at the station, on the map as it is now (a snapshot has no "last five"), from zoom 8.
            if (togs.trend && p.fresh && state.mode === 'now' && z >= 8) {
                var g = trendGlyph(p);
                if (g) L.marker(ll, {icon: L.divIcon({className: 'wt-glyph', html: g, iconSize: [56, 56], iconAnchor: [28, 28]}), interactive: false, keyboard: false}).addTo(stationLayer);
            }
            if (p.fresh) L.circleMarker(ll, {renderer: canvas, radius: r * 2.2, color: c, weight: 0, fillColor: c, fillOpacity: .18, interactive: false}).addTo(stationLayer);
            L.circleMarker(ll, {renderer: canvas, radius: r, color: c, weight: p.fresh ? 1 : 1.2, opacity: p.fresh ? 1 : .7, fillColor: c, fillOpacity: p.fresh ? .95 : 0})
                .bindTooltip(function () {
                    return '<b>' + esc(p.name) + '</b> <span class="muted">' + esc(p.id) + ' · ' + esc((p.state || '').toUpperCase()) + (p.heightM != null ? ' · ' + p.heightM + ' m' : '') + '</span><br>'
                        + (p.fresh ? esc(fmt(p.temperatureC, 1)) + ' °C · ' + esc(fmt(p.humidityPct)) + ' % · ' + esc(fmt(p.windSpeedKmh)) + ' km/h' + (p.windDirectionDeg != null ? ' from ' + p.windDirectionDeg + '°' : '') + (p.windGustKmh != null ? ' gust ' + p.windGustKmh : '') + (p.rainSince9amMm != null ? ' · ' + p.rainSince9amMm + ' mm since 9 am' : '') + '<br><span class="muted">' + when(p.at) + '</span>'
                            + (p.windShift ? '<br><span class="shift-line ' + esc(p.windShift) + '"><b>wind change</b> ' + esc(p.windShiftText || p.windShift) + '</span>' : '')
                            + trendLines(p)
                            : '<span class="muted">' + (p.at ? 'last read ' + ago(p.at) + ': ' : '') + 'the file for its state has not been asked for lately</span>');
                }, {sticky: true, className: 'hx-tip'})
                .on('click', function (e) { L.DomEvent.stopPropagation(e); detail(p.hexagon); })
                .addTo(stationLayer);
        });
    }

    // ---- the sources drawer: read on request only, and by whom
    function sourcesPanel(open) {
        var el = $('sources');
        fetch('/console/map/sources.json').then(function (r) { return r.json(); }).then(function (s) {
            lastSources = s;
            tiles();
            if (open === false || (open == null && el.classList.contains('hidden'))) return;
            var html = '<div class="drawer-head"><h3>Sources <span class="muted">read on request, never on a timer</span></h3><button class="icon-btn" id="sourcesClose" type="button">' + icon('close') + '</button></div>';
            if (!s.sources.length) html += '<p class="muted mb-1">nothing has been asked for since the service started: no source has been read.</p>';
            s.sources.forEach(function (x) {
                var cadMs = x.cadenceMinutes * 60000, since = x.checkedAt ? Date.now() - Date.parse(x.checkedAt) : cadMs, share = Math.max(0, Math.min(1, since / cadMs)), due = share >= 1;
                var cad = x.cadenceMinutes >= 1440 ? Math.round(x.cadenceMinutes / 1440) + ' d' : x.cadenceMinutes >= 60 ? Math.round(x.cadenceMinutes / 60) + ' h' : x.cadenceMinutes + ' min';
                html += '<div class="src"><div class="name"><b>' + esc(x.name) + '</b><span class="muted">every ' + cad + (x.failure ? ' · <span class="text-warning">' + esc(x.failure) + '</span>' : '') + '</span></div>'
                    + '<div class="fresh" title="' + (due ? 'due: the next ask reads it' : 'next check ' + esc(in_(x.dueAt))) + '"><i class="' + (due ? 'due' : '') + '" style="width:' + Math.round(share * 100) + '%"></i></div>'
                    + '<span class="muted">checked</span> ' + esc(ago(x.checkedAt)) + ' <span class="muted">· read</span> ' + esc(ago(x.readAt))
                    + (x.items != null ? ' <span class="muted">(' + x.items + (x.id.indexOf('bureau-') === 0 ? ' stations, the whole file' : x.id.indexOf('warnings-') === 0 ? ' warnings held' : x.id === 'cfs-ratings' ? ' districts' : ' shapes') + ')</span>' : '')
                    + (x.triggeredBy ? ' <span class="muted">· by an ask for</span> ' + esc(x.triggeredBy) + ' <span class="muted">' + esc(ago(x.triggeredAt)) + '</span>' : '')
                    + ' <span class="muted">· next</span> ' + esc(in_(x.dueAt)) + '</div>';
            });
            html += '<div class="src"><div class="name"><b>Open-Meteo</b><span class="muted">' + Math.round((s.allowance.dayFraction || 0) * 100) + ' % of today\'s allowance · a forecast lives ' + s.allowance.lifeMinutes + ' min</span></div><span class="muted">forecasts, elevation, drought archive, rivers: fetched when an ask needs them</span></div>';
            html += '<h2>Last reads <span class="muted">newest first · each against the hexagon that asked</span></h2><div class="reads"><table><tbody>';
            s.reads.forEach(function (r) { html += '<tr><td class="t">' + esc(clock(r.at)) + '</td><td class="s">' + esc(r.source) + '</td><td>' + (r.ok ? '' : '<span class="text-warning">failed · </span>') + esc(r.detail || '') + '</td><td class="ms">' + (r.ms != null ? r.ms + ' ms' : '') + '</td></tr>'; });
            html += '</tbody></table></div>';
            el.innerHTML = html;
            el.classList.remove('hidden');
            $('sourcesClose').addEventListener('click', function () { el.classList.add('hidden'); });
        }).catch(function (e) { note('sources failed: ' + e); });
    }

    // ---- everything held for a hexagon
    function kv(rows) {
        var s = '<table class="table table-sm kv mb-1"><tbody>';
        rows.forEach(function (r) { if (r[1] != null && r[1] !== '' && r[1] !== '—') s += '<tr><th>' + esc(r[0]) + '</th><td class="mono">' + esc(r[1]) + '</td></tr>'; });
        return s + '</tbody></table>';
    }
    // The station's diurnal range (W-25): a day's high from 9 am against the low in the 24 hours to that 9 am.
    function diurnal(d, id) {
        if (!d) return '';
        var day = function (x, soFar) {
            if (!x) return null;
            var hl = 'high / low ' + fmt(x.highC, 1) + ' / ' + fmt(x.lowC, 1);
            return (x.rangeC != null ? fmt(x.rangeC, 1) + ' °C · ' : '') + hl + (soFar || !x.date ? '' : ' · ' + x.date.slice(8, 10) + '/' + x.date.slice(5, 7));
        };
        var period = function (p) { return !p ? null : p.meanRangeC != null ? fmt(p.meanRangeC, 1) + ' °C mean · ' + p.days + ' of ' + p.of + ' days' : 'no complete day of ' + p.of; };
        return '<h2>Diurnal range <span class="muted">' + esc(id) + ' · the day\x27s high from 9 am against the low to 9 am</span></h2>'
            + kv([['last full day', day(d.day, false)], ['today so far', day(d.today, true)], ['week', period(d.week)], ['month', period(d.month)]]);
    }
    function cmp(name, a, b, d, unit) {
        var delta = a != null && b != null ? a - b : null;
        return '<tr><td class="name">' + esc(name) + '</td><td class="ground">' + esc(fmt(a, d)) + '</td><td class="fc">' + esc(fmt(b, d)) + '</td><td class="d">' + (delta == null ? '—' : (delta > 0 ? '+' : '') + esc(fmt(delta, d))) + '</td><td class="d muted">' + esc(unit || '') + '</td></tr>';
    }
    function detail(id) {
        fetch('/console/map/hexagon/' + encodeURIComponent(id)).then(function (r) { return r.ok ? r.json() : null; }).then(function (h) {
            var el = $('detail');
            if (!h) { el.innerHTML = '<div class="drawer-head"><h3>' + esc(id) + '</h3><button class="icon-btn" id="close" type="button">' + icon('close') + '</button></div><p class="muted">not held</p>'; el.classList.remove('hidden'); $('close').addEventListener('click', function () { el.classList.add('hidden'); }); return; }
            var r = h.reading || {}, c = r.current || {}, f = r.fire || {}, g = f.grass || {}, o = f.official || {}, w = f.wind || {}, d = r.drought || {}, st = r.station || {}, fl = r.flood || {}, nb = r.nearby, hx = r.hexagon || {}, lu = hx.landUse, src = r.source || {};
            var fcNow = null;
            if (r.forecast && r.forecast.hours && r.forecast.hours.length) { var t0 = Date.now(), best = null; r.forecast.hours.forEach(function (x) { var dt = Math.abs(Date.parse(x.at) - t0); if (best == null || dt < best) { best = dt; fcNow = x; } }); }
            var html = '<div class="drawer-head"><h3>' + esc(h.id) + ' <span class="muted">' + esc(h.kind) + (h.fireBanDistrict ? ' · ' + esc(h.fireBanDistrict) : '') + '</span></h3><button class="icon-btn" id="close" type="button">' + icon('close') + '</button></div>';
            if (state.mode !== 'now') html += '<p class="muted mb-1">the map is ' + (state.mode === 'ahead' ? '+' + state.hours + ' h ahead' : Math.abs(state.hours) + ' h behind') + '; this is the hexagon now.</p>';
            if (r.available === false) html += '<p class="text-warning">' + esc(r.unavailable) + '</p>';
            // Now against the forecast, side by side: the one table this drawer is for.
            html += '<h2>Now <span class="muted">' + esc(FROM_WORDS[r.currentFrom] || r.currentFrom || '') + ' · ' + clock(r.at) + '</span> against the forecast <span class="muted">' + esc(src.upstream || '') + (fcNow ? ' · ' + clock(fcNow.at) : '') + '</span></h2>';
            html += '<table class="compare"><thead><tr><th></th><th class="ground">now</th><th class="fc">forecast</th><th>Δ</th><th></th></tr></thead><tbody>'
                + cmp('temperature', c.temperatureC, fcNow && fcNow.temperatureC, 1, '°C') + cmp('humidity', c.humidityPct, fcNow && fcNow.humidityPct, 0, '%')
                + cmp('wind', c.windSpeedKmh, fcNow && fcNow.windSpeedKmh, 0, 'km/h') + cmp('direction', c.windDirectionDeg, fcNow && fcNow.windDirectionDeg, 0, '°')
                + cmp('gust', c.windGustKmh, fcNow && fcNow.windGustKmh, 0, 'km/h') + cmp('rain', c.precipitationMm, fcNow && fcNow.precipitationMm, 1, 'mm')
                + cmp('feels like', c.apparentTemperatureC, null, 1, '°C') + cmp('dew point', c.dewPointC, null, 1, '°C') + cmp('pressure', c.pressureMslHpa, null, 1, 'hPa') + '</tbody></table>';
            var dr = h.drift;
            if (dr) {
                var pct = Math.min(100, dr.score / 1.5 * 100);
                html += '<div class="meter-row"><span class="muted">drift</span><div class="bar"><i style="width:' + pct + '%;background:' + driftColour(dr.score) + '"></i><b style="left:66.7%"></b></div><span class="mono">' + esc(dr.score) + '</span><span class="muted">' + (dr.drifted ? 'thrown out' : 'holds') + (dr.worst ? ' · ' + esc(dr.worst) : '') + '</span></div>';
                html += '<div class="muted" style="font-size:.74rem">' + esc(fmt(dr.temperatureC, 1)) + ' °C of 3 · ' + esc(fmt(dr.humidityPct)) + ' pts of 20 · ' + esc(fmt(dr.windKmh, 1)) + ' km/h of 15 · ' + esc(fmt(dr.rainMm, 1)) + ' mm of 5 · judged by ' + esc(dr.stationId) + ' at ' + clock(dr.at) + '</div>';
            }
            if (st.windShift) html += '<div class="shift-note ' + esc(st.windShift.grade) + '"><b>Wind change at ' + esc(st.id) + '</b> · ' + esc(st.windShift.description) + '</div>';
            // The wind as a trend: where it has been, is and is going, from the station point the map already holds.
            var sp = stationProps(st.id);
            if (sp && sp.fresh && (sp.windMeanOver != null || sp.fc1hWindKmh != null)) {
                html += '<h2>Wind at ' + esc(st.id) + ' <span class="muted">where it has been, is, and is going</span></h2>';
                html += '<div class="wt-row">' + trendGlyph(sp).replace('class="wt"', 'class="wt big"') + '<table class="table table-sm trend-table"><thead><tr><th></th><th>from</th><th class="num">km/h</th><th class="num">gust</th></tr></thead><tbody>'
                    + trendRow('mean of ' + sp.windMeanOver + ' <span class="muted">' + sp.windMeanMinutes + ' min</span>', 'mean', sp.windMeanDeg, sp.windMeanKmh, sp.windMeanGustKmh)
                    + trendRow('now <span class="muted">' + clock(sp.at) + '</span>', 'latest' + (sp.windShift ? ' ' + esc(sp.windShift) : ''), sp.windDirectionDeg, sp.windSpeedKmh, sp.windGustKmh)
                    + trendRow('model +1 h', 'fc', sp.fc1hWindDeg, sp.fc1hWindKmh, sp.fc1hGustKmh) + trendRow('model +3 h', 'fc', sp.fc3hWindDeg, sp.fc3hWindKmh, sp.fc3hGustKmh) + trendRow('model +6 h', 'fc', sp.fc6hWindDeg, sp.fc6hWindKmh, sp.fc6hGustKmh)
                    + '</tbody></table></div>';
                if (sp.windTrendSwingDeg != null || sp.windTrendDeltaKmh != null) html += '<div class="muted" style="font-size:.74rem">the latest against the mean: ' + (sp.windTrendSwingDeg != null ? 'swung ' + sp.windTrendSwingDeg + '°' : 'no usable swing') + (sp.windTrendDeltaKmh != null ? ', ' + (sp.windTrendDeltaKmh > 0 ? '+' : '') + sp.windTrendDeltaKmh + ' km/h' : '') + '</div>';
                if (sp.fcChangeAt) html += '<div class="shift-note fc"><b>Change expected ' + clock(sp.fcChangeAt) + '</b> <span class="muted">' + in_(sp.fcChangeAt) + '</span> · ' + dirWord(sp.fcChangeFromDeg) + ' ' + sp.fcChangeFromDeg + '° → ' + dirWord(sp.fcChangeToDeg) + ' ' + sp.fcChangeToDeg + '° at ' + Math.round(sp.fcChangeKmh) + ' km/h' + (sp.fcChangeGustKmh != null ? ', gusts ' + Math.round(sp.fcChangeGustKmh) : '') + ' <span class="muted">· the model\'s</span></div>';
            }
            if (nb && nb.stations) {
                html += '<h2>' + (nb.ring === 0 ? 'Stations in it, blended' : 'Neighbours') + ' <span class="muted">' + (nb.ring === 0 ? 'inside the hexagon' : 'ring ' + nb.ring) + ' · ' + (nb.elevationApplied ? 'brought to ' + Math.round(nb.elevationM) + ' m at ' + nb.lapseTemperatureCPerKm + ' °C/km (dew point ' + nb.lapseDewPointCPerKm + ')' : 'not moved for height') + '</span></h2><table class="table table-sm"><thead><tr><th>station</th><th class="num">km</th><th class="num">height</th><th class="num">weight</th></tr></thead><tbody>';
                nb.stations.forEach(function (x) { html += '<tr><td>' + esc(x.id) + ' <span class="muted">' + esc(x.name || '') + '</span></td><td class="num">' + fmt(x.distanceKm, 1) + '</td><td class="num">' + fmt(x.heightM) + '</td><td class="num">' + fmt(x.weight, 2) + '</td></tr>'; });
                html += '</tbody></table>';
            }
            html += '<h2>Hexagon</h2>';
            if (lu && lu.percent) {
                html += '<div class="stack">' + Object.keys(lu.percent).map(function (k) { return '<i style="width:' + lu.percent[k] + '%;background:' + (PALETTES.LAND[k] || '#999') + '" title="' + esc(k.replace('_', ' ')) + ' ' + lu.percent[k] + '%"></i>'; }).join('') + '</div>';
            }
            html += kv([['centre', fmt(h.lat, 4) + ', ' + fmt(h.lon, 4)], ['elevation', h.elevationM != null ? h.elevationM + ' m (' + h.elevationFrom + ')' : null], ['slope', h.slopeDeg != null ? h.slopeDeg + '°' : null],
                ['land use', lu && lu.percent ? Object.keys(lu.percent).map(function (k) { return k.replace('_', ' ') + ' ' + lu.percent[k] + '%'; }).join(', ') + (lu.leads ? ' → ' + lu.leads : '') + (lu.point ? ' · here: ' + lu.point.replace('_', ' ') : '') + (lu.source ? ' (' + lu.source + ')' : '') : null],
                ['bureau district', h.bureauDistrict], ['station in hexagon', h.stationId], ['nearest station', h.nearestStationId ? h.nearestStationId + ' at ' + fmt(h.nearestStationKm, 1) + ' km' : null],
                ['forecast fetched', when(h.refreshedAt)], ['forecast expires', when(h.expiresAt)], ['activated', when(h.activatedAt)], ['last asked', when(h.lastAskedAt)], ['asks this run', h.asks]]);
            html += '<h2>Fire</h2>';
            html += kv([['FFDI', f.ffdi != null ? f.ffdi + ' ' + f.ffdiRating + (f.peakFfdi != null ? ' (peak ' + f.peakFfdi + ')' : '') : null], ['drought factor', f.droughtFactor], ['KBDI', f.kbdiMm != null ? f.kbdiMm + ' mm ' + f.kbdiBand : null],
                ['GFDI', g.gfdi != null ? g.gfdi + ' ' + g.gfdiRating + ' (curing ' + g.curingPct + '%, ' + g.fuelLoadTHa + ' t/ha)' : (f.leads ? 'no curing figure' : null)], ['AFDRS grass', g.fbi != null ? 'FBI ' + g.fbi + ' ' + g.afdrsRating + ' · ' + g.rateOfSpreadKmh + ' km/h · ' + g.intensityKwm + ' kW/m' : null],
                ['official', o.rating ? o.rating + (o.fbi != null ? ' (FBI ' + o.fbi + ')' : '') + (o.totalFireBan ? ' · TOTAL FIRE BAN' : '') + ' · ' + o.district : null], ['leads', f.leads ? f.leads + (f.appliesToPct != null ? ' over ' + f.appliesToPct + '% of the hexagon' : '') : null],
                ['wind change', w.change ? when(w.change.at) + ' ' + w.change.fromDeg + '° → ' + w.change.toDeg + '° at ' + w.change.speedKmh + ' km/h' : null], ['fire weather warning', f.fireWeatherWarning ? 'YES' : null],
                ['VPD', f.vapourPressureDeficitKpa != null ? f.vapourPressureDeficitKpa + ' kPa' : null], ['mixing height', f.boundaryLayerHeightM != null ? f.boundaryLayerHeightM + ' m' : null]]);
            if (r.warnings && r.warnings.length) { html += '<h2>Warnings</h2><ul class="small mb-1">'; r.warnings.forEach(function (x) { html += '<li>' + esc(x.title) + ' ' + esc(x.phenomena || '') + (x.headline ? ' — ' + esc(x.headline) : '') + ' <span class="muted">until ' + when(x.until) + '</span></li>'; }); html += '</ul>'; }
            if (d.kbdiMm != null) { html += '<h2>Drought</h2>' + kv([['KBDI', d.kbdiMm + ' mm ' + d.kbdiBand], ['drought factor', d.droughtFactor], ['mean annual rain', d.meanAnnualRainfallMm + ' mm'], ['computed for', d.computedFor], ['spun up from', d.spunUpFrom + ' (' + d.days + ' days)'], ['inputs', h.drought && h.drought.from]]); }
            if (st.id) { html += '<h2>Station ' + esc(st.id) + ' <span class="muted">' + esc(st.name) + (st.insideHexagon ? '' : ' · ' + fmt(st.distanceKm, 1) + ' km away') + '</span></h2>'; html += kv([['at', when(st.at)], ['temperature', st.temperatureC != null ? st.temperatureC + ' °C' : null], ['humidity', st.humidityPct != null ? st.humidityPct + ' %' : null], ['wind', st.windSpeedKmh != null ? st.windSpeedKmh + ' km/h ' + (st.windDirection || '') + ' gust ' + fmt(st.windGustKmh) : null], ['rain since 9am', st.rainSince9amMm != null ? st.rainSince9amMm + ' mm' : null], ['rain to 9am', st.rain24hMm != null ? st.rain24hMm + ' mm' : null], ['max / min', (st.maxTemperatureC != null || st.minTemperatureC != null) ? fmt(st.maxTemperatureC) + ' / ' + fmt(st.minTemperatureC) : null]]); html += diurnal(st.diurnal, st.id); }
            if (st.recent && st.recent.length) {
                html += '<h2>Last readings <span class="muted">' + esc(st.id) + ' · newest first</span></h2><table class="table table-sm recent"><thead><tr><th>at</th><th class="num">°C</th><th class="num">RH</th><th class="num">wind</th><th>from</th><th class="num">gust</th></tr></thead><tbody>';
                st.recent.forEach(function (x) { html += '<tr><td class="mono">' + clock(x.at) + '</td><td class="num">' + fmt(x.temperatureC, 1) + '</td><td class="num">' + fmt(x.humidityPct) + '</td><td class="num">' + fmt(x.windSpeedKmh) + '</td><td class="dir">' + (x.windDirectionDeg != null ? '<span class="arrow" style="transform:rotate(' + ((x.windDirectionDeg + 180) % 360) + 'deg)">↑</span> ' + x.windDirectionDeg + '°' : '—') + '</td><td class="num">' + fmt(x.windGustKmh) + '</td></tr>'; });
                html += '</tbody></table>';
            }
            if (fl.rain1dMm != null || fl.forecastRain24hMm != null || fl.riverDischargeCumecs != null) { html += '<h2>Flood</h2>' + kv([['rain 1/2/3/7 d', fmt(fl.rain1dMm) + ' / ' + fmt(fl.rain2dMm) + ' / ' + fmt(fl.rain3dMm) + ' / ' + fmt(fl.rain7dMm) + ' mm'], ['ahead 24/48/72 h', fmt(fl.forecastRain24hMm) + ' / ' + fmt(fl.forecastRain48hMm) + ' / ' + fmt(fl.forecastRain72hMm) + ' mm'], ['river', fl.riverDischargeCumecs != null ? fl.riverDischargeCumecs + ' m³/s, ' + fmt(fl.dischargeRatioToMean) + '× the 92-day mean, ' + fmt(fl.riverTrend) : null]]); }
            if (r.forecast && r.forecast.days && r.forecast.days.length) {
                html += '<h2>Days ahead <span class="muted">' + esc(src.upstream || '') + '</span></h2><table class="table table-sm"><thead><tr><th>day</th><th class="num">min/max</th><th class="num">RH</th><th class="num">wind</th><th class="num">rain</th><th>FFDI</th><th>FBI</th></tr></thead><tbody>';
                r.forecast.days.forEach(function (x) { var df = x.fire || {}; html += '<tr><td class="mono">' + esc(x.date) + '</td><td class="num">' + fmt(x.minTemperatureC) + '/' + fmt(x.maxTemperatureC) + '</td><td class="num">' + fmt(x.minHumidityPct) + '</td><td class="num">' + fmt(x.maxWindKmh) + '</td><td class="num">' + fmt(x.precipitationMm) + '</td><td>' + (df.ffdi != null ? df.ffdi + ' ' + esc(df.ffdiRating) : '—') + '</td><td>' + (df.fbi != null ? df.fbi + ' ' + esc(df.afdrsRating) : '—') + '</td></tr>'; });
                html += '</tbody></table>';
            }
            // The ground's record (W-19): the model's stand-ins where the hexagon has no station; the station's six-hourly ledger, consolidated, where it has.
            if (h.history && h.history.length && h.history[0].from === 'model') {
                html += '<h2>Model stood in <span class="muted">no station within reach; the series at each fetch</span></h2><table class="table table-sm"><thead><tr><th>at</th><th class="num">°C</th><th class="num">RH</th><th class="num">wind</th><th class="num">gust</th><th>from</th></tr></thead><tbody>';
                h.history.forEach(function (s) { var sc = s.conditions || {}; html += '<tr><td class="mono">' + when(s.at) + '</td><td class="num">' + fmt(sc.temperatureC, 1) + '</td><td class="num">' + fmt(sc.humidityPct) + '</td><td class="num">' + fmt(sc.windSpeedKmh) + '</td><td class="num">' + fmt(sc.windGustKmh) + '</td><td class="muted">' + esc(s.upstream || '') + '</td></tr>'; });
                html += '</tbody></table>';
            }
            if (h.ledger && h.ledger.length) {
                html += '<h2>Station ledger <span class="muted">six-hourly; each row the readings since the last, consolidated</span></h2><table class="table table-sm"><thead><tr><th>at</th><th class="num">°C</th><th class="num">min·mean·max</th><th class="num">day max</th><th class="num">RH lo·hi</th><th class="num">wind mean·max</th><th class="num">gust</th><th class="num">rain 9am</th><th class="num">24h</th><th class="num">n</th></tr></thead><tbody>';
                h.ledger.forEach(function (s) { html += '<tr><td class="mono">' + when(s.at) + '</td><td class="num">' + fmt(s.temperature_c, 1) + '</td><td class="num">' + (s.temp_min_c != null ? fmt(s.temp_min_c, 1) + '·' + fmt(s.temp_mean_c, 1) + '·' + fmt(s.temp_max_c, 1) : '—') + '</td><td class="num">' + fmt(s.max_temperature_c, 1) + '</td><td class="num">' + (s.rh_min_pct != null ? s.rh_min_pct + '·' + s.rh_max_pct : '—') + '</td><td class="num">' + (s.wind_mean_kmh != null ? fmt(s.wind_mean_kmh) + '·' + fmt(s.wind_max_kmh) : '—') + '</td><td class="num">' + fmt(s.gust_max_kmh) + '</td><td class="num">' + fmt(s.rain_since_9am_mm, 1) + '</td><td class="num">' + fmt(s.rain_24h_mm, 1) + '</td><td class="num">' + fmt(s.readings) + '</td></tr>'; });
                html += '</tbody></table>';
            }
            html += '<button class="btn-probe" id="probe" type="button">' + icon('probe') + ' probe <span class="muted">an ask: reads what is due, spends allowance</span></button>';
            el.innerHTML = html;
            el.classList.remove('hidden');
            $('close').addEventListener('click', function () { el.classList.add('hidden'); });
            $('probe').addEventListener('click', function () { probe(h.lat, h.lon); });
        });
    }
    function probe(lat, lon) {
        var headers = window.gullyCsrf();
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        note('asking…');
        fetch('/console/map/probe', {method: 'POST', headers: headers, body: 'lat=' + lat + '&lon=' + lon})
            .then(function (r) { return r.json(); }).then(function (rd) { etag = null; lastStations = null; load(); stations(); sourcesPanel(); if (rd.hexagon) detail(rd.hexagon.id); note('asked: ' + (rd.hexagon ? rd.hexagon.id : '') + (rd.currentFrom ? ' · now from ' + rd.currentFrom : '')); });
    }

    // ---- the timeline: hours from now, a week back and three days ahead; a tick at every local midnight
    var timeInput = $('time');
    function ticks() {
        var el = $('ticks'), lo = Number(timeInput.min), hi = Number(timeInput.max), html = '', now = new Date();
        var d = new Date(now); d.setHours(0, 0, 0, 0);
        // Every midnight on a wide track; every other one, weekday only, on a narrow one.
        var narrow = el.clientWidth < 560;
        for (var day = -8; day <= 4; day++) {
            var t = new Date(d.getTime() + day * 86400000), h = (t.getTime() - now.getTime()) / 3600000;
            if (h < lo || h > hi || Math.abs(h) < 8 || (narrow && day % 2 !== 0)) continue;
            html += '<span style="left:' + ((h - lo) / (hi - lo) * 100) + '%">' + t.toLocaleDateString(undefined, narrow ? {weekday: 'short'} : {weekday: 'short', day: 'numeric'}) + '</span>';
        }
        html += '<span class="now-tick" style="left:' + ((0 - lo) / (hi - lo) * 100) + '%">now</span>';
        el.innerHTML = html;
    }
    function timeLabel() {
        var lo = Number(timeInput.min), hi = Number(timeInput.max), h = state.hours, lab = $('timeLabel');
        lab.style.left = ((h - lo) / (hi - lo) * 100) + '%';
        lab.textContent = h === 0 ? 'now' : (h > 0 ? '+' + h + ' h · ' : h + ' h · ') + new Date(Date.now() + h * 3600000).toLocaleString(undefined, {weekday: 'short', hour: '2-digit', minute: '2-digit'});
    }
    function slid(immediate) {
        state.hours = Number(timeInput.value);
        timeLabel();
        clearTimeout(loadTimer);
        loadTimer = setTimeout(load, immediate ? 0 : 160);
    }
    function play() {
        if (state.playing) { clearInterval(state.playing); state.playing = null; $('play').classList.remove('on'); $('play').innerHTML = icon('play'); return; }
        if (state.hours < 0) timeInput.value = 0;
        $('play').classList.add('on'); $('play').innerHTML = icon('pause');
        state.playing = setInterval(function () {
            var next = Number(timeInput.value) + 1;
            if (next > Number(timeInput.max)) { play(); return; }
            timeInput.value = next; slid(true);
        }, 900);
    }

    // ---- notes
    var noteTimer = null;
    function note(text) {
        var el = $('note');
        el.textContent = text; el.classList.remove('hidden');
        clearTimeout(noteTimer);
        noteTimer = setTimeout(function () { el.classList.add('hidden'); }, 3200);
    }

    // ---- wiring
    document.querySelectorAll('#tabs button').forEach(function (b) { b.addEventListener('click', function () { pickTab(b.dataset.tab); }); });
    document.querySelectorAll('.tog').forEach(function (b) {
        b.addEventListener('click', function () {
            var k = b.dataset.tog; togs[k] = !togs[k]; b.classList.toggle('on', togs[k]);
            if (k === 'grid') grid(); else if (k === 'stations' || k === 'trend') stations(); else if (k === 'borders' || k === 'regions') restyle(); else if (k === 'points' || k === 'forecasts') { draw(); legend(); tiles(); } else glyphs();
        });
    });
    $('windSwing').addEventListener('input', windMoved);
    $('windSpeed').addEventListener('input', windMoved);
    $('windSet').addEventListener('click', setWindChange);
    windSaved($('windSaved').dataset.swing, $('windSaved').dataset.speed, $('windSaved').dataset.by, $('windSaved').dataset.since);
    $('droughtRings').addEventListener('input', function () { droughtMoved(false); });
    $('droughtKm').addEventListener('input', function () { droughtMoved(false); });
    $('droughtRings').addEventListener('change', function () { droughtMoved(true); });
    $('droughtKm').addEventListener('change', function () { droughtMoved(true); });
    $('droughtSet').addEventListener('click', setDroughtRule);
    droughtSaved($('droughtSaved').dataset.rings, $('droughtSaved').dataset.km, $('droughtSaved').dataset.by, $('droughtSaved').dataset.since);
    $('inView').addEventListener('click', function () { inView = !inView; $('inView').classList.toggle('on', inView); $('inView').setAttribute('aria-checked', inView); legend(); });
    $('sourcesToggle').addEventListener('click', function () { var el = $('sources'); if (el.classList.contains('hidden')) sourcesPanel(true); else el.classList.add('hidden'); });
    timeInput.addEventListener('input', function () { slid(false); });
    timeInput.addEventListener('change', function () { slid(true); });
    $('reachKm').addEventListener('input', function () { reachMoved(false); });
    $('reachKm').addEventListener('change', function () { reachMoved(true); });
    $('reachSet').addEventListener('click', setReach);
    reachSaved($('reachSaved').dataset.km, $('reachSaved').dataset.by, $('reachSaved').dataset.since);
    $('now').addEventListener('click', function () { if (state.playing) play(); timeInput.value = 0; slid(true); });
    $('play').addEventListener('click', play);
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') { $('detail').classList.add('hidden'); $('sources').classList.add('hidden'); }
        if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT' || e.target.tagName === 'TEXTAREA') return;
        if (e.key === '1') pickTab('now'); else if (e.key === '2') pickTab('forecast'); else if (e.key === '3') pickTab('drought');
        else if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') { timeInput.value = Math.max(Number(timeInput.min), Math.min(Number(timeInput.max), Number(timeInput.value) + (e.key === 'ArrowRight' ? 1 : -1))); slid(false); }
        else if (e.key === ' ') { e.preventDefault(); play(); }
        else if (e.key === '0') { timeInput.value = 0; slid(true); }
    });
    var zoomWasPoints = pointsMode();
    map.on('zoomend', function () { var pm = pointsMode(); if (!isCoverage() && (pm !== zoomWasPoints || pm)) { zoomWasPoints = pm; draw(); } else glyphs(); stations(); if (isFeed()) drawFeed(); });
    map.on('moveend', function () { if (togs.grid) grid(); if (inView) legend(); if (isCoverage()) glyphs(); });
    window.addEventListener('resize', function () { ticks(); timeLabel(); });
    map.on('click', function (e) {
        // On the drought's feed a click spins the hexagon's drought up on its own; elsewhere it is the probe.
        if (isFeed()) {
            if (confirm('Spin up the drought for the hexagon at ' + e.latlng.lat.toFixed(4) + ', ' + e.latlng.lng.toFixed(4) + '? A year of the archive: about 26 units, once.')) spinDrought(e.latlng.lat, e.latlng.lng);
            return;
        }
        var b = confirm('Probe ' + e.latlng.lat.toFixed(4) + ', ' + e.latlng.lng.toFixed(4) + '? This is an ask: it reads whatever is due for that state and spends allowance.');
        if (b) probe(e.latlng.lat, e.latlng.lng);
    });
    document.addEventListener('gully:theme', function () { restyle(); });

    // The coach mark, once per browser: what the switch is for.
    var coached = false;
    try { coached = localStorage.getItem('gully.map.coached') === '1'; } catch (e) {}
    if (!coached) $('coach').classList.remove('hidden');
    $('coachOk').addEventListener('click', function () { $('coach').classList.add('hidden'); try { localStorage.setItem('gully.map.coached', '1'); } catch (e) {} });

    buildSide();
    ticks();
    timeLabel();
    legend();
    load();
    stations();
    sourcesPanel(false);
    // Live: the layer and the stations every minute, the sources every half minute, and the states in view
    // told to the service so their files are read when due (W-14: the open map is the request). Paused
    // while the tab is hidden; caught up the moment it is shown again.
    var lastLoadAt = null;
    function watch() {
        if (document.hidden || !window.gullyCsrf) return;
        var b = map.getBounds(), headers = window.gullyCsrf();
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        fetch('/console/map/watch', {method: 'POST', headers: headers, body: 'south=' + b.getSouth() + '&west=' + b.getWest() + '&north=' + b.getNorth() + '&east=' + b.getEast()})
            .then(function (r) { return r.json(); }).then(function (w) { if (w.read && w.read.length) { lastStations = null; stations(); etag = null; load(); } }).catch(function () {});
    }
    function liveTick() {
        var el = $('live');
        el.classList.toggle('paused', document.hidden);
        $('liveText').textContent = document.hidden ? 'paused' : (lastLoadAt ? 'live · ' + clock(lastLoadAt) : 'live');
    }
    document.addEventListener('visibilitychange', function () { liveTick(); if (!document.hidden) { load(); lastStations = null; stations(); sourcesPanel(); watch(); } });
    setInterval(liveTick, 1000);
    setInterval(watch, 60000);
    watch();
    setInterval(function () { if (!document.hidden && state.hours === 0) { load(); lastStations = null; stations(); if (isCoverage()) coverage(); if (isFeed()) droughtFeed(); } }, 60000);
    setInterval(function () { if (!document.hidden && (current().kind === 'life' || current().kind === 'from' || state.group === 'fc')) restyle(); }, 20000);
    setInterval(function () { if (!document.hidden) sourcesPanel(); }, 30000);
    setInterval(ticks, 600000);
})();
