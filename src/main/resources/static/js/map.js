// The map: the hexagon layer coloured by one value, the tessellation on request, the stations, a
// time slider over the history, and a click that opens everything held for a hexagon.
(function () {
    'use strict';
    var $ = function (id) { return document.getElementById(id); };
    var esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]; }); };
    var fmt = function (v, d) { return v == null ? '—' : (typeof v === 'number' ? (d == null ? v : v.toFixed(d)) : String(v)); };
    var when = function (iso) { if (!iso) return '—'; var d = new Date(iso); return isNaN(d) ? String(iso) : d.toLocaleString(undefined, {day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'}); };

    var map = L.map('map').setView([-34.93, 138.6], 7);
    window.gullyBaseLayer(map);
    var hexLayer = L.geoJSON(null, {style: styleOf, onEachFeature: onHexagon}).addTo(map);
    var gridLayer = L.geoJSON(null, {style: {color: '#888', weight: .6, fill: false, opacity: .6}, interactive: false});
    var stationLayer = L.layerGroup().addTo(map);
    var etag = null, value = 'ffdi', at = null, legendKeys = {};

    // ---- colouring: the published rating colours for the ratings, a ramp for a number
    var RATING = {'LOW-MODERATE': '#9bc466', 'HIGH': '#f7e463', 'VERY HIGH': '#f0a04b', 'SEVERE': '#e35d3c', 'EXTREME': '#c1272d', 'CATASTROPHIC': '#6d2077',
        'No Rating': '#dddddd', 'Moderate': '#7fc47f', 'High': '#f7e463', 'Extreme': '#f0a04b', 'Catastrophic': '#c1272d'};
    var KIND = {station: '#3b82f6', forecast: '#22c55e', both: '#a855f7', bare: '#9ca3af'};
    var LEADS = {forest: '#15803d', grass: '#ca8a04'};
    var RANGES = {temperatureC: [0, 45], humidityPct: [0, 100], windSpeedKmh: [0, 80], windGustKmh: [0, 110], ffdi: [0, 100], gfdi: [0, 150], fbi: [0, 100],
        droughtFactor: [0, 10], kbdiMm: [0, 203], curingPct: [0, 100], ageMinutes: [0, 180], elevationM: [0, 1500]};
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
    function colour(p) {
        var v = p[value];
        if (v == null) return null;
        if (value === 'ffdi' && p.ffdiRating) return RATING[p.ffdiRating] || ramp(v / 100);
        if (value === 'gfdi' && p.gfdiRating) return RATING[p.gfdiRating] || ramp(v / 150);
        if (value === 'fbi' && p.afdrsRating) return RATING[p.afdrsRating] || ramp(v / 100);
        if (value === 'officialRating') return RATING[v] || '#9ca3af';
        if (value === 'kind') return KIND[v] || '#9ca3af';
        if (value === 'leads') return LEADS[v] || '#9ca3af';
        var r = RANGES[value] || [0, 100];
        if (value === 'humidityPct') return ramp(1 - (v - r[0]) / (r[1] - r[0]));
        return ramp((v - r[0]) / (r[1] - r[0]));
    }
    function styleOf(f) {
        var p = f.properties, c = colour(p);
        return {color: p.warm ? '#111' : '#555', weight: p.active ? 1.2 : .7, dashArray: p.stale ? '4 3' : null,
            fillColor: c || '#000', fillOpacity: c ? (p.active ? .55 : .35) : .05};
    }
    function legend() {
        var el = $('legend'), html = '';
        if (value === 'ffdi' || value === 'gfdi') ['LOW-MODERATE', 'HIGH', 'VERY HIGH', 'SEVERE', 'EXTREME', 'CATASTROPHIC'].forEach(function (k) { html += '<i style="background:' + RATING[k] + '" title="' + k + '"></i>'; });
        else if (value === 'fbi' || value === 'officialRating') ['No Rating', 'Moderate', 'High', 'Extreme', 'Catastrophic'].forEach(function (k) { html += '<i style="background:' + RATING[k] + '" title="' + k + '"></i>'; });
        else if (value === 'kind') Object.keys(KIND).forEach(function (k) { html += '<i style="background:' + KIND[k] + '" title="' + k + '"></i><span class="muted me-1">' + k + '</span>'; });
        else if (value === 'leads') Object.keys(LEADS).forEach(function (k) { html += '<i style="background:' + LEADS[k] + '" title="' + k + '"></i><span class="muted me-1">' + k + '</span>'; });
        else { var r = RANGES[value] || [0, 100]; html = '<span class="muted">' + r[0] + '</span>'; for (var i = 0; i <= 8; i++) html += '<i style="background:' + ramp(i / 8) + '"></i>'; html += '<span class="muted">' + r[1] + '</span>'; }
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
            hexLayer.clearLayers();
            hexLayer.addData(fc);
            var m = fc.meta || {};
            $('status').textContent = (m.hexagons || 0) + ' hexagons' + (at ? ' at ' + when(at) : '') + (m.active != null ? ' · ' + m.active + ' active · ' + m.withStation + ' with a station · ' + m.withForecast + ' with a forecast' : '');
        }).catch(function (e) { $('status').textContent = 'layer failed: ' + e; });
    }
    function onHexagon(f, layer) {
        var p = f.properties;
        layer.bindTooltip(function () {
            return '<b>' + esc(p.id) + '</b> ' + esc(p.kind) + '<br>' + esc(value) + ': ' + esc(fmt(p[value], 1)) + (p.at ? '<br>' + when(p.at) + ' (' + esc(p.from) + ')' : '');
        }, {sticky: true});
        layer.on('click', function (e) { L.DomEvent.stopPropagation(e); detail(p.id); });
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
                L.circleMarker(ll, {radius: 3, color: '#3b82f6', weight: 1, fillColor: '#3b82f6', fillOpacity: .9})
                    .bindTooltip(function () {
                        return '<b>' + esc(p.name) + '</b> ' + esc(p.id) + '<br>' + esc(fmt(p.temperatureC, 1)) + ' °C · ' + esc(fmt(p.humidityPct)) + ' % · ' + esc(fmt(p.windSpeedKmh)) + ' km/h'
                            + (p.windDirectionDeg != null ? ' from ' + p.windDirectionDeg + '°' : '') + (p.windGustKmh != null ? ' gust ' + p.windGustKmh : '') + '<br>' + when(p.at) + ' · ' + esc(p.district);
                    }, {sticky: true})
                    .on('click', function (e) { L.DomEvent.stopPropagation(e); detail(p.hexagon); })
                    .addTo(stationLayer);
            });
        });
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
            var r = h.reading || {}, c = r.current || {}, f = r.fire || {}, g = f.grass || {}, o = f.official || {}, w = f.wind || {}, d = r.drought || {}, st = r.station || {}, fl = r.flood || {};
            var html = '<div class="d-flex justify-content-between align-items-start"><h3>' + esc(h.id) + ' <span class="muted">' + esc(h.kind) + '</span></h3><button class="btn btn-sm btn-outline-secondary py-0" id="close" type="button">×</button></div>';
            html += kv([['centre', fmt(h.lat, 4) + ', ' + fmt(h.lon, 4)], ['zone', h.zone], ['elevation', h.elevationM != null ? h.elevationM + ' m (' + h.elevationFrom + ')' : null], ['slope', h.slopeDeg != null ? h.slopeDeg + '°' : null],
                ['land use', h.landUse ? Object.keys(h.landUse).map(function (k) { return k + ' ' + h.landUse[k] + '%'; }).join(', ') + (h.leads ? ' → ' + h.leads : '') : null],
                ['fire ban district', h.fireBanDistrict], ['bureau district', h.bureauDistrict], ['station in hexagon', h.stationId], ['nearest station', h.nearestStationId ? h.nearestStationId + ' at ' + fmt(h.nearestStationKm, 1) + ' km' : null],
                ['upstream', h.upstream], ['refreshed', when(h.refreshedAt)], ['expires', when(h.expiresAt)], ['activated', when(h.activatedAt)], ['last asked', when(h.lastAskedAt)], ['asks this run', h.asks], ['snapshots', h.historyCount]]);
            if (r.available === false) html += '<p class="text-warning">' + esc(r.unavailable) + '</p>';
            html += '<h2>now <span class="muted">' + esc(r.currentFrom || '') + ' · ' + when(r.at) + '</span></h2>';
            html += kv([['temperature', c.temperatureC != null ? c.temperatureC + ' °C' + (c.apparentTemperatureC != null ? ' (feels ' + c.apparentTemperatureC + ')' : '') : null], ['humidity', c.humidityPct != null ? c.humidityPct + ' %' : null], ['dew point', c.dewPointC != null ? c.dewPointC + ' °C' : null],
                ['wind', c.windSpeedKmh != null ? c.windSpeedKmh + ' km/h from ' + fmt(c.windDirectionDeg) + '° gust ' + fmt(c.windGustKmh) : null], ['pressure', c.pressureMslHpa != null ? c.pressureMslHpa + ' hPa' : null], ['rain', c.precipitationMm != null ? c.precipitationMm + ' mm' : null], ['condition', c.condition]]);
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
                html += '<h2>days ahead</h2><table class="table table-sm"><thead><tr><th>day</th><th class="num">min/max</th><th class="num">RH</th><th class="num">wind</th><th class="num">rain</th><th>FFDI</th><th>FBI</th></tr></thead><tbody>';
                r.forecast.days.forEach(function (x) { var df = x.fire || {}; html += '<tr><td class="mono">' + esc(x.date) + '</td><td class="num">' + fmt(x.minTemperatureC) + '/' + fmt(x.maxTemperatureC) + '</td><td class="num">' + fmt(x.minHumidityPct) + '</td><td class="num">' + fmt(x.maxWindKmh) + '</td><td class="num">' + fmt(x.precipitationMm) + '</td><td>' + (df.ffdi != null ? df.ffdi + ' ' + esc(df.ffdiRating) : '—') + '</td><td>' + (df.fbi != null ? df.fbi + ' ' + esc(df.afdrsRating) : '—') + '</td></tr>'; });
                html += '</tbody></table>';
            }
            if (h.history && h.history.length) {
                html += '<h2>history <span class="muted">' + h.historyCount + ' snapshots</span></h2><table class="table table-sm"><thead><tr><th>at</th><th>incident</th><th class="num">°C</th><th class="num">RH</th><th class="num">wind</th><th>FFDI</th></tr></thead><tbody>';
                h.history.forEach(function (s) { var sc = s.current || {}, sf = s.fire || {}; html += '<tr><td class="mono">' + when(s.at) + '</td><td>' + esc(s.incident) + '</td><td class="num">' + fmt(sc.temperatureC) + '</td><td class="num">' + fmt(sc.humidityPct) + '</td><td class="num">' + fmt(sc.windSpeedKmh) + '</td><td>' + (sf.ffdi != null ? sf.ffdi + ' ' + esc(sf.ffdiRating) : '—') + '</td></tr>'; });
                html += '</tbody></table>';
            }
            if (h.ledger && h.ledger.length) {
                html += '<h2>station ledger</h2><table class="table table-sm"><thead><tr><th>at</th><th class="num">°C</th><th class="num">max</th><th class="num">rain 9am</th><th class="num">rain 24h</th></tr></thead><tbody>';
                h.ledger.forEach(function (s) { html += '<tr><td class="mono">' + when(s.at) + '</td><td class="num">' + fmt(s.temperature_c) + '</td><td class="num">' + fmt(s.max_temperature_c) + '</td><td class="num">' + fmt(s.rain_since_9am_mm) + '</td><td class="num">' + fmt(s.rain_24h_mm) + '</td></tr>'; });
                html += '</tbody></table>';
            }
            html += '<button class="btn btn-sm btn-outline-warning py-0" id="probe" type="button">probe (spends allowance)</button>';
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
            .then(function (r) { return r.json(); }).then(function (rd) { etag = null; load(); if (rd.hexagon) detail(rd.hexagon.id); });
    }

    // ---- the time slider: hours back from now, the layer as it was
    function slid() {
        var h = Number($('time').value);
        if (h >= 0) { at = null; $('timeLabel').textContent = 'now'; }
        else { var d = new Date(Date.now() + h * 3600000); at = d.toISOString(); $('timeLabel').textContent = when(at); }
        load();
    }

    $('value').addEventListener('change', function () { value = $('value').value; legend(); hexLayer.setStyle(styleOf); });
    $('grid').addEventListener('change', grid);
    $('stations').addEventListener('change', stations);
    $('time').addEventListener('change', slid);
    $('now').addEventListener('click', function () { $('time').value = 0; slid(); });
    map.on('moveend', function () { if ($('grid').checked) grid(); });
    map.on('click', function (e) {
        var b = confirm('Probe ' + e.latlng.lat.toFixed(4) + ', ' + e.latlng.lng.toFixed(4) + '? This asks for the hexagon and spends allowance.');
        if (b) probe(e.latlng.lat, e.latlng.lng);
    });
    legend();
    load();
    stations();
    setInterval(function () { if (!document.hidden && !at) load(); }, 60000);
})();
