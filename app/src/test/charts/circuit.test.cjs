'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

// A minimal SVG DOM records exactly what the renderer constructs. Device tests
// cover actual font bounds, WebView layout and PNG/SVG exports.
class Element {
  constructor(tag){this.tagName=tag;this.attributes={};this.children=[];this.textContent='';}
  setAttribute(key,value){this.attributes[key]=String(value);}
  appendChild(child){this.children.push(child);return child;}
}
const context=vm.createContext({window:{},document:{createElementNS:(ns,tag)=>{
  assert.equal(ns,'http://www.w3.org/2000/svg');return new Element(tag);
}}});
vm.runInContext(fs.readFileSync(path.resolve(__dirname,'../../main/assets/charts/circuit-symbols.js'),'utf8'),context);
vm.runInContext(fs.readFileSync(path.resolve(__dirname,'../../main/assets/charts/circuit.js'),'utf8'),context);
const circuit=context.window.LyraCircuit;
const load=()=>JSON.parse(fs.readFileSync(path.resolve(__dirname,'../../androidTest/assets/charts/ne555-cd4017.json'),'utf8'));
const walk=node=>[node,...node.children.flatMap(walk)];
const sample=()=>({components:[{id:'R1',type:'resistor',x:100,y:100}]});

test('NE555/CD4017 reference preserves every symbol and label in both themes',()=>{
  for(const dark of [false,true]) {
    const source=load(),svg=circuit.render(source,{dark,title:'中文电路图'}),nodes=walk(svg);
    assert.equal(nodes.filter(n=>n.attributes['data-component']).length,source.components.length);
    assert.equal(nodes.filter(n=>Object.hasOwn(n.attributes,'data-circuit-wire')).length,source.wires.length);
    for(const text of ['NE555','CD4017','MG45','220μF','负载','~220V'])assert.ok(nodes.some(n=>n.textContent===text),text);
    assert.equal(svg.attributes.stroke,dark?'#ece6f0':'#1c1b1f');
    assert.equal(svg.attributes['aria-label'],'中文电路图');
  }
});

test('rotated pins, IC side offsets, named nodes and waypoints resolve to orthogonal wires',()=>{
  const source={components:[{id:'R1',type:'resistor',x:100,y:100,rotation:90},
    {id:'U1',type:'ic',x:300,y:200,width:120,height:160,pins:[{id:'8',side:'top',offset:-20}]}],
    nodes:[{id:'VCC',x:100,y:40}],wires:[{from:'VCC',to:'R1.a'},
      {from:'R1.b',to:'U1.8',via:[[200,140],[200,100]]}]};
  const svg=circuit.render(source),wires=walk(svg).filter(n=>n.tagName==='polyline');
  assert.equal(wires[0].attributes.points,'100,40 100,60');
  assert.equal(wires[1].attributes.points,'100,140 200,140 200,100 280,100');
  for(const wire of wires) {
    const points=wire.attributes.points.split(' ').map(p=>p.split(',').map(Number));
    points.forEach((p,i)=>assert.ok(!i || p[0]===points[i-1][0] || p[1]===points[i-1][1]));
  }
});

test('crossings acquire dots only at explicitly named nodes or junctions',()=>{
  const source={...sample(),wires:[{from:[200,100],to:[400,100]},{from:[300,50],to:[300,150]}]};
  const dots=svg=>walk(svg).filter(n=>n.tagName==='circle');
  assert.equal(dots(circuit.render(source)).length,0);
  source.junctions=[[300,100]];
  const dot=dots(circuit.render(source));assert.equal(dot.length,1);assert.equal(dot[0].attributes.cx,'300');assert.equal(dot[0].attributes.cy,'100');
});

test('two-line horizontal captions clear symbols and source polarity stays upright after rotation',()=>{
  const source={components:[{id:'C1',type:'capacitor',x:200,y:200,value:'1μF'},
    {id:'V1',type:'voltage_source',x:400,y:200,rotation:90}]};
  const nodes=walk(circuit.render(source)),value=nodes.find(n=>n.tagName==='text' && n.textContent==='1μF');
  assert.ok(Number(value.attributes.y)<200-17-10,'Capacitor caption overlaps its plates');
  const minus=nodes.find(n=>n.tagName==='text' && n.textContent==='−');
  assert.equal(minus.attributes.transform,'rotate(-90 11 0)');
});

test('untrusted markup stays in SVG text nodes and cannot create executable attributes',()=>{
  const payload='<script>window.pwned=true</script><image href="https://example.invalid">';
  const source=sample();source.components[0].label=payload;
  const nodes=walk(circuit.render(source,{title:payload}));
  assert.ok(nodes.some(n=>n.tagName==='text' && n.textContent===payload));
  assert.ok(nodes.every(n=>!['script','foreignObject','image','a'].includes(n.tagName)));
  assert.ok(nodes.every(n=>Object.keys(n.attributes).every(k=>!/^on|href/i.test(k))));
});

