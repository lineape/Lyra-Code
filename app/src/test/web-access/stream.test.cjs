'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const context=vm.createContext({performance});
vm.runInContext(readFileSync(require('node:path').resolve(__dirname,'../../main/assets/web-access/stream.js'),'utf8'),context);
const progress=()=>vm.runInContext('createStreamProgress()',context);

test('sustained fast output cannot accumulate an unbounded playback queue',()=>{
  const view=progress(),text='快速模型输出。'.repeat(10000),received=[];
  view.update('',{},0);
  for(let now=4;now<=2000;now+=4) {
    const target=text.slice(0,now*25);received.push([now,target.length]);
    view.update(target,{animate:true},now);
    if(now%16===0) {
      view.advance(now);
      const due=received.filter(([time])=>time<=now-80).at(-1)?.[1] || 0;
      assert.ok(view.shown.length>=due,`Output is more than 80 ms behind at ${now} ms`);
      assert.ok(target.startsWith(view.shown));
    }
  }
  view.advance(2080);assert.equal(view.shown,view.target);assert.equal(view.pending,false);
});

test('new deltas do not push an existing catch-up deadline further out',()=>{
  const view=progress();view.update('',{},0);
  for(let now=0;now<=80;now+=16) {view.update('x'.repeat(100+now*500),{animate:true},now);view.advance(now);}
  assert.equal(view.shown,view.target);
});

test('completion settles pending text without forcing a large instantaneous flush',()=>{
  const view=progress();view.update('',{},0);view.update('x'.repeat(12000),{animate:true},0);view.advance(16);
  const before=view.shown;
  view.update('x'.repeat(14000),{animate:true},20);
  assert.equal(view.shown,before);
  view.advance(32);assert.ok(view.shown.length>before.length && view.pending);
  view.advance(80);assert.equal(view.shown.length,14000);assert.equal(view.pending,false);
});

test('reasoning can finish before body animation begins',()=>{
  const thinking=progress(),body=progress();thinking.update('',{},0);body.update('',{},0);
  thinking.update('推理'.repeat(6000),{animate:true},0);thinking.advance(16);assert.ok(thinking.pending);
  thinking.update(thinking.target,{animate:true,instant:true},20);
  body.update('正文'.repeat(6000),{animate:true},20);body.advance(36);
  assert.equal(thinking.pending,false);assert.equal(thinking.shown,thinking.target);assert.ok(body.pending);
});

test('history, fade mode and replaced content display promptly',()=>{
  const view=progress();view.update('历史'.repeat(6000),{},0);assert.equal(view.shown,view.target);
  view.update(view.target+'新增文本',{animate:false},10);assert.equal(view.shown,view.target);
  view.update('重新生成',{animate:true},20);assert.equal(view.shown,'重新生成');
});

test('a stalled or background browser resumes at the latest target',()=>{
  const view=progress();view.update('',{},0);view.update('x'.repeat(40000),{animate:true},0);
  view.advance(5000);assert.equal(view.shown,view.target);assert.equal(view.pending,false);
});

test('typing never splits emoji surrogate pairs',()=>{
  const view=progress();view.update('',{},0);view.update('😀🚀✨'.repeat(100),{animate:true},0);
  for(let now=1;now<=80;now++) {view.advance(now);assert.ok(!/[\uD800-\uDBFF]$/.test(view.shown));}
  assert.equal(view.shown,view.target);
});

const app=readFileSync(require('node:path').resolve(__dirname,'../../main/assets/web-access/app.js'),'utf8');
function functionSource(name) {
  const source=app.match(new RegExp(`function ${name}\\([^]*?\\n\\}`))?.[0];
  assert.ok(source,`Missing ${name}`);return source;
}
function footer(messages,language='zh-CN') {
  const nodes={},document={querySelectorAll:()=>[],documentElement:{}},context=vm.createContext({document,state:{messages},$:id=>nodes[id] ||= {}});
  vm.runInContext(readFileSync(require('node:path').resolve(__dirname,'../../main/assets/web-access/i18n.js'),'utf8'),context);
  context.setInterfaceLocale({language});vm.runInContext(functionSource('renderPerformance'),context);context.renderPerformance();return nodes;
}
test('footer uses native speed formatting and displays a legitimate zero-percent cache hit',()=>{
  const nodes=footer([{role:'assistant',tokensPerSecond:9.64,deepSeekCacheHitRate:0}]);
  assert.equal(nodes['tokens-per-second'].textContent,'9.6 tok/s');assert.equal(nodes['tokens-per-second'].hidden,false);
  assert.equal(nodes['cache-hit-rate'].textContent,'缓存命中率 0.0%');assert.equal(nodes['cache-hit-rate'].hidden,false);
});
test('footer statistics follow the phone language',()=>{
  const records=[{role:'assistant',tokensPerSecond:321.6,deepSeekCacheHitRate:75}];
  assert.equal(footer(records,'en')['cache-hit-rate'].textContent,'Cache hit rate 75.0%');
  assert.equal(footer(records,'zh-TW')['cache-hit-rate'].textContent,'快取命中率 75.0%');
  assert.equal(footer(records)['tokens-per-second'].textContent,'322 tok/s');
});
test('other providers and new turns do not show stale DeepSeek statistics',()=>{
  const old={role:'assistant',tokensPerSecond:100,deepSeekCacheHitRate:75};
  for(const records of [[{role:'assistant',tokensPerSecond:100,deepSeekCacheHitRate:null}],[old,{role:'user'}],[old,{role:'user'},{role:'assistant',tokensPerSecond:0,deepSeekCacheHitRate:null}]]) {
    assert.equal(footer(records)['cache-hit-rate'].hidden,true);
  }
  assert.equal(footer([old,{role:'user'}])['tokens-per-second'].hidden,true);
});
test('coalesced rendering preserves every changed record and rejects stale snapshots',()=>{
  const nodes={},frames=[],rendered=[];
  const context=vm.createContext({state:null,document:{hidden:false},stateRenderFrame:0,lastVersion:-1,serverInstance:'',connected:false,drafts:new Map(),
    $:id=>nodes[id] ||= {value:'',hidden:false,innerHTML:'',classList:{remove(){}}},applyLocale(){},resizeInput(){},t:text=>text,esc:text=>text,
    requestAnimationFrame:callback=>{frames.push(callback);return frames.length;},render:()=>rendered.push(context.state)});
  vm.runInContext(functionSource('scheduleStateRender')+'\n'+functionSource('acceptState'),context);
  context.acceptState({instanceId:'test',version:1,sessionRevision:1,messages:[{id:1,role:'user',content:'Question'}]});
  for(let version=2;version<102;version++)context.acceptState({partial:true,instanceId:'test',version,messages:[{id:2,role:'assistant',content:'x'.repeat(version)}],messageOrder:[1,2]});
  context.acceptState({partial:true,instanceId:'test',version:2,messages:[{id:2,content:'stale'}]});
  assert.equal(frames.length,1);assert.equal(rendered.length,0);frames[0]();
  assert.equal(rendered.length,1);assert.equal(rendered[0].messages[0].content,'Question');assert.equal(rendered[0].messages[1].content.length,101);
});
