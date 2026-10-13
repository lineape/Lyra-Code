/* Lyra circuit schematics, AGPL-3.0. Data-only SVG; no external assets or evaluated source. */
(function () {
  'use strict';
  var catalog = window.LyraCircuitSymbols.symbols;
  var types = Object.keys(catalog);
  var ns = 'http://www.w3.org/2000/svg';
  function check(condition, message) { if (!condition) throw new Error(message); }
  function object(value) { check(value && typeof value === 'object' && !Array.isArray(value), 'Expected a circuit object.'); }
  function fields(value, allowed) {
    object(value);
    Object.keys(value).forEach(function (key) { check(allowed.includes(key), 'Unsupported circuit property: ' + key); });
  }
  function number(value, name, min, max) {
    check(typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max,
      name + ' must be a finite number between ' + min + ' and ' + max + '.');
    return value;
  }
  function optional(value, key, fallback) { return Object.prototype.hasOwnProperty.call(value, key) ? value[key] : fallback; }
  function text(value, key, max) {
    if (Object.prototype.hasOwnProperty.call(value, key)) check(typeof value[key] === 'string' && value[key].length <= (max || 100), 'Invalid circuit text: ' + key);
  }
  function array(value, key, max, required) {
    var list = optional(value, key, []);
    check(Array.isArray(list) && list.length <= max && (!required || list.length > 0), 'Invalid circuit array: ' + key);
    return list;
  }
  function pinLayout(component) {
    var definition = catalog[component.type];
    if (definition.dynamic) {
      var w = optional(component, 'width', definition.size[0]), h = optional(component, 'height', definition.size[1]), result = Object.create(null);
      component.pins.forEach(function (pin) {
        var offset = optional(pin, 'offset', 0);
        result[pin.id] = pin.side === 'left' ? [-w / 2 - 20, offset] : pin.side === 'right' ? [w / 2 + 20, offset] :
          pin.side === 'top' ? [offset, -h / 2 - 20] : [offset, h / 2 + 20];
      });
      return result;
    }
    return definition.pins;
  }
  function transform(component, point) {
    var rotation = optional(component, 'rotation', 0), x = point[0], y = point[1];
    if (rotation === 90) { x = -point[1]; y = point[0]; }
    else if (rotation === 180) { x = -point[0]; y = -point[1]; }
    else if (rotation === 270) { x = point[1]; y = -point[0]; }
    return [component.x + x, component.y + y];
  }
  function validate(data) {
    fields(data, ['width', 'height', 'components', 'wires', 'nodes', 'junctions', 'labels']);
    var width = number(optional(data, 'width', 960), 'width', 160, 4096);
    var height = number(optional(data, 'height', 640), 'height', 160, 4096);
    var ids = new Set(), endpoints = Object.create(null);
    function point(value) {
      check(Array.isArray(value) && value.length === 2, 'A circuit point must be [x,y].');
      number(value[0], 'x', 0, width); number(value[1], 'y', 0, height);
    }
    function position(value) { point([value.x, value.y]); }
    function id(value) {
      check(typeof value.id === 'string' && /^[A-Za-z][A-Za-z0-9_-]{0,39}$/.test(value.id) && !ids.has(value.id), 'Invalid or duplicate circuit id: ' + value.id);
      ids.add(value.id);
    }
    array(data, 'components', 200, true).forEach(function (component) {
      fields(component, ['id', 'type', 'x', 'y', 'rotation', 'label', 'value', 'width', 'height', 'pins', 'mark']);
      id(component); check(types.includes(component.type), 'Unsupported circuit component: ' + component.type); position(component);
      check([0, 90, 180, 270].includes(optional(component, 'rotation', 0)), 'rotation must be 0, 90, 180 or 270.');
      text(component, 'label'); text(component, 'value');
      if (catalog[component.type].dynamic) {
        var size = catalog[component.type].size;
        var w = number(optional(component, 'width', size[0]), 'Module width', 80, 2400);
        var h = number(optional(component, 'height', size[1]), 'Module height', 80, 2400);
        text(component, 'mark', 40);
        var names = new Set(), positions = new Set();
        array(component, 'pins', 512, true).forEach(function (pin) {
          fields(pin, ['id', 'side', 'offset', 'label', 'number', 'invert', 'clock', 'overline']);
          check(typeof pin.id === 'string' && /^[A-Za-z0-9_+-]{1,40}$/.test(pin.id) && !names.has(pin.id), 'Invalid or duplicate module pin: ' + pin.id);
          names.add(pin.id); check(['left', 'right', 'top', 'bottom'].includes(pin.side), 'Invalid IC pin side.');
          var extent = (pin.side === 'left' || pin.side === 'right' ? h : w) / 2 - 12;
          var offset = number(optional(pin, 'offset', 0), 'pin offset', -extent, extent), key = pin.side + ':' + offset;
          check(!positions.has(key), 'IC pins must have distinct positions.'); positions.add(key);
          text(pin, 'label', 40); text(pin, 'number', 16);
          ['invert', 'clock', 'overline'].forEach(function (flag) {
            if (Object.prototype.hasOwnProperty.call(pin, flag)) check(typeof pin[flag] === 'boolean', flag + ' must be boolean.');
          });
        });
      } else {
        check(!['pins', 'width', 'height', 'mark'].some(function (key) { return Object.prototype.hasOwnProperty.call(component, key); }), 'Only ic/block/connector/converter accept custom pins, dimensions or mark.');
      }
      var pins = pinLayout(component);
      Object.keys(pins).forEach(function (name) { endpoints[component.id + '.' + name] = transform(component, pins[name]); });
    });
    array(data, 'nodes', 200).forEach(function (node) {
      fields(node, ['id', 'x', 'y', 'label']); id(node); position(node); text(node, 'label'); endpoints[node.id] = [node.x, node.y];
    });
    function endpoint(value) {
      if (typeof value === 'string') check(Object.prototype.hasOwnProperty.call(endpoints, value), 'Unknown circuit endpoint: ' + value);
      else point(value);
    }
    array(data, 'wires', 500).forEach(function (wire) {
      fields(wire, ['from', 'to', 'via']); endpoint(wire.from); endpoint(wire.to);
      array(wire, 'via', 32).forEach(point);
    });
    array(data, 'junctions', 500).forEach(point);
    array(data, 'labels', 100).forEach(function (label) {
      fields(label, ['x', 'y', 'text']); position(label); check(Object.prototype.hasOwnProperty.call(label, 'text'), 'Circuit label requires text.'); text(label, 'text', 200);
    });
    return { width: width, height: height, endpoints: endpoints };
  }
  function render(data, options) {
    var layout = validate(data), settings = options || {}, ink = settings.dark ? '#ece6f0' : '#1c1b1f';
    var background = settings.dark ? '#1c1b1f' : '#ffffff';
    function element(parent, tag, attributes) {
      var node = document.createElementNS(ns, tag);
      Object.keys(attributes || {}).forEach(function (key) { node.setAttribute(key, attributes[key]); });
      if (parent) parent.appendChild(node);
      return node;
    }
    var svg = element(null, 'svg', { xmlns: ns, viewBox: '0 0 ' + layout.width + ' ' + layout.height,
      width: layout.width, height: layout.height, role: 'img', 'aria-label': settings.title || 'Circuit schematic',
      fill: 'none', stroke: ink, 'stroke-width': 2, 'stroke-linecap': 'round', 'stroke-linejoin': 'round',
      'font-family': 'sans-serif', 'font-size': 14 });
    if (settings.title) element(svg, 'title').textContent = settings.title;
    var content = element(svg, 'g', { 'data-circuit-content': '' });
    function path(parent, d, fill) { return element(parent, 'path', { d: d, fill: fill || 'none' }); }
    function line(parent, x1, y1, x2, y2) { return element(parent, 'line', { x1: x1, y1: y1, x2: x2, y2: y2 }); }
    function label(parent, x, y, value, anchor, size) {
      var node = element(parent, 'text', { x: x, y: y, fill: ink, stroke: 'none', 'text-anchor': anchor || 'middle', 'font-size': size || 14 });
      node.textContent = value;
      return node;
    }
    function circle(parent, x, y, radius, fill) { return element(parent, 'circle', { cx: x, cy: y, r: radius, fill: fill || background }); }
    function arrow(parent, x1, y1, x2, y2) {
      line(parent, x1, y1, x2, y2);
      var angle = Math.atan2(y2 - y1, x2 - x1), cos = Math.cos(angle), sin = Math.sin(angle);
      path(parent, 'M' + x2 + ',' + y2 + ' L' + (x2 - 7 * cos + 3 * sin) + ',' + (y2 - 7 * sin - 3 * cos) +
        ' L' + (x2 - 7 * cos - 3 * sin) + ',' + (y2 - 7 * sin + 3 * cos) + ' Z', ink);
    }
    // Definitions come exclusively from the bundled, generated catalog. Model
    // documents can reference a type but cannot supply SVG tags or attributes.
    function drawCatalog(parent, entries, rotation) {
      entries.forEach(function (entry) {
        var attrs = {};
        Object.keys(entry.attrs).forEach(function (key) {
          attrs[key] = entry.attrs[key] === '@ink' ? ink : entry.attrs[key] === '@background' ? background : entry.attrs[key];
        });
        var child = element(parent, entry.tag, attrs);
        if (entry.text) child.textContent = entry.text;
        if (entry.upright) child.setAttribute('transform', 'rotate(' + (-rotation) + ' ' + attrs.x + ' ' + (Number(attrs.y) - 5) + ')');
        if (entry.children) drawCatalog(child, entry.children, rotation);
      });
    }
    function endpoint(value) { return typeof value === 'string' ? layout.endpoints[value] : value; }
    array(data, 'wires', 500).forEach(function (wire) {
      var points = [endpoint(wire.from)].concat(wire.via || [], [endpoint(wire.to)]), route = [points[0]];
      for (var i = 1; i < points.length; i++) {
        var previous = route[route.length - 1], next = points[i];
        // Every segment stays orthogonal. Explicit waypoints control branches and crossings.
        if (previous[0] !== next[0] && previous[1] !== next[1]) route.push([next[0], previous[1]]);
        route.push(next);
      }
      element(content, 'polyline', { points: route.map(function (p) { return p.join(','); }).join(' '), 'data-circuit-wire': '' });
    });
    array(data, 'components', 200, true).forEach(function (component) {
      var type = component.type, rotation = optional(component, 'rotation', 0), definition = catalog[type];
      var g = element(content, 'g', { transform: 'translate(' + component.x + ',' + component.y + ') rotate(' + rotation + ')', 'data-component': component.id });
      function leads(left, right) { line(g, -40, 0, left, 0); line(g, right, 0, 40, 0); }
      function resistor() { leads(-20, 20); element(g, 'rect', { x: -20, y: -8, width: 40, height: 16, fill: background }); }
      function diode() { leads(-12, 12); path(g, 'M-12,-12 L12,0 L-12,12 Z', background); line(g, 12, -12, 12, 12); }
      if (definition.body) drawCatalog(g, definition.body, rotation);
      else switch (type) {
        case 'resistor': resistor(); break;
        case 'photoresistor': resistor(); circle(g, 0, 0, 28, 'none'); arrow(g, -32, -35, -17, -20); arrow(g, -16, -39, -1, -24); break;
        case 'potentiometer': resistor(); arrow(g, 0, 40, 0, 9); break;
        case 'capacitor': case 'polarized_capacitor':
          leads(-6, 6); line(g, -6, -17, -6, 17);
          if (type === 'capacitor') line(g, 6, -17, 6, 17);
          else { path(g, 'M9,-17 Q1,0 9,17'); label(g, -17, -20, '+', 'middle', 16); }
          break;
        case 'inductor': leads(-24, 24); path(g, 'M-24,0 C-24,-20 -12,-20 -12,0 C-12,-20 0,-20 0,0 C0,-20 12,-20 12,0 C12,-20 24,-20 24,0'); break;
        case 'diode': case 'zener': case 'led': case 'photodiode':
          diode();
          if (type === 'zener') { line(g, 12, -12, 18, -16); line(g, 6, 16, 12, 12); }
          if (type === 'led') { arrow(g, -5, -22, 10, -37); arrow(g, 10, -18, 25, -33); }
          if (type === 'photodiode') { arrow(g, -24, -37, -9, -22); arrow(g, -9, -40, 6, -25); }
          break;
        case 'switch': leads(-18, 18); circle(g, -18, 0, 3); circle(g, 18, 0, 3); line(g, -15, -1, 15, -20); break;
        case 'battery': leads(-8, 8); line(g, -8, -20, -8, 20); line(g, 8, -10, 8, 10); label(g, -20, -23, '+'); break;
        case 'voltage_source': case 'ac_source':
          leads(-24, 24); circle(g, 0, 0, 24);
          if (type === 'ac_source') path(g, 'M-16,0 C-10,-16 -6,-16 0,0 C6,16 10,16 16,0');
          else {
            label(g, -11, 5, '+', 'middle', 18).setAttribute('transform', 'rotate(' + (-rotation) + ' -11 0)');
            label(g, 11, 5, '−', 'middle', 18).setAttribute('transform', 'rotate(' + (-rotation) + ' 11 0)');
          }
          break;
        case 'ground': line(g, 0, -40, 0, 0); line(g, -18, 0, 18, 0); line(g, -12, 6, 12, 6); line(g, -6, 12, 6, 12); break;
        case 'terminal': circle(g, 0, 0, 4); break;
        case 'lamp': leads(-22, 22); circle(g, 0, 0, 22); line(g, -15, -15, 15, 15); line(g, -15, 15, 15, -15); break;
        case 'fuse': resistor(); line(g, -20, 0, 20, 0); break;
        case 'npn': case 'pnp':
          circle(g, 0, 0, 28); line(g, -40, 0, -10, 0); line(g, -10, -15, -10, 15);
          path(g, 'M-10,-8 L20,-24 L20,-40 M-10,8 L20,24 L20,40');
          if (type === 'npn') arrow(g, 5, 16, 19, 24); else arrow(g, 19, 24, 5, 16);
          break;
        case 'nmos': case 'pmos':
          line(g, -40, 0, -14, 0); line(g, -14, -18, -14, 18);
          path(g, 'M-6,-18 L-6,-8 M-6,-4 L-6,4 M-6,8 L-6,18 M-6,-15 L20,-15 L20,-40 M-6,15 L20,15 L20,40 M20,15 L20,0');
          if (type === 'nmos') arrow(g, 20, 0, -5, 0); else arrow(g, -5, 0, 20, 0);
          break;
        case 'scr': diode(); path(g, 'M12,8 L20,20 L20,40'); break;
        case 'triac':
          leads(-16, 16);
          path(g, 'M-16,0 L-16,-22 L16,-11 Z M16,0 L16,22 L-16,11 Z', background);
          line(g, 16, -22, 16, 0); line(g, -16, 0, -16, 22); path(g, 'M16,13 L20,20 L20,40');
          break;
        case 'opamp':
          path(g, 'M-40,-35 L40,0 L-40,35 Z', background);
          line(g, -60, -20, -40, -20); line(g, -60, 20, -40, 20); line(g, 40, 0, 60, 0);
          line(g, 0, -40, 0, -17.5); line(g, 0, 17.5, 0, 40); label(g, -28, -15, '−'); label(g, -28, 25, '+');
          break;
        case 'ic': case 'block': case 'connector': case 'converter':
          var w = optional(component, 'width', definition.size[0]), h = optional(component, 'height', definition.size[1]), pins = pinLayout(component);
          element(g, 'rect', { x: -w / 2, y: -h / 2, width: w, height: h, fill: background });
          component.pins.forEach(function (pin) {
            var p = pins[pin.id], inner = pin.side === 'left' ? [p[0] + 20, p[1]] : pin.side === 'right' ? [p[0] - 20, p[1]] :
              pin.side === 'top' ? [p[0], p[1] + 20] : [p[0], p[1] - 20];
            var dx = (p[0] - inner[0]) / 20, dy = (p[1] - inner[1]) / 20;
            line(g, p[0], p[1], inner[0] + (pin.invert ? 8 * dx : 0), inner[1] + (pin.invert ? 8 * dy : 0));
            if (pin.invert) circle(g, inner[0] + 4 * dx, inner[1] + 4 * dy, 4);
            if (pin.clock) {
              var tx = -dy * 5, ty = dx * 5;
              path(g, 'M' + (inner[0] + tx) + ',' + (inner[1] + ty) + ' L' + (inner[0] - 8 * dx) + ',' + (inner[1] - 8 * dy) + ' L' + (inner[0] - tx) + ',' + (inner[1] - ty));
            }
            var pinLabel;
            if (pin.side === 'left' || pin.side === 'right') {
              var left = pin.side === 'left';
              if (pin.label) pinLabel = label(g, inner[0] + (left ? 6 : -6) + (pin.clock ? (left ? 8 : -8) : 0), p[1] + 4, pin.label, left ? 'start' : 'end', 12);
              if (pin.number) label(g, inner[0] + (left ? -10 : 10), p[1] - 6, pin.number, 'middle', 11);
            } else {
              var top = pin.side === 'top';
              if (pin.label) pinLabel = label(g, p[0], inner[1] + (top ? 16 : -8) + (pin.clock ? (top ? 8 : -8) : 0), pin.label, 'middle', 11);
              if (pin.number) label(g, p[0] + 7, inner[1] + (top ? -8 : 16), pin.number, 'start', 11);
            }
            if (pinLabel && pin.overline) pinLabel.setAttribute('text-decoration', 'overline');
          });
          if (type === 'converter') {
            line(g, -w / 2, h / 2, w / 2, -h / 2);
            var marks = optional(component, 'mark', '~ / =').split('/');
            label(g, -w / 4, -h / 4 + 5, marks[0].trim(), 'middle', 18);
            label(g, w / 4, h / 4 + 5, (marks[1] || '').trim(), 'middle', 18);
            label(g, 0, -h / 2 - 14, optional(component, 'label', component.id));
            if (component.value) label(g, 0, h / 2 + 24, component.value);
          } else if (type === 'connector') {
            label(g, 0, -h / 2 - 14, optional(component, 'label', component.id));
            if (component.value) label(g, 0, h / 2 + 24, component.value);
            if (component.mark) label(g, 0, 5, component.mark);
          } else {
            if (component.mark) label(g, 0, -h / 2 + 32, component.mark);
            label(g, 0, -8, optional(component, 'label', component.id), 'middle', 16);
            if (component.value) label(g, 0, 16, component.value, 'middle', 15);
          }
          break;
      }
      if (!definition.dynamic) {
        var vertical = rotation === 90 || rotation === 270, right = vertical || ['ground', 'terminal', 'npn', 'pnp', 'nmos', 'pmos'].includes(type);
        var extra = ['photoresistor', 'led', 'photodiode'].includes(type) ? 26 : 0;
        // Both caption lines stay above the tallest two-terminal symbol.
        var x = component.x + (right ? 36 + extra : 0), y = component.y + (right ? -9 : (component.value ? -56 : -32) - extra);
        if (type === 'opamp') { x = component.x; y = component.y + 62; right = false; }
        if (definition.bounds) {
          var b = definition.bounds, corners = [[b[0],b[1]],[b[0],b[3]],[b[2],b[1]],[b[2],b[3]]].map(function (p) { return transform(component,p); });
          x = component.x; y = Math.min.apply(null, corners.map(function (p) { return p[1]; })) - (component.value ? 34 : 16); right = false;
        }
        label(content, x, y, optional(component, 'label', component.id), right ? 'start' : 'middle');
        if (component.value) label(content, x, y + 18, component.value, right ? 'start' : 'middle');
      }
    });
    // Connections are explicit: crossings without a junction dot are not connections.
    array(data, 'nodes', 200).forEach(function (node) {
      circle(content, node.x, node.y, 3, ink);
      if (node.label) label(content, node.x + 8, node.y - 9, node.label, 'start');
    });
    array(data, 'junctions', 500).forEach(function (point) { circle(content, point[0], point[1], 3, ink); });
    array(data, 'labels', 100).forEach(function (entry) { label(content, entry.x, entry.y, entry.text, 'start'); });
    return svg;
  }
  // Call after insertion so the browser measures real glyphs, including Chinese labels.
  function fit(svg) {
    var box = svg.querySelector('[data-circuit-content]').getBBox(), padding = 24;
    var width = Math.max(1, box.width) + padding * 2, height = Math.max(1, box.height) + padding * 2;
    svg.setAttribute('viewBox', [box.x - padding, box.y - padding, width, height].join(' '));
    svg.setAttribute('width', width); svg.setAttribute('height', height);
  }
  window.LyraCircuit = { render: render, fit: fit, validate: validate };
})();