test('bad endpoints, pin collisions, geometry and unsupported properties fail before drawing',()=>{
  const invalid=[
    {...sample(),wires:[{from:'R1.c',to:[200,100]}]},
    {...sample(),wires:[{from:'R1.a',to:[Infinity,100]}]},
    {...sample(),wires:[{from:'R1.a',to:[200,100],via:[[1]]}]},
    {...sample(),width:null},
    {...sample(),nodes:[{id:'R1',x:100,y:100}]},
    {...sample(),components:[{...sample().components[0],x:'100'}]},
    {...sample(),components:[{...sample().components[0],x:NaN}]},
    {...sample(),components:[{...sample().components[0],rotation:45}]},
    {...sample(),components:[{...sample().components[0],onclick:'evil()'}]},
    {...sample(),components:[{...sample().components[0],type:'custom'}]},
    {...sample(),components:[{...sample().components[0],pins:[]}]},
    {components:[{id:'U1',type:'ic',x:100,y:100,pins:[{id:'1',side:'left'},{id:'2',side:'left'}]}]},
    {components:[{id:'U1',type:'ic',x:100,y:100,pins:[{id:1,side:'left'}]}]},
    {components:[]},
    {...sample(),components:Array.from({length:201},(_,i)=>({...sample().components[0],id:`R${i}`}))},
    JSON.parse('{"__proto__":{},"components":[{"id":"R1","type":"resistor","x":100,"y":100}]}')
  ];
  for(const source of invalid)assert.throws(()=>circuit.render(source),undefined,JSON.stringify(source));
});

test('every library symbol draws with every supported rotation and all its pins connect',()=>{
  const definitions=context.window.LyraCircuitSymbols.symbols;
  assert.ok(Object.keys(definitions).length>=120);
  for(const [type,definition] of Object.entries(definitions))for(const rotation of [0,90,180,270]) {
    const component={id:'X1',type,x:200,y:200,rotation};
    if(definition.dynamic)component.pins=[{id:'1',side:'left',label:'IN',number:'1',clock:true,invert:true,overline:true}];
    const names=definition.dynamic?['1']:Object.keys(definition.pins);
    const svg=circuit.render({components:[component],wires:names.map(name=>({from:`X1.${name}`,to:[100,100]}))});
    assert.ok(walk(svg).some(n=>n.attributes['data-component']==='X1'));
    assert.equal(walk(svg).filter(n=>Object.hasOwn(n.attributes,'data-circuit-wire')).length,names.length);
  }
});

test('native and browser snapshots contain identical symbols and pin geometry',()=>{
  const native=JSON.parse(fs.readFileSync(path.resolve(__dirname,'../../main/resources/charts/circuit-symbols.json'),'utf8'));
  assert.deepEqual(JSON.parse(JSON.stringify(context.window.LyraCircuitSymbols)),native);
  for(const type of ['variable_resistor','motor','three_phase_motor','ammeter','voltmeter','transformer','autotransformer','block','converter','adc','dac','and_gate','counter'])assert.ok(native.symbols[type],type);
  assert.deepEqual(native.symbols.three_phase_motor.pins,{u:[-50,-16],v:[-50,0],w:[-50,16],pe:[0,40]});
  const checkText=entry=>{
    if(['text','tspan'].includes(entry.tag))assert.equal(entry.attrs.stroke,'none','Text must not inherit the circuit root stroke');
    (entry.children||[]).forEach(checkText);
  };
  Object.values(native.symbols).forEach(symbol=>(symbol.body||[]).forEach(checkText));
});

test('custom high-pin-count ICs and functional modules retain clock and active-low markers',()=>{
  for(const type of ['ic','block','connector','converter']) {
    const source={width:2400,height:2400,components:[{id:'U1',type,x:1200,y:1200,width:1200,height:1200,mark:type==='converter'?'= / ~':'MCU',
      pins:Array.from({length:512},(_,i)=>({id:`P${i}`,side:['left','right','top','bottom'][Math.floor(i/128)],offset:-508+(i%128)*8,
        label:`P${i}`,clock:i===0,invert:i===1,overline:i===1}))}],wires:[{from:'U1.P511',to:[200,200]}]};
    const nodes=walk(circuit.render(source));
    assert.ok(nodes.some(n=>n.attributes['text-decoration']==='overline'));
    assert.ok(nodes.some(n=>n.tagName==='circle' && n.attributes.r==='4'));
    const bad=JSON.parse(JSON.stringify(source));bad.components[0].pins[0].clock='true';assert.throws(()=>circuit.render(bad));
    bad.components[0].pins[0].clock=true;bad.components[0].pins.push({...bad.components[0].pins[0],id:'EXTRA'});assert.throws(()=>circuit.render(bad));
  }
});
