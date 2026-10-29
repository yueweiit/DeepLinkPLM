import {onMounted,onUnmounted,ref} from 'vue'

/** A loaded tab keeps its JavaScript until a reload. Never replace a user's unsaved form. */
export function useClientUpdate() {
  const available=ref(false)
  let stopped=false,pending=false,lastCheck=-Infinity,timer:ReturnType<typeof setInterval>|undefined,controller:AbortController|undefined
  function entry(html:ParentNode,base:string) {
    const src=html.querySelector<HTMLScriptElement>('script[type="module"][src]')?.getAttribute('src')
    if(!src)return ''
    const url=new URL(src,base)
    return url.origin===location.origin&&/\/assets\/index-[\w-]+\.js$/.test(url.pathname)?url.pathname:''
  }
  const current=entry(document,location.href)
  async function check() {
    if(stopped||pending||!current||document.visibilityState==='hidden'||Date.now()-lastCheck<30_000)return
    pending=true;lastCheck=Date.now();controller=new AbortController()
    try {
      const base=new URL(import.meta.env.BASE_URL,location.href),url=new URL('index.html',base)
      const response=await fetch(url,{cache:'no-store',signal:controller.signal})
      if(!response.ok)return
      const template=document.createElement('template');template.innerHTML=await response.text()
      const latest=entry(template.content,url.href)
      if(!stopped&&latest)available.value=latest!==current
    } catch { /* Keep the current form usable when version discovery is unavailable. */ }
    finally {pending=false}
  }
  function refresh(){location.reload()}
  onMounted(()=>{void check();timer=setInterval(()=>void check(),60_000);window.addEventListener('focus',check);document.addEventListener('visibilitychange',check)})
  onUnmounted(()=>{stopped=true;controller?.abort();if(timer)clearInterval(timer);window.removeEventListener('focus',check);document.removeEventListener('visibilitychange',check)})
  return {available,check,refresh}
}
