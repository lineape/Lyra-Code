'use strict';
function createRichContent() {
  let media=new Map(),chartCounter=0,mermaidQueue=Promise.resolve(),visual=null;
  const charts=new Map();
  const formula=(text,display)=>`<span class="lyra-math ${display?'display-math':''}" data-formula="${encodeURIComponent(text)}" data-display="${display}"></span>`;
  marked.use({extensions:[
    {name:'mathBlock',level:'block',start:src=>src.search(/\$\$|\\\[/),tokenizer(src){const m=/^(?:\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\])(?:\s*\n|$)/.exec(src);if(m)return {type:'mathBlock',raw:m[0],text:m[1] || m[2]};},renderer:token=>`<div class="math-block">${formula(token.text,true)}</div>`},
    {name:'mathInline',level:'inline',start:src=>src.search(/\$|\\\(/),tokenizer(src){const m=/^(?:\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\\\(([\s\S]+?)\\\)|\$([^\s$](?:[^$]*?[^\s$])?)\$(?!\d))/.exec(src);if(m)return {type:'mathInline',raw:m[0],text:m[1] || m[2] || m[3] || m[4],display:!!(m[1] || m[2])};},renderer:token=>formula(token.text,token.display)}
  ]});
  function markdown(text) {
    return DOMPurify.sanitize(marked.parse(text || '',{breaks:true}),{
      FORBID_TAGS:['script','form','input','button','textarea','select','iframe','object','embed','style','svg','math'],
      FORBID_ATTR:['style','id','name','autoplay','data-source','data-rendered','data-media','data-media-url','data-chart-rendered','data-media-link','data-visual','data-chart-source','data-chart-save','data-i18n','data-i18n-title','data-i18n-placeholder','data-i18n-aria-label'],
      ALLOWED_URI_REGEXP:/^(?:(?:https?|data|blob|file|content|proot):|\/|#|[^:/?#]*(?:[/?#]|$))/i,
      ADD_TAGS:['video','audio','source'],ADD_ATTR:['controls','preload','target']
    });
  }
  function kind(source,mime='') {
    const extension=source.split(/[?#]/)[0].split('.').pop().toLowerCase();
    if(mime.startsWith('image/') || ['png','jpg','jpeg','gif','webp','svg','avif'].includes(extension))return 'image';
    if(mime.startsWith('video/') || ['mp4','webm','mov','m4v'].includes(extension))return 'video';
    if(mime.startsWith('audio/') || ['mp3','wav','ogg','m4a','aac','flac'].includes(extension))return 'audio';
    return 'file';
  }
  function resource(source) {
    let decoded=source;try{decoded=decodeURI(source);}catch{}
    const local=media.get(source) || media.get(decoded);
    if(local)return `/api/media?id=${encodeURIComponent(local.id)}`;
    if(/^https?:\/\//i.test(source))return source;
    if(/^data:(?:image\/(?:png|jpeg|gif|webp|svg\+xml|avif)|audio\/[^;,]+|video\/[^;,]+)[;,]/i.test(source))return source;
    return '';
  }
  function mediaCard(source,name,mime='',size=0) {
    const type=kind(source,mime),tag=type==='image'?'img':type;
    return `<figure class="media-card" data-media="${encodeURIComponent(source)}"><div class="media-preview">${type==='file'?'':type==='image'?`<img data-source="${encodeURIComponent(source)}" alt="${esc(name)}" loading="lazy" referrerpolicy="no-referrer">`:`<${tag} data-source="${encodeURIComponent(source)}" controls preload="metadata"></${tag}>`}</div><figcaption><span>${esc(name)}${size?` · ${(size/1024).toFixed(1)} KB`:''}</span><a data-media-link="${encodeURIComponent(source)}" download="${esc(name)}">${esc(t('下载文件'))}</a>${type==='file'?'':`<button class="quiet" data-visual>${esc(t('放大预览'))}</button>`}</figcaption><small class="media-error" hidden></small></figure>`;
  }
  function body(text) {
    const attachments=[];
    const clean=text.replace(/<lyra_attachment_v1>([\s\S]*?)<\/lyra_attachment_v1>/g,(_,raw)=>{try{attachments.push(JSON.parse(raw));}catch{}return '';})
      .replace(/<lyra_(?:workspace_refs|editor_context)_v1>[\s\S]*?<\/lyra_(?:workspace_refs|editor_context)_v1>/g,'');
    return markdown(clean)+attachments.map(file=>mediaCard(file.uri || file.data_url || '',file.name || t('媒体文件'),file.mime_type || '',Number(file.size))).join('');
  }
  function descendants(root,selector) { return [...(root.matches?.(selector)?[root]:[]),...root.querySelectorAll(selector)]; }
  function hydrate(root,{streaming=false,onResize=()=>{}}={}) {
    for(const button of descendants(root,'[data-visual],[data-chart-source],[data-chart-save]')) {
      const text=t(button.hasAttribute('data-visual')?'放大预览':button.hasAttribute('data-chart-source')?'查看源码':'保存图表');
      if(button.textContent!==text)button.textContent=text;
    }
    for(const link of descendants(root,'a[data-media-link]'))if(link.textContent!==t('下载文件'))link.textContent=t('下载文件');
    for(const span of descendants(root,'.lyra-math[data-formula]'))if(!span.dataset.rendered) {
      span.dataset.rendered='true';const latex=decodeURIComponent(span.dataset.formula).slice(0,10_000);
      try{katex.render(latex,span,{displayMode:span.dataset.display==='true',throwOnError:false,trust:false,maxSize:20,maxExpand:1000,strict:'ignore'});}catch{span.textContent=latex;}
    }
    for(const node of descendants(root,'img,audio,video,source')) {
      const encoded=node.dataset.source,source=encoded?decodeURIComponent(encoded):node.getAttribute('src');
      if(!source)continue;
      const url=resource(source);if(node.dataset.mediaUrl===url)continue;
      node.dataset.mediaUrl=url;node.dataset.source=encodeURIComponent(source);
      if(url){node.src=url;node.setAttribute('referrerpolicy','no-referrer');if(node.tagName!=='IMG'){node.controls=true;node.preload='metadata';}node.addEventListener('load',onResize,{once:true});}
      else node.removeAttribute('src');
      node.addEventListener('error',()=>{
        const card=node.closest('.media-card');if(card){const error=card.querySelector('.media-error');error.textContent=t('媒体无法加载，请检查文件或格式');error.hidden=false;}
      },{once:true});
      if(node.tagName==='IMG')node.classList.add('preview-image');
    }
    for(const link of descendants(root,'a')) {
      const original=link.dataset.mediaLink?decodeURIComponent(link.dataset.mediaLink):link.getAttribute('href');if(!original)continue;
      const url=resource(original);
      if(url){link.href=url;link.rel='noopener noreferrer';if(!link.hasAttribute('download'))link.target='_blank';}
      else if(!original.startsWith('#')){link.removeAttribute('href');link.setAttribute('aria-disabled','true');}
    }
    if(!streaming)for(const code of descendants(root,'pre>code')) {
      const language=[...code.classList].find(c=>c.startsWith('language-'))?.slice(9);
      if(['mermaid','echarts','chart'].includes(language) && !code.parentElement.dataset.chartRendered) {
        code.parentElement.dataset.chartRendered='true';renderChart(code,language,onResize);
      }
    }
  }
  function validateData(value,depth=0) {
    if(depth>32)throw new Error('Chart data too deep');
    if(value && typeof value==='object')for(const [key,child] of Object.entries(value)) {
      if(['__proto__','prototype','constructor'].includes(key))throw new Error('Invalid chart property');
      if(key==='tooltip' && child && typeof child==='object')child.renderMode='richText';
      validateData(child,depth+1);
    }
  }
  async function renderChart(code,language,onResize) {
    const pre=code.parentElement,source=code.textContent;if(source.length>100_000)return;
    const host=document.createElement('div');host.className='rich-chart-host';pre.classList.add('rich-chart');code.hidden=true;pre.append(host);
    const controls=document.createElement('div');controls.className='chart-actions';
    controls.innerHTML=`<button class="quiet" data-visual>${esc(t('放大预览'))}</button><button class="quiet" data-chart-source>${esc(t('查看源码'))}</button><button class="quiet" data-chart-save>${esc(t('保存图表'))}</button>`;pre.append(controls);
    try {
      const document=language==='chart'?JSON.parse(source):{engine:language,source};
      if(!['mermaid','echarts'].includes(document.engine) || typeof document.source!=='string')throw new Error('Unsupported chart');
      if(document.title){const title=window.document.createElement('strong');title.textContent=document.title;pre.prepend(title);}
      if(document.engine==='mermaid') {
        if(/%%\s*\{|^\s*---/.test(document.source))throw new Error('Chart configuration directives are not supported');
        mermaidQueue=mermaidQueue.catch(()=>{}).then(async()=>{
          if(!pre.isConnected)return;
          mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:window.document.documentElement.dataset.scheme==='dark'?'dark':'default',htmlLabels:false,flowchart:{htmlLabels:false},suppressErrorRendering:true});
          const {svg}=await mermaid.render(`lyra-diagram-${++chartCounter}`,document.source);
          if(!pre.isConnected)return;
          host.innerHTML=DOMPurify.sanitize(svg,{USE_PROFILES:{svg:true,svgFilters:true},FORBID_TAGS:['script','foreignObject','iframe','a'],FORBID_ATTR:['onclick','onload']});
          host.querySelectorAll('[href],[xlink\\:href]').forEach(node=>{for(const attr of ['href','xlink:href'])if(node.hasAttribute(attr) && !node.getAttribute(attr).startsWith('#'))node.removeAttribute(attr);});
          onResize();
        });await mermaidQueue;
      } else {
        const option=JSON.parse(document.source);validateData(option);delete option.toolbox;
        const series=Array.isArray(option.series)?option.series:[option.series];if(!series.length || series.length>100 || series.some(s=>!s || typeof s.type!=='string'))throw new Error('Invalid ECharts series');
        host.classList.add('echarts-host');const chart=echarts.init(host,window.document.documentElement.dataset.scheme==='dark'?'dark':null,{renderer:'svg'});
        charts.set(host,{chart,observer:new ResizeObserver(()=>chart.resize())});charts.get(host).observer.observe(host);
        chart.setOption({...option,tooltip:{...option.tooltip,renderMode:'richText'}});onResize();
      }
    } catch(error) {
      const message=window.document.createElement('p');message.className='rich-error';message.textContent=`${t('图表渲染失败')} · ${error.message}`;host.replaceChildren(message);code.hidden=false;
    }
  }
  function restoreVisual() {if(visual){visual.placeholder.replaceWith(visual.node);visual=null;}}
  const visualDialog=document.getElementById('visual-dialog');
  document.getElementById('close-visual').addEventListener('click',()=>visualDialog.close());visualDialog.addEventListener('close',restoreVisual);
  visualDialog.addEventListener('click',e=>{if(e.target===visualDialog)visualDialog.close();});
  document.addEventListener('click',event=>{
    const button=event.target.closest('[data-visual],[data-chart-save],[data-chart-source],img.preview-image');if(!button)return;
    const container=button.closest('.rich-chart,.media-card') || button.parentElement;
    if(button.matches('[data-chart-source]')) {const code=container.querySelector('code');code.hidden=!code.hidden;return;}
    if(button.matches('[data-chart-save]')) {
      const svg=container.querySelector('svg');if(!svg)return;
      const url=URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(svg)],{type:'image/svg+xml'}));
      const link=document.createElement('a');link.href=url;link.download='chart.svg';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);return;
    }
    const node=container.querySelector('.rich-chart-host,img,audio,video');if(!node)return;
    restoreVisual();const placeholder=document.createElement('span');node.replaceWith(placeholder);visual={node,placeholder};
    document.getElementById('visual-title').textContent=t('放大预览');document.getElementById('close-visual').setAttribute('aria-label',t('关闭'));
    document.getElementById('visual-content').replaceChildren(node);visualDialog.showModal();
  });
  function release(root) {
    if(visual && (root===visual.placeholder || root.contains?.(visual.placeholder))){restoreVisual();visualDialog.close();}
    for(const [host,state] of charts)if(root===host || root.contains?.(host)){state.observer.disconnect();state.chart.dispose();charts.delete(host);}
  }
  return {markdown,body,hydrate,release,update(state){media=new Map((state.media || []).map(item=>[item.source,item]));}};
}
