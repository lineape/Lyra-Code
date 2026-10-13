'use strict';
function createStateStream({getToken,accept,unauthorized}) {
  let epoch=0, controller=null, connected=false, retryTimer=0, attempts=0, activeToken='';
  function stop() { epoch++; activeToken='';connected=false;clearTimeout(retryTimer);controller?.abort();controller=null; }
  async function connect(turn) {
    if (turn!==epoch || !activeToken || getToken()!==activeToken) return;
    controller=new AbortController();let watchdog;
    const alive=() => { clearTimeout(watchdog);watchdog=setTimeout(()=>controller?.abort(),25_000); };
    try {
      alive();
      const response=await fetch('/api/events',{headers:{Authorization:`Bearer ${activeToken}`},signal:controller.signal,cache:'no-store'});
      if (response.status===401) { unauthorized((await response.json()).error);stop();return; }
      if (!response.ok || !response.body || !response.headers.get('Content-Type')?.includes('text/event-stream')) throw new Error('Stream unavailable');
      const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='';
      while(turn===epoch) {
        const {value,done}=await reader.read();if(done)break;alive();
        buffer=(buffer+decoder.decode(value,{stream:true})).replace(/\r\n/g,'\n');if(buffer.length>32*1024*1024)throw new Error('Stream frame too large');
        let boundary;
        while((boundary=buffer.indexOf('\n\n'))>=0) {
          const event=buffer.slice(0,boundary);buffer=buffer.slice(boundary+2);
          const data=event.split('\n').filter(line=>line.startsWith('data:')).map(line=>line.slice(5).trimStart()).join('\n');
          if(data && turn===epoch && getToken()===activeToken) { accept(JSON.parse(data));connected=true;attempts=0; }
        }
      }
    } catch (_) { /* The regular state request remains available during reconnects. */ }
    finally {
      clearTimeout(watchdog);
      if(turn===epoch) {connected=false;retryTimer=setTimeout(()=>connect(turn),Math.min(6000,600*2**Math.min(attempts++,4)));}
    }
  }
  return {stop,get connected(){return connected;},start() {
    if(activeToken===getToken() && controller && !controller.signal.aborted)return;
    stop();activeToken=getToken();if(activeToken)connect(epoch);
  }};
}

/** A short, fixed catch-up deadline prevents a faster model building a playback queue. */
function createStreamProgress() {
  let shown='',target='',deadline=0,lastTick=0,initialized=false;
  return {
    get shown(){return shown;},get target(){return target;},get pending(){return shown!==target;},
    update(text,{animate=false,instant=false}={},now=performance.now()) {
      target=text;
      if(!initialized || instant || !animate || !target.startsWith(shown)) {shown=target;deadline=0;}
      else if(shown!==target && !deadline) {deadline=now+80;lastTick=now;}
      if(shown===target)deadline=0;
      initialized=true;
    },
    advance(now) {
      if(shown===target)return;
      const elapsed=Math.max(0,now-lastTick),remaining=Math.max(1,deadline-lastTick),backlog=target.length-shown.length;
      // Keep the phone's gentle typing cadence for small chunks; accelerate large ones.
      const count=Math.max(1,Math.floor(elapsed/12),Math.ceil(backlog*Math.min(1,elapsed/remaining)));
      let end=now>=deadline?target.length:Math.min(target.length,shown.length+count);
      if(end<target.length && /[\uD800-\uDBFF]/.test(target.charAt(end-1)) && /[\uDC00-\uDFFF]/.test(target.charAt(end)))end++;
      shown=target.slice(0,end);lastTick=now;if(shown===target)deadline=0;
    }
  };
}

