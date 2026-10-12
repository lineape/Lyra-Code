'use strict';
// Drawing parameters mirror ui/chat/ReasoningDepthSelector.kt. Auto has no flow clock.
function createReasoningControl({commit, fail}) {
  const el = id => document.getElementById(id);
  const sheet = el('reasoning-dialog'), range = el('reasoning-range'), track = el('reasoning-track'), flow = el('reasoning-flow'), fill = el('reasoning-track-fill');
  const depths = ['auto','low','medium','high','xhigh','max'];
  const english = ['Auto','Low','Medium','High','XHigh','Max'];
  let snapshot, level = 0, dragging = false, pending = 0, queue = Promise.resolve(), optionSignature = '';
  let renderedLevel = -1;
  function labels() { return snapshot?.locale?.reasoningLabels || Object.fromEntries(depths.map((d,i) => [d,english[i]])); }
  function refreshLabels() {
    const names = labels(), bilingual = ['自动','自動'].includes(names.auto);
    const title = snapshot?.locale?.reasoningTitle || t('推理深度');
    el('reasoning-title').textContent = title;
    range.setAttribute('aria-label',title); el('reasoning-options').setAttribute('aria-label',title);
    el('reasoning-button').title = title; el('reasoning-button').setAttribute('aria-label',title);
    el('reasoning-label').textContent = names[snapshot?.reasoning] || english[0];
    el('reasoning-model').textContent = snapshot?.model || '';
    el('reasoning-hint').textContent = snapshot?.locale?.reasoningHint || '';
    const signature = JSON.stringify(names);
    if (signature !== optionSignature) { optionSignature = signature; el('reasoning-options').replaceChildren(...depths.map((d,i) => {
      const button = document.createElement('button'); button.type = 'button'; button.dataset.depth = d;
      button.textContent = names[d]; button.setAttribute('aria-pressed',String(level === i));
      if (bilingual) { const subtitle = document.createElement('small'); subtitle.textContent = english[i]; button.append(subtitle); }
      button.addEventListener('click',() => { preview(i); save(); }); return button;
    })); }
    [...el('reasoning-options').children].forEach((button,i) => button.setAttribute('aria-pressed',String(level === i)));
    el('reasoning-preview-label').textContent = bilingual ? `${names[depths[level]]} · ${english[level]}` : names[depths[level]];
    el('reasoning-strength-label').textContent = names[depths[level]];
    range.setAttribute('aria-valuetext',names[depths[level]]);
  }
  function preview(value) {
    const next = Math.max(0,Math.min(5,Math.round(value)));
    level = next;
    range.value = level; el('reasoning-level').textContent = level;
    el('reasoning-thumb').style.left = `calc((100% - 34px) * ${level/5})`;
    sheet.style.setProperty('--reasoning-power',String(level/5));
    [...el('reasoning-dots').children].forEach((dot,i) => dot.classList.toggle('lit',i < level));
    refreshLabels(); start();
  }
  function save() {
    const value = depths[level]; pending++;
    // A fast sequence of clicks must reach the phone in the same order.
    queue = queue.then(async () => { await commit(value); snapshot = {...snapshot,reasoning:value}; }).catch(fail).finally(() => {
      pending--; if (!pending && snapshot && !dragging) preview(depths.indexOf(snapshot.reasoning));
    });
  }
  function start() {
    const colors=snapshot?.theme?.colors || {};
    const rgb=(colors.surface || '#ffffff').slice(1).match(/../g).map(x=>parseInt(x,16)/255).map(x=>x<=.04045?x/12.92:((x+.055)/1.055)**2.4);
    const dark=rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722<.5;
    sheet.classList.toggle('dark-surface',dark);
    sheet.style.setProperty('--reasoning-accent',dark?'#9175ff':'#6d50d5');
    track.style.setProperty('--reasoning-variant',snapshot?.theme?.surfaceVariant || colors['surface-highest'] || '#ededeb');
    fill.style.width=`${level/5*100}%`;fill.style.opacity=String(.65+level/5*.35);
    if(renderedLevel!==level) {
      renderedLevel=level;flow.replaceChildren(...Array.from({length:level*4},(_,i)=>{
        const streak=document.createElement('span'),length=12+i%4*7+level/5*18,thickness=.8+i%3*.35;
        const duration=(5200-level*820)/(1+i%2);
        streak.className='reasoning-streak';streak.style.width=length+'px';streak.style.height=thickness+'px';
        streak.style.top=(.19+(i*.271828%1)*.62)*100+'%';streak.style.setProperty('--streak-length',length+'px');
        streak.style.animationDuration=duration+'ms';streak.style.animationDelay=(-(i*.618034%1)*duration)+'ms';return streak;
      }));
    }
    flow.style.setProperty('--flow-width',(track.clientWidth*level/5)+'px');
    for(const streak of flow.children)streak.style.animationPlayState=sheet.open && !document.hidden && snapshot?.motionEnabled!==false?'running':'paused';
    sheet.classList.toggle('no-motion',snapshot?.motionEnabled===false);
  }
  function stop() {for(const streak of flow.children)streak.style.animationPlayState='paused';}
  el('reasoning-button').addEventListener('click',() => { sheet.showModal();preview(depths.indexOf(snapshot?.reasoning)); });
  el('close-reasoning').addEventListener('click',() => sheet.close());sheet.addEventListener('close',() => {dragging=false;stop();});
  range.addEventListener('pointerdown',() => dragging=true);
  range.addEventListener('input',() => preview(Number(range.value)));
  range.addEventListener('change',() => { dragging=false;save(); });
  range.addEventListener('pointerup',() => dragging=false);range.addEventListener('pointercancel',() => {dragging=false;preview(depths.indexOf(snapshot.reasoning));});
  document.addEventListener('visibilitychange',() => document.hidden ? stop() : start());
  new ResizeObserver(start).observe(track);
  return {sync(next) { snapshot=next;if (!dragging && !pending) preview(depths.indexOf(next.reasoning));else refreshLabels(); }};
}
