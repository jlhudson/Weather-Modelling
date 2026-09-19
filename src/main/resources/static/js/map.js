// The map: every hexagon held, with "now" and the forecast kept apart. "Now" is what the ground
// says - the station in the hexagon, the stations in it blended, or the neighbours brought to its
// elevation - and is coloured by where it came from or by a value; the forecast is the model's
// series read at this moment, coloured by a value or fading as its life runs out; and the two can
// be shown against each other, value by value, or as the drift score. The weather is drawn on each
// hexagon as a wind arrow and a label, in black for now and in amber for the forecast. A sources
// panel shows that nothing is read but on request: each source's cadence, when an ask last checked
// it, what it holds, which hexagon asked, and the last reads the ledger saw. A time slider goes
// over the history; a click opens everything held for a hexagon. Deferred, so it runs after Leaflet
// and console.js.
(function () {
    'use strict';
    var $ = function (id) { return document.getElementById(id); };
    var esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]; }); };
    var fmt = function (v, d) { return v == null ? '—' : (typeof v === 'number' ? (d == null ? v : v.toFixed(d)) : String(v)); };
    var when = function (iso) { if (!iso) return '—'; var d = new Date(iso); return isNaN(d) ? String(iso) : d.toLocaleString(undefined, {day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'}); };
    var clock = function (iso) { if (!iso) return '—'; var d = new Date(iso); return isNaN(d) ? String(iso) : d.toLocaleTimeString(undefined, {hour: '2-digit', minute: '2-digit', second: '2-digit'}); };
    var ago = function (iso) { if (!iso) return '—'; var m = Math.round((Date.now() - Date.parse(iso)) / 60000); return m < 1 ? 'just now' : m < 120 ? m + ' min ago' : Math.round(m / 60) + ' h ago'; };
    var in_ = function (iso) { if (!iso) return '—'; var m = Math.round((Date.parse(iso) - Date.now()) / 60000); return m <= 0 ? 'on the next ask' : m < 120 ? 'in ' + m + ' min' : 'in ' + Math.round(m / 60) + ' h'; };

    var map = L.map('map').setView([-34.93, 138.6], 7);
    window.gullyBaseLayer(map);
    var hexLayer = L.geoJSON(null, {style: styleOf, onEachFeature: onHexagon, filter: shown}).addTo(map);
    var gridLayer = L.geoJSON(null, {style: {color: '#888', weight: .6, fill: false, opacity: .6}, interactive: false});
    var stationLayer = L.layerGroup().addTo(map);
    var glyphLayer = L.layerGroup().addTo(map);
    var etag = null, value = 'now:from', at = null, lastFc = null;

    // ---- colouring
    // Where "now" comes from: blues for the ground - one station, several blended, the neighbours
    // brought here - amber for the model standing in, faded as its life runs out; nothing is a faint outline.
    var FROM = {station: '#2563eb', stations: '#1e3a8a', neighbours: '#0d9488', model: '#f59e0b', none: '#9ca3af'};
    var FROM_WORDS = {station: 'station in it', stations: 'stations in it, blended', neighbours: 'neighbours, brought to its height', model: 'model standing in', none: 'nothing yet'};
    var ACT = {forecast: '#f59e0b', drought: '#a855f7'};
    var RATING = {'LOW-MODERATE': '#9bc466', 'HIGH': '#f7e463', 'VERY HIGH': '#f0a04b', 'SEVERE': '#e35d3c', 'EXTREME': '#c1272d', 'CATASTROPHIC': '#6d2077',
        'No Rating': '#dddddd', 'Moderate': '#7fc47f', 'High': '#f7e463', 'Extreme': '#f0a04b', 'Catastrophic': '#c1272d'};
    var KIND = {station: '#3b82f6', forecast: '#f59e0b', both: '#a855f7', bare: '#9ca3af'};
    var LEADS = {forest: '#15803d', grass: '#ca8a04'};
    var LAND = {forest: '#14532d', scrub: '#4d7c0f', grassland: '#ca8a04', cropland: '#eab308', built_up: '#6b7280', water: '#2563eb', bare: '#a16207', unknown: '#9ca3af'};
    var RANGES = {temperatureC: [0, 45], humidityPct: [0, 100], windKmh: [0, 80], gustKmh: [0, 110], rainMm: [0, 25], age: [0, 180],
        ffdi: [0, 100], gfdi: [0, 150], fbi: [0, 100], droughtFactor: [0, 10], kbdiMm: [0, 203], curingPct: [0, 100], elevationM: [0, 1500],
        burnablePct: [0, 100], asked: [0, 120], asks: [0, 50], drift: [0, 1.5], drift24h: [0, 1.5]};
    var DIFF = {temperatureC: 5, humidityPct: 25, windKmh: 20};
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
    // The stations against the forecast: green agrees, amber is halfway to the tolerance, red is at it
    // (the forecast was thrown out), dark red beyond.
    function driftColour(score) {
        if (score == null) return null;
        if (score >= 1.25) return '#7f1d1d';
        if (score >= 1) return '#dc2626';
        if (score >= .5) return 'rgb(' + Math.round(245 + (220 - 245) * (score - .5) * 2) + ',' + Math.round(158 + (38 - 158) * (score - .5) * 2) + ',11)';
        return 'rgb(' + Math.round(34 + (245 - 34) * score * 2) + ',' + Math.round(197 + (158 - 197) * score * 2) + ',' + Math.round(94 + (11 - 94) * score * 2) + ')';
    }
    // How much of a forecast's life is left: 1 when just fetched, 0 at its expiry, 0 once stale.
    function freshness(p) {
        if (p.stale) return 0;
        if (!p.fcFetchedAt || !p.fcExpiresAt) return 1;
        var from = Date.parse(p.fcFetchedAt), to = Date.parse(p.fcExpiresAt);
        if (isNaN(from) || isNaN(to) || to <= from) return 1;
        return Math.max(0, Math.min(1, (to - Date.now()) / (to - from)));
    }
    // The layer's side and field: "now:temperatureC" is the ground's temperature, "fc:temperatureC" the model's,
    // "diff:temperatureC" one against the other; everything else is a field of the hexagon itself.
    function side() { var i = value.indexOf(':'); return i < 0 ? '' : value.substring(0, i); }
    function field() { var i = value.indexOf(':'); return i < 0 ? value : value.substring(i + 1); }
    function read(p) {
        var s = side(), f = field();
        if (s === 'now') return f === 'age' ? p.nowAgeMinutes : p['now' + f.charAt(0).toUpperCase() + f.slice(1)];
        if (s === 'fc') return f === 'age' ? p.fcAgeMinutes : p['fc' + f.charAt(0).toUpperCase() + f.slice(1)];
        if (s === 'diff') return p['diff' + f.charAt(0).toUpperCase() + f.slice(1)];
        if (f === 'asked') return p.askedMinutesAgo;
        return p[f];
    }
    function colour(p) {
        var v = read(p), f = field(), s = side();
        if (v == null) return null;
        if (s === 'diff') { var lim = DIFF[f] || 10; return ramp(.5 + Math.max(-1, Math.min(1, v / lim)) / 2); }
        if (f === 'ffdi' && p.ffdiRating) return RATING[p.ffdiRating] || ramp(v / 100);
        if (f === 'gfdi' && p.gfdiRating) return RATING[p.gfdiRating] || ramp(v / 150);
        if (f === 'fbi' && p.afdrsRating) return RATING[p.afdrsRating] || ramp(v / 100);
        if (f === 'officialRating') return RATING[v] || '#9ca3af';
        if (f === 'kind') return KIND[v] || '#9ca3af';
        if (f === 'leads') return LEADS[v] || '#9ca3af';
        if (f === 'landDominant') return LAND[v] || '#9ca3af';
        if (f === 'drift' || f === 'drift24h') return driftColour(v);
        var r = RANGES[f] || [0, 100];
        if (f === 'humidityPct' || f === 'age' || f === 'asked') return ramp(1 - (v - r[0]) / (r[1] - r[0]));
        return ramp((v - r[0]) / (r[1] - r[0]));
    }
    // The forecasts-only switch: without a forecast, a hexagon is not drawn at all.
    function shown(f) { return !$('forecasts').checked || f.properties.hasForecast; }
    function styleOf(f) {
        var p = f.properties;
        var outline = {color: p.hasDrought ? ACT.drought : (p.warm ? '#111' : '#777'), weight: p.hasDrought ? 1.6 : (p.warm ? 1.2 : .6), opacity: p.hasDrought ? .9 : .5,
            dashArray: p.stale ? '4 3' : null};
        if (value === 'now:from') {
            var from = p.from || 'none', fill = FROM[from];
            // A station hexagon whose state has not been asked about lately has no fresh values: the blue, faintly.
            if (from === 'none' && p.hasStation) fill = FROM.station;
            var op = from === 'none' ? (p.hasStation ? .12 : .03) : from === 'model' ? .1 + .4 * freshness(p) : .45;
            return Object.assign(outline, {fillColor: fill, fillOpacity: op});
        }
        if (value === 'fc:life') {
            return Object.assign(outline, {fillColor: p.hasForecast ? ACT.forecast : '#000', fillOpacity: p.hasForecast ? .1 + .5 * freshness(p) : .03});
        }
        var c = colour(p);
        return Object.assign(outline, {fillColor: c || '#000', fillOpacity: c ? (side() === 'fc' ? .25 + .35 * freshness(p) : .55) : .04});
    }
    function legend() {
        var el = $('legend'), html = '', f = field(), s = side();
        if (value === 'now:from') {
            Object.keys(FROM).forEach(function (k) { html += '<i style="background:' + FROM[k] + (k === 'none' ? ';opacity:.3' : '') + '" title="' + FROM_WORDS[k] + '"></i><span class="muted me-2">' + FROM_WORDS[k] + '</span>'; });
            html += '<i style="background:' + FROM.station + ';opacity:.25" title="station in it, its state not asked about lately"></i><span class="muted me-2">station in it, state not asked about lately</span>';
            html += '<i style="background:transparent;border:2px solid ' + ACT.drought + '" title="drought stepped"></i><span class="muted">drought stepped</span>';
        }
        else if (value === 'fc:life') html = '<i style="background:' + ACT.forecast + '" title="forecast held · fades as its life runs out"></i><span class="muted me-2">forecast held · fades as its life runs out</span><i style="background:transparent;border:1px dashed #777" title="stale"></i><span class="muted">past its life</span>';
        else if (s === 'diff') { var lim = DIFF[f] || 10; html = '<span class="muted">now −' + lim + '</span>'; for (var j = 0; j <= 8; j++) html += '<i style="background:' + ramp(j / 8) + '"></i>'; html += '<span class="muted">now +' + lim + '</span>'; }
        else if (f === 'ffdi' || f === 'gfdi') ['LOW-MODERATE', 'HIGH', 'VERY HIGH', 'SEVERE', 'EXTREME', 'CATASTROPHIC'].forEach(function (k) { html += '<i style="background:' + RATING[k] + '" title="' + k + '"></i>'; });
        else if (f === 'fbi' || f === 'officialRating') ['No Rating', 'Moderate', 'High', 'Extreme', 'Catastrophic'].forEach(function (k) { html += '<i style="background:' + RATING[k] + '" title="' + k + '"></i>'; });
        else if (f === 'drift' || f === 'drift24h') {
            html = '<span class="muted">agrees</span>';
            [0, .25, .5, .75, 1, 1.25].forEach(function (v) { html += '<i style="background:' + driftColour(v) + '" title="' + v + '"></i>'; });
            html += '<span class="muted">thrown out</span>';
        }
        else if (f === 'kind') Object.keys(KIND).forEach(function (k) { html += '<i style="background:' + KIND[k] + '" title="' + k + '"></i><span class="muted me-1">' + k + '</span>'; });
        else if (f === 'leads') Object.keys(LEADS).forEach(function (k) { html += '<i style="background:' + LEADS[k] + '" title="' + k + '"></i><span class="muted me-1">' + k + '</span>'; });
        else if (f === 'landDominant') Object.keys(LAND).forEach(function (k) { html += '<i style="background:' + LAND[k] + '" title="' + k + '"></i><span class="muted me-1">' + k.replace('_', ' ') + '</span>'; });
        else { var r = RANGES[f] || [0, 100], rev = f === 'humidityPct' || f === 'age' || f === 'asked'; html = '<span class="muted">' + r[0] + '</span>'; for (var i = 0; i <= 8; i++) html += '<i style="background:' + ramp(rev ? 1 - i / 8 : i / 8) + '"></i>'; html += '<span class="muted">' + r[1] + '</span>'; }
        el.innerHTML = html;
    }

    // ---- the layer
    function load() {
        var url = '/console/map/layer.geojson' + (at ? '?at=' + encodeURIComponent(at) : '');
        fetch(url, {headers: etag && !at ? {'If-None-Match': etag} : {}}).then(function (r) {
            if (r.status === 304) return null;
            if (!at) etag = r.headers.get('ETag');
            return r.json();
        }).then(function (fc) {
            if (!fc) return;
            lastFc = fc;
            draw();
            var m = fc.meta || {}, nf = m.nowFrom || {};
            $('status').textContent = (m.hexagons || 0) + ' hexagons' + (at ? ' at ' + when(at) : '')
                + (m.active != null ? ' · now from the ground in ' + ((nf.station || 0) + (nf.stations || 0) + (nf.neighbours || 0)) + ' (' + (nf.station || 0) + ' station, ' + (nf.stations || 0) + ' blended, ' + (nf.neighbours || 0) + ' neighbours), the model in ' + (nf.model || 0)
                    + ' · ' + m.withForecast + ' forecasts held, ' + m.lifeMinutes + ' min life' + (m.withDrought == null ? '' : ' · ' + m.withDrought + ' drought stepped') + ' · ' + (m.withLandUse || 0) + ' with land use' : '');
        }).catch(function (e) { $('status').textContent = 'layer failed: ' + e; });
    }
    // The held layer drawn again: the filter and the styles are read at draw time, so a switch redraws.
    function draw() {
        hexLayer.clearLayers();
        if (lastFc) hexLayer.addData(lastFc);
        glyphs();
    }
    function line(t, rh, w, dir, gust) {
        if (t == null && rh == null && w == null) return null;
        return esc(fmt(t, 1)) + ' °C · ' + esc(fmt(rh)) + ' % · ' + esc(fmt(w == null ? null : Math.round(w))) + ' km/h' + (dir != null ? ' from ' + dir + '°' : '') + (gust != null ? ' gust ' + Math.round(gust) : '');
    }
    function onHexagon(f, layer) {
        var p = f.properties;
        layer.bindTooltip(function () {
            var s = '<b>' + esc(p.id) + '</b> <span class="muted">' + esc(p.kind) + (p.hasDrought ? ' · drought stepped' : '') + (p.fireBanDistrict ? ' · ' + esc(p.fireBanDistrict) : '') + '</span>';
            var now = line(p.nowTemperatureC, p.nowHumidityPct, p.nowWindKmh, p.nowWindDeg, p.nowGustKmh);
            if (now) s += '<br><b>now</b> ' + now + ' <span class="muted">' + esc(FROM_WORDS[p.from] || p.from) + (p.nowStations > 1 ? ' (' + p.nowStations + (p.nowRing === 0 ? ' in it' : p.nowRing != null ? ', ring ' + p.nowRing : '') + ')' : '') + (p.stationId && p.from === 'station' ? ' ' + esc(p.stationId) : '') + ' · ' + clock(p.nowAt) + '</span>';
            else if (p.hasStation) s += '<br><b>now</b> <span class="muted">station ' + esc(p.stationId) + ' in it, no fresh values: the file for its state has not been asked for lately</span>';
            else s += '<br><b>now</b> <span class="muted">nothing from the ground' + (p.nearestStationId ? ' · nearest station ' + esc(p.nearestStationId) : '') + '</span>';
            var fc = line(p.fcTemperatureC, p.fcHumidityPct, p.fcWindKmh, p.fcWindDeg, p.fcGustKmh);
            if (fc) s += '<br><b>forecast</b> ' + fc + ' <span class="muted">' + esc(p.upstream || '') + ' · fetched ' + clock(p.fcFetchedAt) + (p.stale ? ' · <b>past its life</b>' : p.fcMinutesLeft != null ? ' · ' + p.fcMinutesLeft + ' min left' : '') + '</span>';
            else if (p.hasForecast) s += '<br><b>forecast</b> <span class="muted">held, nothing for this hour</span>';
            if (p.diffTemperatureC != null || p.diffHumidityPct != null || p.diffWindKmh != null) s += '<br>now − forecast: ' + esc(fmt(p.diffTemperatureC, 1)) + ' °C, ' + esc(fmt(p.diffHumidityPct)) + ' pts, ' + esc(fmt(p.diffWindKmh, 1)) + ' km/h';
            if (p.drift != null) s += '<br>drift ' + esc(p.drift) + (p.drifted ? ' <b>thrown out</b>' : '') + (p.driftWorst ? ' (' + esc(p.driftWorst) + ')' : '') + ' <span class="muted">stations against the forecast at ' + clock(p.driftAt) + (p.drift24h != null ? ' · 24 h mean ' + esc(p.drift24h) : '') + '</span>';
            if (p.ffdi != null) s += '<br>FFDI ' + esc(p.ffdi) + ' ' + esc(p.ffdiRating || '') + (p.fbi != null ? ' · FBI ' + esc(p.fbi) + ' ' + esc(p.afdrsRating || '') : '') + (p.droughtFactor != null ? ' · DF ' + esc(p.droughtFactor) : '');
            var ground = [];
            if (p.elevationM != null) ground.push(Math.round(p.elevationM) + ' m' + (p.elevationFrom ? ' (' + esc(p.elevationFrom) + ')' : ''));
            if (p.landUse) ground.push(Object.keys(p.landUse).map(function (k) { return k.replace('_', ' ') + ' ' + p.landUse[k] + '%'; }).join(', ') + (p.leads ? ' → ' + p.leads : ''));
            if (ground.length) s += '<br><span class="muted">' + ground.join(' · ') + '</span>';
            if (side() === '' && read(p) != null && ['ffdi', 'fbi', 'drift', 'drift24h', 'elevationM'].indexOf(field()) < 0) s += '<br>' + esc(field()) + ': ' + esc(fmt(read(p), 1));
            s += '<br><span class="muted">' + (p.lastAskedAt ? 'last asked ' + ago(p.lastAskedAt) + ' · ' + p.asks + ' asks this run' : 'never asked') + '</span>';
            return s;
        }, {sticky: true});
        layer.on('click', function (e) { L.DomEvent.stopPropagation(e); detail(p.id); });
    }

    // ---- the weather each hexagon knows, drawn on it: an arrow the way the wind blows, its length by
    // the speed, from zoom 7 where a hexagon is about fifteen pixels; the temperature, humidity and
    // speed as a label from zoom 9 where there is room. Black for now, amber for the forecast, whichever
    // side the layer shows. Never interactive: the hexagon under it is.
    function arrow(fromDeg, speed, cls) {
        if (speed < 1) return '<svg class="hx-arrow ' + cls + '" width="24" height="24" viewBox="-12 -12 24 24"><circle r="2"/></svg>';
        var len = 7 + Math.min(1, speed / 60) * 13, to = (fromDeg + 180) % 360, h = len / 2;
        return '<svg class="hx-arrow ' + cls + '" width="24" height="24" viewBox="-12 -12 24 24" style="transform:rotate(' + to + 'deg)">'
            + '<line x1="0" y1="' + h + '" x2="0" y2="' + (-h) + '"/><polyline points="-3.5,' + (-h + 4) + ' 0,' + (-h) + ' 3.5,' + (-h + 4) + '"/></svg>';
    }
    function glyphs() {
        glyphLayer.clearLayers();
        var z = map.getZoom();
        if (!$('weather').checked || z < 7) return;
        var fc = side() === 'fc', cls = fc ? 'fc' : 'now';
        hexLayer.eachLayer(function (layer) {
            var p = layer.feature.properties;
            if (p.lat == null) return;
            var t = fc ? p.fcTemperatureC : p.nowTemperatureC, rh = fc ? p.fcHumidityPct : p.nowHumidityPct, w = fc ? p.fcWindKmh : p.nowWindKmh, dir = fc ? p.fcWindDeg : p.nowWindDeg;
            if (!fc && p.from === 'model') return; // the model is not "now": its values are on the forecast layers
            var html = '';
            if (dir != null && w != null) html += arrow(dir, w, cls);
            if (z >= 9 && (t != null || rh != null)) {
                html += '<span class="hx-label ' + cls + '">' + (t != null ? Math.round(t) + '°' : '') + (rh != null ? ' ' + rh + '%' : '') + (w != null ? ' ' + Math.round(w) : '') + '</span>';
            }
            if (!html) return;
            L.marker([p.lat, p.lon], {icon: L.divIcon({className: 'hx-glyph', html: html, iconSize: [24, 24], iconAnchor: [12, 12]}), interactive: false, keyboard: false}).addTo(glyphLayer);
        });
    }

    // ---- the tessellation
    function grid() {
        if (!$('grid').checked) { map.removeLayer(gridLayer); return; }
        var b = map.getBounds();
        fetch('/console/map/grid.geojson?south=' + b.getSouth() + '&west=' + b.getWest() + '&north=' + b.getNorth() + '&east=' + b.getEast())
            .then(function (r) { return r.json(); }).then(function (fc) {
                gridLayer.clearLayers();
                if (fc.meta && fc.meta.tooMany) { $('status').textContent = 'zoom in to draw the tessellation'; return; }
                gridLayer.addData(fc).addTo(map);
                gridLayer.bringToBack();
            });
    }

    // ---- the stations
    function stations() {
        stationLayer.clearLayers();
        if (!$('stations').checked) return;
        fetch('/console/map/stations.geojson').then(function (r) { return r.json(); }).then(function (fc) {
            fc.features.forEach(function (f) {
                var p = f.properties, ll = [f.geometry.coordinates[1], f.geometry.coordinates[0]];
                L.circleMarker(ll, {radius: 3, color: '#2563eb', weight: 1, fillColor: '#2563eb', fillOpacity: .9})
                    .bindTooltip(function () {
                        return '<b>' + esc(p.name) + '</b> ' + esc(p.id) + '<br>' + esc(fmt(p.temperatureC, 1)) + ' °C · ' + esc(fmt(p.humidityPct)) + ' % · ' + esc(fmt(p.windSpeedKmh)) + ' km/h'
                            + (p.windDirectionDeg != null ? ' from ' + p.windDirectionDeg + '°' : '') + (p.windGustKmh != null ? ' gust ' + p.windGustKmh : '') + '<br>' + when(p.at) + ' · ' + esc(p.district) + (p.heightM != null ? ' · ' + p.heightM + ' m' : '');
                    }, {sticky: true})
                    .on('click', function (e) { L.DomEvent.stopPropagation(e); detail(p.hexagon); })
                    .addTo(stationLayer);
            });
        });
    }

    // ---- the sources: read on request only, and the panel says by whom
    function sourcesPanel() {
        var el = $('sources');
        if (el.classList.contains('hidden')) return;
        fetch('/console/map/sources.json').then(function (r) { return r.json(); }).then(function (s) {
            var html = '<div class="d-flex justify-content-between align-items-start"><h3>sources <span class="muted">read on request, never on a timer</span></h3><button class="btn btn-sm btn-outline-secondary py-0" id="sourcesClose" type="button">×</button></div>';
            if (!s.sources.length) html += '<p class="muted mb-1">nothing has been asked for since the service started: no source has been read.</p>';
            s.sources.forEach(function (x) {
                html += '<div class="src"><b>' + esc(x.name) + '</b> <span class="muted">every ' + (x.cadenceMinutes >= 1440 ? Math.round(x.cadenceMinutes / 1440) + ' d' : x.cadenceMinutes >= 60 ? Math.round(x.cadenceMinutes / 60) + ' h' : x.cadenceMinutes + ' min') + '</span>'
                    + (x.failure ? ' <span class="text-warning">' + esc(x.failure) + '</span>' : '')
                    + '<br><span class="muted">checked</span> ' + esc(ago(x.checkedAt)) + ' <span class="muted">· read</span> ' + esc(ago(x.readAt)) + (x.items != null ? ' <span class="muted">(' + x.items + (x.id.indexOf('bureau-') === 0 ? ' stations, the whole file' : x.id.indexOf('warnings-') === 0 ? ' warnings held' : x.id === 'cfs-ratings' ? ' districts' : ' shapes') + ')</span>' : '')
                    + (x.triggeredBy ? ' <span class="muted">· by an ask for</span> ' + esc(x.triggeredBy) + ' <span class="muted">' + esc(ago(x.triggeredAt)) + '</span>' : '')
                    + ' <span class="muted">· next check</span> ' + esc(in_(x.dueAt)) + '</div>';
            });
            html += '<div class="src"><b>Open-Meteo</b> <span class="muted">forecasts, elevation, drought archive, rivers · fetched when an ask needs them · ' + Math.round((s.allowance.dayFraction || 0) * 100) + ' % of today\'s allowance used · a forecast lives ' + s.allowance.lifeMinutes + ' min</span></div>';
            html += '<h3 class="mt-2">last reads <span class="muted">newest first · each against the hexagon that asked</span></h3><div class="reads"><table class="table table-sm mb-0"><tbody>';
            s.reads.forEach(function (r) {
                html += '<tr><td class="mono">' + esc(clock(r.at)) + '</td><td class="mono">' + esc(r.source) + '</td><td>' + (r.ok ? '' : '<span class="text-warning">failed · </span>') + esc(r.detail || '') + '</td><td class="num muted">' + (r.ms != null ? r.ms + ' ms' : '') + '</td></tr>';
            });
            html += '</tbody></table></div>';
            el.innerHTML = html;
            $('sourcesClose').addEventListener('click', function () { el.classList.add('hidden'); });
        }).catch(function (e) { el.innerHTML = '<p class="text-warning">sources failed: ' + esc(e) + '</p>'; });
    }

    // ---- everything held for a hexagon
    function kv(rows) {
        var s = '<table class="table table-sm kv mb-1"><tbody>';
        rows.forEach(function (r) { if (r[1] != null && r[1] !== '' && r[1] !== '—') s += '<tr><th>' + esc(r[0]) + '</th><td class="mono">' + esc(r[1]) + '</td></tr>'; });
        return s + '</tbody></table>';
    }
    function detail(id) {
        fetch('/console/map/hexagon/' + encodeURIComponent(id)).then(function (r) { return r.ok ? r.json() : null; }).then(function (h) {
            var el = $('detail');
            if (!h) { el.innerHTML = '<p class="muted">not held</p>'; el.classList.remove('hidden'); return; }
            var r = h.reading || {}, c = r.current || {}, f = r.fire || {}, g = f.grass || {}, o = f.official || {}, w = f.wind || {}, d = r.drought || {}, st = r.station || {}, fl = r.flood || {}, nb = r.nearby, hx = r.hexagon || {}, lu = hx.landUse;
            var html = '<div class="d-flex justify-content-between align-items-start"><h3>' + esc(h.id) + ' <span class="muted">' + esc(h.kind) + '</span></h3><button class="btn btn-sm btn-outline-secondary py-0" id="close" type="button">×</button></div>';
            html += kv([['centre', fmt(h.lat, 4) + ', ' + fmt(h.lon, 4)], ['zone', h.zone], ['elevation', h.elevationM != null ? h.elevationM + ' m (' + h.elevationFrom + ')' : null], ['slope', h.slopeDeg != null ? h.slopeDeg + '°' : null],
                ['land use', lu && lu.percent ? Object.keys(lu.percent).map(function (k) { return k.replace('_', ' ') + ' ' + lu.percent[k] + '%'; }).join(', ') + (lu.leads ? ' → ' + lu.leads : '') + (lu.source ? ' (' + lu.source + ')' : '') : (h.landUse ? Object.keys(h.landUse).map(function (k) { return k + ' ' + h.landUse[k] + '%'; }).join(', ') : null)],
                ['fire ban district', h.fireBanDistrict], ['bureau district', h.bureauDistrict], ['station in hexagon', h.stationId], ['nearest station', h.nearestStationId ? h.nearestStationId + ' at ' + fmt(h.nearestStationKm, 1) + ' km' : null],
                ['upstream', h.upstream], ['forecast fetched', when(h.refreshedAt)], ['forecast expires', when(h.expiresAt)], ['activated', when(h.activatedAt)], ['last asked', when(h.lastAskedAt)], ['asks this run', h.asks], ['snapshots', h.historyCount]]);
            if (r.available === false) html += '<p class="text-warning">' + esc(r.unavailable) + '</p>';
            html += '<h2>now <span class="muted">' + esc(FROM_WORDS[r.currentFrom] || r.currentFrom || '') + ' · ' + when(r.at) + '</span></h2>';
            html += kv([['temperature', c.temperatureC != null ? c.temperatureC + ' °C' + (c.apparentTemperatureC != null ? ' (feels ' + c.apparentTemperatureC + ')' : '') : null], ['humidity', c.humidityPct != null ? c.humidityPct + ' %' : null], ['dew point', c.dewPointC != null ? c.dewPointC + ' °C' : null],
                ['wind', c.windSpeedKmh != null ? c.windSpeedKmh + ' km/h from ' + fmt(c.windDirectionDeg) + '° gust ' + fmt(c.windGustKmh) : null], ['pressure', c.pressureMslHpa != null ? c.pressureMslHpa + ' hPa' : null], ['rain', c.precipitationMm != null ? c.precipitationMm + ' mm' : null], ['condition', c.condition]]);
            if (nb && nb.stations) {
                html += '<h2>' + (nb.ring === 0 ? 'stations in it, blended' : 'neighbours') + ' <span class="muted">' + (nb.ring === 0 ? 'inside the hexagon' : 'ring ' + nb.ring) + ' · ' + (nb.elevationApplied ? 'brought to ' + Math.round(nb.elevationM) + ' m at ' + nb.lapseTemperatureCPerKm + ' °C/km (dew point ' + nb.lapseDewPointCPerKm + ')' : 'not moved for height') + '</span></h2><table class="table table-sm"><thead><tr><th>station</th><th class="num">km</th><th class="num">height</th><th class="num">weight</th></tr></thead><tbody>';
                nb.stations.forEach(function (x) { html += '<tr><td>' + esc(x.id) + ' <span class="muted">' + esc(x.name || '') + '</span></td><td class="num">' + fmt(x.distanceKm, 1) + '</td><td class="num">' + fmt(x.heightM) + '</td><td class="num">' + fmt(x.weight, 2) + '</td></tr>'; });
                html += '</tbody></table>';
            }
            var dr = h.drift;
            if (dr) {
                html += '<h2>drift <span class="muted">stations − forecast at ' + when(dr.at) + '</span></h2>';
                html += kv([['score', dr.score + (dr.drifted ? ' · thrown out' : '') + (dr.worst ? ' (' + dr.worst + ')' : '')], ['temperature', dr.temperatureC != null ? dr.temperatureC + ' °C (tolerance 3)' : null],
                    ['humidity', dr.humidityPct != null ? dr.humidityPct + ' points (tolerance 20)' : null], ['wind', dr.windKmh != null ? dr.windKmh + ' km/h (tolerance 15)' : null],
                    ['rain since 9 am', dr.rainMm != null ? dr.rainMm + ' mm (tolerance 5)' : null], ['stations', dr.stationId], ['upstream', dr.upstream]]);
            }
            html += '<h2>fire</h2>';
            html += kv([['FFDI', f.ffdi != null ? f.ffdi + ' ' + f.ffdiRating + (f.peakFfdi != null ? ' (peak ' + f.peakFfdi + ')' : '') : null], ['drought factor', f.droughtFactor], ['KBDI', f.kbdiMm != null ? f.kbdiMm + ' mm ' + f.kbdiBand : null],
                ['GFDI', g.gfdi != null ? g.gfdi + ' ' + g.gfdiRating + ' (curing ' + g.curingPct + '%, ' + g.fuelLoadTHa + ' t/ha)' : (f.leads ? 'no curing figure' : null)], ['AFDRS grass', g.fbi != null ? 'FBI ' + g.fbi + ' ' + g.afdrsRating + ' · ' + g.rateOfSpreadKmh + ' km/h · ' + g.intensityKwm + ' kW/m' : null],
                ['official', o.rating ? o.rating + (o.fbi != null ? ' (FBI ' + o.fbi + ')' : '') + (o.totalFireBan ? ' · TOTAL FIRE BAN' : '') + ' · ' + o.district : null], ['leads', f.leads ? f.leads + (f.appliesToPct != null ? ' over ' + f.appliesToPct + '% of the hexagon' : '') : null],
                ['wind change', w.change ? when(w.change.at) + ' ' + w.change.fromDeg + '° → ' + w.change.toDeg + '° at ' + w.change.speedKmh + ' km/h' : null], ['fire weather warning', f.fireWeatherWarning ? 'YES' : null],
                ['VPD', f.vapourPressureDeficitKpa != null ? f.vapourPressureDeficitKpa + ' kPa' : null], ['mixing height', f.boundaryLayerHeightM != null ? f.boundaryLayerHeightM + ' m' : null]]);
            if (r.warnings && r.warnings.length) { html += '<h2>warnings</h2><ul class="small mb-1">'; r.warnings.forEach(function (x) { html += '<li>' + esc(x.title) + ' ' + esc(x.phenomena || '') + (x.headline ? ' — ' + esc(x.headline) : '') + ' <span class="muted">until ' + when(x.until) + '</span></li>'; }); html += '</ul>'; }
            if (d.kbdiMm != null) { html += '<h2>drought</h2>' + kv([['KBDI', d.kbdiMm + ' mm ' + d.kbdiBand], ['drought factor', d.droughtFactor], ['mean annual rain', d.meanAnnualRainfallMm + ' mm'], ['computed for', d.computedFor], ['spun up from', d.spunUpFrom + ' (' + d.days + ' days)'], ['inputs', h.drought && h.drought.from]]); }
            if (st.id) { html += '<h2>station ' + esc(st.id) + ' <span class="muted">' + esc(st.name) + (st.insideHexagon ? '' : ' · ' + fmt(st.distanceKm, 1) + ' km away') + '</span></h2>'; html += kv([['at', when(st.at)], ['temperature', st.temperatureC != null ? st.temperatureC + ' °C' : null], ['humidity', st.humidityPct != null ? st.humidityPct + ' %' : null], ['wind', st.windSpeedKmh != null ? st.windSpeedKmh + ' km/h ' + (st.windDirection || '') + ' gust ' + fmt(st.windGustKmh) : null], ['rain since 9am', st.rainSince9amMm != null ? st.rainSince9amMm + ' mm' : null], ['rain to 9am', st.rain24hMm != null ? st.rain24hMm + ' mm' : null], ['max / min', (st.maxTemperatureC != null || st.minTemperatureC != null) ? fmt(st.maxTemperatureC) + ' / ' + fmt(st.minTemperatureC) : null]]); }
            if (fl.rain1dMm != null || fl.forecastRain24hMm != null || fl.riverDischargeCumecs != null) { html += '<h2>flood</h2>' + kv([['rain 1/2/3/7 d', fmt(fl.rain1dMm) + ' / ' + fmt(fl.rain2dMm) + ' / ' + fmt(fl.rain3dMm) + ' / ' + fmt(fl.rain7dMm) + ' mm'], ['ahead 24/48/72 h', fmt(fl.forecastRain24hMm) + ' / ' + fmt(fl.forecastRain48hMm) + ' / ' + fmt(fl.forecastRain72hMm) + ' mm'], ['river', fl.riverDischargeCumecs != null ? fl.riverDischargeCumecs + ' m³/s, ' + fmt(fl.dischargeRatioToMean) + '× the 92-day mean, ' + fmt(fl.riverTrend) : null]]); }
            if (r.forecast && r.forecast.days && r.forecast.days.length) {
                html += '<h2>forecast · days ahead <span class="muted">' + esc((r.source || {}).upstream || '') + '</span></h2><table class="table table-sm"><thead><tr><th>day</th><th class="num">min/max</th><th class="num">RH</th><th class="num">wind</th><th class="num">rain</th><th>FFDI</th><th>FBI</th></tr></thead><tbody>';
                r.forecast.days.forEach(function (x) { var df = x.fire || {}; html += '<tr><td class="mono">' + esc(x.date) + '</td><td class="num">' + fmt(x.minTemperatureC) + '/' + fmt(x.maxTemperatureC) + '</td><td class="num">' + fmt(x.minHumidityPct) + '</td><td class="num">' + fmt(x.maxWindKmh) + '</td><td class="num">' + fmt(x.precipitationMm) + '</td><td>' + (df.ffdi != null ? df.ffdi + ' ' + esc(df.ffdiRating) : '—') + '</td><td>' + (df.fbi != null ? df.fbi + ' ' + esc(df.afdrsRating) : '—') + '</td></tr>'; });
                html += '</tbody></table>';
            }
            if (h.history && h.history.length) {
                html += '<h2>history <span class="muted">' + h.historyCount + ' snapshots</span></h2><table class="table table-sm"><thead><tr><th>at</th><th>ref</th><th class="num">°C</th><th class="num">RH</th><th class="num">wind</th><th>FFDI</th></tr></thead><tbody>';
                h.history.forEach(function (s) { var sc = s.current || {}, sf = s.fire || {}; html += '<tr><td class="mono">' + when(s.at) + '</td><td>' + esc(s.ref) + '</td><td class="num">' + fmt(sc.temperatureC) + '</td><td class="num">' + fmt(sc.humidityPct) + '</td><td class="num">' + fmt(sc.windSpeedKmh) + '</td><td>' + (sf.ffdi != null ? sf.ffdi + ' ' + esc(sf.ffdiRating) : '—') + '</td></tr>'; });
                html += '</tbody></table>';
            }
            if (h.ledger && h.ledger.length) {
                html += '<h2>station ledger</h2><table class="table table-sm"><thead><tr><th>at</th><th class="num">°C</th><th class="num">max</th><th class="num">rain 9am</th><th class="num">rain 24h</th></tr></thead><tbody>';
                h.ledger.forEach(function (s) { html += '<tr><td class="mono">' + when(s.at) + '</td><td class="num">' + fmt(s.temperature_c) + '</td><td class="num">' + fmt(s.max_temperature_c) + '</td><td class="num">' + fmt(s.rain_since_9am_mm) + '</td><td class="num">' + fmt(s.rain_24h_mm) + '</td></tr>'; });
                html += '</tbody></table>';
            }
            html += '<button class="btn btn-sm btn-outline-warning py-0" id="probe" type="button">probe (an ask: reads what is due, spends allowance)</button>';
            el.innerHTML = html;
            el.classList.remove('hidden');
            $('close').addEventListener('click', function () { el.classList.add('hidden'); });
            $('probe').addEventListener('click', function () { probe(h.lat, h.lon); });
        });
    }
    function probe(lat, lon) {
        var headers = window.gullyCsrf();
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        fetch('/console/map/probe', {method: 'POST', headers: headers, body: 'lat=' + lat + '&lon=' + lon})
            .then(function (r) { return r.json(); }).then(function (rd) { etag = null; load(); sourcesPanel(); if (rd.hexagon) detail(rd.hexagon.id); });
    }

    // ---- the time slider: hours back from now, the layer as it was
    function slid() {
        var h = Number($('time').value);
        if (h >= 0) { at = null; $('timeLabel').textContent = 'now'; }
        else { var d = new Date(Date.now() + h * 3600000); at = d.toISOString(); $('timeLabel').textContent = when(at); }
        load();
    }

    $('value').addEventListener('change', function () { value = $('value').value; legend(); hexLayer.setStyle(styleOf); glyphs(); });
    $('grid').addEventListener('change', grid);
    $('stations').addEventListener('change', stations);
    $('weather').addEventListener('change', glyphs);
    $('forecasts').addEventListener('change', draw);
    $('time').addEventListener('change', slid);
    $('now').addEventListener('click', function () { $('time').value = 0; slid(); });
    $('sourcesToggle').addEventListener('click', function () { var el = $('sources'); el.classList.toggle('hidden'); if (!el.classList.contains('hidden')) { el.innerHTML = '<p class="muted">reading…</p>'; sourcesPanel(); } });
    map.on('zoomend', glyphs);
    map.on('moveend', function () { if ($('grid').checked) grid(); });
    map.on('click', function (e) {
        var b = confirm('Probe ' + e.latlng.lat.toFixed(4) + ', ' + e.latlng.lng.toFixed(4) + '? This is an ask: it reads whatever is due for that state and spends allowance.');
        if (b) probe(e.latlng.lat, e.latlng.lng);
    });
    legend();
    load();
    stations();
    // Reloaded every minute; between reloads the amber keeps fading; the sources panel keeps up.
    setInterval(function () { if (!document.hidden && !at) load(); }, 60000);
    setInterval(function () { if (!document.hidden && (value === 'fc:life' || value === 'now:from' || side() === 'fc')) hexLayer.setStyle(styleOf); }, 20000);
    setInterval(function () { if (!document.hidden) sourcesPanel(); }, 30000);
})();