/** Stable message nodes retain expanded reasoning, charts, selection and media playback. */
function createLiveMessages(element,rich) {
  const rows=new Map(),slots=new Set(),sourceHtml=new WeakMap();let sessionKey='',frame=0,follow=true;
  element.addEventListener('scroll',()=>follow=element.scrollHeight-element.scrollTop-element.clientHeight<110,{passive:true});
  const scroll=()=>{if(follow)element.scrollTop=element.scrollHeight;};
  function patch(parent,html,streaming) {
    const template=document.createElement('template');template.innerHTML=html;
    const next=[...template.content.childNodes];
    next.forEach((candidate,index)=>{
      const previous=parent.childNodes[index],source=candidate.nodeType===1 ? candidate.outerHTML : candidate.textContent;
      if(previous && sourceHtml.get(previous)===source)return;
      if(previous) {rich.release(previous);previous.replaceWith(candidate);} else parent.append(candidate);
      sourceHtml.set(candidate,source);if(candidate.nodeType===1)rich.hydrate(candidate,{streaming,onResize:scroll});
    });
    while(parent.childNodes.length>next.length) {const last=parent.lastChild;rich.release(last);last.remove();}
    scroll();
  }
  function paint(slot) {
    const text=slot.progress.shown,streaming=slot.streaming || slot.progress.pending;
    if(slot.painted===text && slot.richStreaming===streaming)return;
    slot.painted=text;slot.richStreaming=streaming;
    patch(slot.node,slot.render(text),streaming);
    slot.node.classList.toggle('stream-fade',streaming && slot.mode==='fade');
    if(!streaming)rich.hydrate(slot.node,{streaming:false,onResize:scroll});
  }
  function updateSlot(slot,text,streaming,mode,instant=false) {
    if(slot.progress.target===text && slot.streaming===streaming && slot.mode===mode && !instant)return;
    const animate=mode!=='fade' && (streaming || slot.streaming || slot.progress.pending);
    slot.progress.update(text,{animate,instant:instant || document.hidden});slot.streaming=streaming;slot.mode=mode;
    paint(slot);
    if(slot.progress.pending && !frame && !document.hidden)frame=requestAnimationFrame(tick);
  }
  function tick(now) {
    frame=0;let pending=false;
    for(const slot of slots) if(slot.progress.pending) {
      slot.progress.advance(now);paint(slot);pending ||= slot.progress.pending;
    }
    if(pending && !document.hidden)frame=requestAnimationFrame(tick);
  }
  document.addEventListener('visibilitychange',()=>{if(!document.hidden && !frame)frame=requestAnimationFrame(tick);});
  function reset() {cancelAnimationFrame(frame);frame=0;rich.release(element);rows.clear();slots.clear();element.replaceChildren();follow=true;}
  return {render(state) {
    const key=`${state.instanceId || ''}:${state.sessionRevision}:${state.conversationId}`;
    if(key!==sessionKey){reset();sessionKey=key;}
    if(!state.messages.length) {
      const html=state.loading ? `<div class="welcome">${esc(t('正在载入对话…'))}</div>` : `<div class="welcome"><div class="welcome-heading"><h1>${esc(t('让想法开始成形'))}</h1></div><p>${esc(t('与手机接续对话，直接附加电脑上的文件。'))}</p></div>`;
      if(element.innerHTML!==html){reset();element.innerHTML=html;}return;
    }
    if(!rows.size)element.replaceChildren();
    const existing=new Set();
    state.messages.forEach((message,index)=>{
      const id=String(message.id || `${message.role}-${index}`);existing.add(id);let row=rows.get(id);
      if(!row) {
        const article=document.createElement('article');article.className=`message ${message.role==='user'?'user':''}`;
        article.innerHTML='<div class="message-meta"></div><details class="message-thinking"><summary></summary><div class="thinking-body"></div></details><details class="message-input"><summary></summary><pre></pre></details><div class="body"></div>';
        row={article,header:'',body:{node:article.querySelector('.body'),render:rich.body,progress:createStreamProgress()},thinking:{node:article.querySelector('.thinking-body'),render:rich.markdown,progress:createStreamProgress()}};
        slots.add(row.body);slots.add(row.thinking);rows.set(id,row);element.append(article);
      }
      const header=`${interfaceLanguage}:${state.running}:${index}:${message.role}:${message.toolName}`;
      if(row.header!==header) {
        row.header=header;const role=message.role==='user'?t('你'):message.role==='tool'?`${t('工具 ·')} ${message.toolName || ''}`:message.role==='assistant'?'Lyra':t('上下文');
        row.article.querySelector('.message-meta').innerHTML=`<strong>${esc(role)}</strong><span class="message-controls"><button data-copy="${index}" class="quiet">${esc(t('复制'))}</button>${message.id && !state.running?`<button data-branch="${message.id}" class="quiet">${esc(t('分支'))}</button>`:''}${message.id && message.role==='user' && !state.running?`<button data-edit-message="${message.id}" class="quiet">${esc(t('编辑'))}</button>`:''}</span>`;
        row.article.querySelector('.message-thinking>summary').textContent=t('推理过程');
        row.article.querySelector('.message-input>summary').textContent=`${message.toolName || ''} · ${t('参数')}`;
      }
      row.article.querySelector('.message-thinking').hidden=!message.thinking;
      row.article.querySelector('.message-input').hidden=!message.toolInput;
      row.article.querySelector('.message-input>pre').textContent=message.toolInput || '';
      const streaming=(state.generating ?? state.running) && message.role==='assistant' && index===state.messages.length-1;
      row.article.classList.toggle('streaming',streaming);
      // Reasoning finishes before the first body chunk; do not animate both phases together.
      updateSlot(row.thinking,message.thinking || '',streaming && !message.content,state.streamingAnimationMode,!!message.content);
      if(message.role==='tool') {
        row.body.render=text=>`<details><summary>${esc(t('工具结果'))}</summary><pre>${esc(text)}</pre></details>`;
      }
      updateSlot(row.body,message.content || '',streaming,state.streamingAnimationMode);
      if(element.children[index]!==row.article)element.insertBefore(row.article,element.children[index] || null);
    });
    for(const [id,row] of rows)if(!existing.has(id)){rich.release(row.article);slots.delete(row.body);slots.delete(row.thinking);row.article.remove();rows.delete(id);}
    scroll();
  }};
}
