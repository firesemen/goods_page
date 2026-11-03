const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const ROUTE = new RegExp(`^/v1/rooms/(${UUID}_${UUID})(/events)?$`);
const EVENT_ID = new RegExp(`^${UUID}$`);
const json = (value, status = 200) => new Response(JSON.stringify(value), {status, headers: {'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'}});
export async function digest(value) {
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return Array.from(new Uint8Array(hash), n => n.toString(16).padStart(2, '0')).join('');
}
function equal(a, b) {if(typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length)return false;let diff=0;for(let i=0;i<a.length;i++)diff|=a.charCodeAt(i)^b.charCodeAt(i);return diff===0;}
async function body(request) {
  if (Number(request.headers.get('Content-Length') || 0) > 1_000_000) throw Object.assign(new Error('body_limit'), {status: 413});
  if(!request.body)throw Object.assign(new Error('invalid_json'),{status:400});
  const reader=request.body.getReader();let size=0;const chunks=[];
  while(true){const {value,done}=await reader.read();if(done)break;size+=value.length;if(size>1_000_000){await reader.cancel();throw Object.assign(new Error('body_limit'),{status:413});}chunks.push(value);}
  const all=new Uint8Array(size);let offset=0;for(const c of chunks){all.set(c,offset);offset+=c.length;}
  try{return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(all));}catch{throw Object.assign(new Error('invalid_json'),{status:400});}
}

export default {
  async fetch(request, env) {
    try {
      const url=new URL(request.url),match=url.pathname.match(ROUTE);
      if(!match)return json({error:'not_found'},404);
      const room=match[1],eventsRoute=!!match[2];
      let rooms;try{rooms=JSON.parse(env.ROOMS || '{}');}catch{return json({error:'relay_not_configured'},503);}
      const authorization=request.headers.get('Authorization')||'';
      if(!/^Bearer [A-Za-z0-9_-]{43}$/.test(authorization))return json({error:'unauthorized'},401);
      const expected=rooms[room];
      if(!expected||!/^[0-9a-f]{64}$/.test(expected)||!equal(await digest(authorization.substring(7)),expected))return json({error:'unauthorized'},401);
      if(!eventsRoute&&request.method==='PUT')return json({ready:true});
      if(eventsRoute&&request.method==='GET'){
        const afterText=url.searchParams.get('after')||'0';
        if(!/^\d{1,16}$/.test(afterText)||!Number.isSafeInteger(Number(afterText)))return json({error:'invalid_cursor'},400);
        const after=Number(afterText),{results}=await env.DB.prepare('SELECT seq,id,payload FROM events WHERE room=? AND seq>? ORDER BY seq LIMIT 21').bind(room,after).all();
        const events=results.slice(0,20);return json({events,cursor:events.length?events.at(-1).seq:after,hasMore:results.length>20});
      }
      if(eventsRoute&&request.method==='POST'){
        const input=await body(request);
        if(!input||!Array.isArray(input.events)||input.events.length<1||input.events.length>20)return json({error:'invalid_events'},400);
        const seen=new Set(),prepared=[];
        for(const e of input.events){
          if(!e||!EVENT_ID.test(e.id)||seen.has(e.id)||typeof e.payload!=='string'||e.payload.length<39||e.payload.length>400_000||!/^[A-Za-z0-9_-]+$/.test(e.payload))return json({error:'invalid_event'},400);
          seen.add(e.id);const hash=await digest(e.payload);
          const existing=await env.DB.prepare('SELECT digest FROM events WHERE room=? AND id=?').bind(room,e.id).first();
          if(existing&&!equal(existing.digest,hash))return json({error:'immutable_event_conflict'},409);
          if(!existing)prepared.push(env.DB.prepare('INSERT OR IGNORE INTO events(room,id,payload,digest) VALUES (?,?,?,?)').bind(room,e.id,e.payload,hash));
        }
        const count=await env.DB.prepare('SELECT count(*) AS n FROM events WHERE room=?').bind(room).first();
        if(count.n+prepared.length>20_000)return json({error:'history_limit'},413);
        if(prepared.length)await env.DB.batch(prepared);
        return json({accepted:input.events.map(e=>e.id)});
      }
      return json({error:'method_not_allowed'},405);
    }catch(e){return json({error:e.status?e.message:'relay_error'},e.status||500);}
  }
};
