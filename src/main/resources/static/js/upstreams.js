/* The upstreams page: the spend chart. One line per budgeted upstream from spend.json - the last 24
   hours by hour or the last 31 UTC days by day - on a hairline grid, a crosshair that reads every
   line at the bucket under the pointer, a red dot where a call failed, and the same figures as a
   table for whoever cannot hover. Colours are the page's CSS slots, so the theme swaps them. */
(function () {
    'use strict';
    var root = document.getElementById('spend');
    if (!root) return;
    var plot = document.getElementById('spendPlot');
    var legend = document.getElementById('spendLegend');
    var note = document.getElementById('spendNote');
    var tip = document.getElementById('spendTip');
    var tableBox = document.getElementById('spendTable');
    var SVG = 'http://www.w3.org/2000/svg';
    var data = null;
    var range = 'hours';

    function el(name, attrs, parent) {
        var e = document.createElementNS(SVG, name);
        for (var k in attrs) e.setAttribute(k, attrs[k]);
        if (parent) parent.appendChild(e);
        return e;
    }

    function html(name, cls, text, parent) {
        var e = document.createElement(name);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        if (parent) parent.appendChild(e);
        return e;
    }

    function fmt(n) { return Number(n).toLocaleString('en-AU', {maximumFractionDigits: 1}); }
    function pad(n) { return (n < 10 ? '0' : '') + n; }
    function plural(n, word) { return fmt(n) + ' ' + word + (n === 1 ? '' : 's'); }

    /* The bucket's short label on the axis, and its full name in the tooltip and the table. */
    function label(b) {
        if (range === 'hours') return pad(new Date(b.hour).getUTCHours()) + 'Z';
        var p = b.date.split('-');
        return p[2] + '/' + p[1];
    }

    function full(b) {
        if (range === 'hours') {
            var d = new Date(b.hour);
            return pad(d.getUTCDate()) + '/' + pad(d.getUTCMonth() + 1) + ' ' + pad(d.getUTCHours()) + ':00Z';
        }
        var p = b.date.split('-');
        return p[2] + '/' + p[1] + '/' + p[0];
    }

    /* Which buckets carry an axis label: the six-hour marks, or every fifth day counted back from today. */
    function labelled(b, i, n) {
        return range === 'hours' ? new Date(b.hour).getUTCHours() % 6 === 0 : (n - 1 - i) % 5 === 0;
    }

    function series() {
        return data.upstreams.map(function (u, i) { return {id: u.id, cls: 's' + (i + 1), rows: u[range] || []}; });
    }

    /* A round step - 1, 2 or 5 times a power of ten - at or above the raw one. */
    function niceStep(raw) {
        var p = Math.pow(10, Math.floor(Math.log10(raw)));
        var m = raw / p;
        return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 5 : 10) * p;
    }

    function draw() {
        if (!data) return;
        var ss = series();
        var n = ss.length ? ss[0].rows.length : 0;
        plot.textContent = '';
        tip.hidden = true;
        if (!n) {
            html('span', 'muted', 'No budgeted upstream.', plot);
            legend.textContent = '';
            return;
        }
        var W = plot.clientWidth || 600, H = plot.clientHeight || 220;
        var m = {l: 50, r: 16, t: 12, b: 22};
        var iw = W - m.l - m.r, ih = H - m.t - m.b;
        var max = 0;
        ss.forEach(function (s) { s.rows.forEach(function (r) { if (r.units > max) max = r.units; }); });
        var step = niceStep(Math.max(1, max) / 4);
        var top = Math.max(step, Math.ceil(max / step - 1e-9) * step);
        var x = function (i) { return m.l + (n === 1 ? iw / 2 : i * iw / (n - 1)); };
        var y = function (v) { return m.t + ih - v / top * ih; };
        var svg = el('svg', {viewBox: '0 0 ' + W + ' ' + H, width: W, height: H}, plot);

        for (var v = 0; v <= top + 1e-9; v += step) {
            el('line', {x1: m.l, x2: W - m.r, y1: y(v), y2: y(v), 'class': 'grid'}, svg);
            el('text', {x: m.l - 7, y: y(v) + 3.5, 'text-anchor': 'end'}, svg).textContent = fmt(v);
        }
        ss[0].rows.forEach(function (r, i) {
            if (labelled(r, i, n)) el('text', {x: x(i), y: H - 6, 'text-anchor': 'middle'}, svg).textContent = label(r);
        });
        ss.forEach(function (s) {
            var d = s.rows.map(function (r, i) { return (i ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(r.units).toFixed(1); }).join(' ');
            el('path', {d: d, 'class': 'line ' + s.cls}, svg);
        });
        ss.forEach(function (s) {
            s.rows.forEach(function (r, i) {
                if (r.failures > 0) el('circle', {cx: x(i), cy: y(r.units), r: 4, 'class': 'fail'}, svg);
            });
            el('circle', {cx: x(n - 1), cy: y(s.rows[n - 1].units), r: 3.5, 'class': 'end ' + s.cls}, svg);
        });

        var cross = el('line', {x1: 0, x2: 0, y1: m.t, y2: m.t + ih, 'class': 'cross', visibility: 'hidden'}, svg);
        var hit = el('rect', {x: m.l - 12, y: 0, width: iw + 24, height: H, 'class': 'hit'}, svg);
        hit.addEventListener('pointermove', function (e) {
            var box = svg.getBoundingClientRect();
            var px = (e.clientX - box.left) * W / box.width;
            var i = Math.max(0, Math.min(n - 1, Math.round((px - m.l) / iw * (n - 1))));
            cross.setAttribute('x1', x(i));
            cross.setAttribute('x2', x(i));
            cross.setAttribute('visibility', 'visible');
            showTip(ss, i, x(i) * box.width / W, e.clientY - box.top);
        });
        hit.addEventListener('pointerleave', function () {
            cross.setAttribute('visibility', 'hidden');
            tip.hidden = true;
        });

        note.textContent = range === 'hours' ? 'Units per hour, the last 24 hours, UTC.' : 'Units per UTC day, the last 31 days.';
        drawLegend(ss);
        drawTable(ss);
    }

    /* Every line at one bucket: the value first, the upstream after it. */
    function showTip(ss, i, px, py) {
        tip.textContent = '';
        html('div', 't', full(ss[0].rows[i]), tip);
        ss.forEach(function (s) {
            var r = s.rows[i];
            var row = html('div', null, null, tip);
            html('i', 'key ' + s.cls, null, row);
            html('b', null, fmt(r.units), row);
            row.appendChild(document.createTextNode(' units · ' + plural(r.calls, 'call')));
            if (r.failures > 0) html('span', 'text-danger', ' · ' + r.failures + ' failed', row);
            html('span', 'muted', ' ' + s.id, row);
        });
        tip.hidden = false;
        var left = px + 14;
        if (left + tip.offsetWidth > plot.clientWidth) left = px - tip.offsetWidth - 14;
        tip.style.left = (plot.offsetLeft + Math.max(0, left)) + 'px';
        tip.style.top = (plot.offsetTop + Math.max(0, py - 12)) + 'px';
    }

    /* Each upstream with its total over the range, and what the red dot means. */
    function drawLegend(ss) {
        legend.textContent = '';
        var anyFail = false;
        ss.forEach(function (s) {
            var units = 0, calls = 0, fails = 0;
            s.rows.forEach(function (r) { units += r.units; calls += r.calls; fails += r.failures; });
            anyFail = anyFail || fails > 0;
            var e = html('span', null, null, legend);
            html('i', 'key ' + s.cls, null, e);
            e.appendChild(document.createTextNode(s.id + ' '));
            html('span', 'muted', fmt(units) + ' units · ' + plural(calls, 'call') + (fails ? ' · ' + fails + ' failed' : ''), e);
        });
        if (anyFail) {
            var f = html('span', 'muted', null, legend);
            html('i', 'dot', null, f);
            f.appendChild(document.createTextNode('a failed call'));
        }
    }

    /* The same figures, newest first, for reading without a pointer. */
    function drawTable(ss) {
        tableBox.textContent = '';
        var t = html('table', 'table w-auto mt-1', null, tableBox);
        var hr = t.createTHead().insertRow();
        html('th', null, range === 'hours' ? 'hour, UTC' : 'day, UTC', hr);
        ss.forEach(function (s) { html('th', 'num', s.id + ': units · calls · failed', hr); });
        var body = t.createTBody();
        for (var i = ss[0].rows.length - 1; i >= 0; i--) {
            var row = body.insertRow();
            html('td', 'mono', full(ss[0].rows[i]), row);
            ss.forEach(function (s) {
                var r = s.rows[i];
                html('td', 'num', fmt(r.units) + ' · ' + r.calls + ' · ' + r.failures, row);
            });
        }
    }

    root.querySelectorAll('[data-range]').forEach(function (b) {
        b.addEventListener('click', function () {
            range = b.getAttribute('data-range');
            root.querySelectorAll('[data-range]').forEach(function (o) { o.classList.toggle('active', o === b); });
            draw();
        });
    });
    fetch(root.getAttribute('data-src'), {credentials: 'same-origin'})
        .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
        .then(function (d) { data = d; draw(); })
        .catch(function (e) { plot.textContent = ''; html('span', 'text-danger', 'The ledger did not answer: ' + e.message, plot); });
    if (window.ResizeObserver) {
        var width = plot.clientWidth;
        new ResizeObserver(function () {
            if (data && plot.clientWidth !== width) { width = plot.clientWidth; draw(); }
        }).observe(plot);
    }
})();
