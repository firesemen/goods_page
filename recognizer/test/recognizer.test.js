import {test,beforeEach} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {randomUUID,randomBytes} from 'node:crypto';
import worker,{hash,validateResult} from '../worker.js';
let db,env,room,token,calls,lastInput,modelResult;
class Statement {
  constructor(sql){this.sql=sql;this.values=[];}
  bind(...v){this.values=v;return this;}
  async first(){return db.prepare(this.sql).get(...this.values)||null;}
}
function result(){return {fields:{name:'Молоко',quantity:'500',unit:'мл',manufactured:'2026-11-01',expiry:'2026-11-10',openingDays:'3'},evidence:{name:'Молоко',quantity:'Объем 500 мл',unit:'Объем 500 мл',manufactured:'Дата изготовления 01.11.2026',expiry:'Годен до 10.11.2026',openingDays:'После вскрытия 3 дня'},unknownDates:[],notes:[],recognizedText:'Молоко\nОбъем 500 мл\nГоден до 10.11.2026'};}
beforeEach(async()=>{
 db?.close();db=new DatabaseSync(':memory:');db.exec(readFileSync(new URL('../schema.sql',import.meta.url),'utf8'));
 room=`${randomUUID()}_${randomUUID()}`;token=randomBytes(32).toString('base64url');calls=0;lastInput=null;modelResult=result();
 env={ROOMS:JSON.stringify({[room]:await hash(token)}),DB:{prepare:sql=>new Statement(sql)},AI:{run:async(model,input)=>{calls++;lastInput={model,input};return {response:JSON.stringify(modelResult)};}}};
});
// A marker-only JPEG is sufficient for transport tests; inference is deliberately stubbed.
const photo=Buffer.concat([Buffer.from([255,216,255]),Buffer.alloc(200,4)]).toString('base64');
function req(data={images:[photo]},auth=token,method='POST'){return new Request(`https://ocr.test/v1/rooms/${room}/recognize`,{method,headers:{Authorization:`Bearer ${auth}`,'Content-Type':'application/json'},body:method==='POST'?JSON.stringify(data):undefined});}
const count=()=>db.prepare('SELECT sum(count) AS count FROM recognition_usage').get().count||0;
test('unprovisioned room, missing/wrong capability never reach AI or reserve quota',async()=>{
 for(const auth of ['',randomBytes(32).toString('base64url')])assert.equal((await worker.fetch(req(undefined,auth),env)).status,401);
 assert.equal((await worker.fetch(req(),{...env,ROOMS:'{}'})).status,401);assert.equal(calls,0);assert.equal(count(),0);
});
test('official multimodal messages API, schema and bounded output are used',async()=>{
 const response=await worker.fetch(req({images:[photo,photo]}),env);assert.equal(response.status,200);assert.equal(response.headers.get('Cache-Control'),'no-store');
 const parsed=await response.json();assert.equal(parsed.fields.expiry,'2026-11-10');assert.equal(parsed.remaining,19);
 assert.equal(lastInput.model,'@cf/google/gemma-3-12b-it');const input=lastInput.input;assert.equal(input.messages[0].role,'user');assert.equal(input.messages[0].content[1].image_url.url,'data:image/jpeg;base64,'+photo);assert.equal(input.messages[0].content.length,3);assert.ok(input.guided_json);assert.equal(input.stream,false);assert.equal(calls,1);
 const columns=db.prepare('PRAGMA table_info(recognition_usage)').all().map(x=>x.name);assert.deepEqual(columns,['room','day','count']);assert.equal(count(),1);
});
test('atomic daily quota caps concurrent attempts and isolates families',async()=>{
 env.DAILY_LIMIT='2';const responses=await Promise.all(Array.from({length:8},()=>worker.fetch(req(),env)));assert.equal(responses.filter(r=>r.status===200).length,2);assert.equal(responses.filter(r=>r.status===429).length,6);assert.equal(calls,2);assert.equal(count(),2);
 const oldRoom=room;room=`${randomUUID()}_${randomUUID()}`;const other=randomBytes(32).toString('base64url');env.ROOMS=JSON.stringify({[oldRoom]:await hash(token),[room]:await hash(other)});
 assert.equal((await worker.fetch(req(),env)).status,401);assert.equal((await worker.fetch(req(undefined,other),env)).status,200);assert.equal(count(),3);
});
test('provider errors count once and are neither retried nor exposed',async()=>{
 env.AI.run=async()=>{calls++;throw new Error('secret-provider-details');};const response=await worker.fetch(req(),env);assert.equal(response.status,502);assert.deepEqual(await response.json(),{error:'recognition_failed'});assert.equal(calls,1);assert.equal(count(),1);
});
test('invalid images, external URLs and large bodies fail before AI/quota',async()=>{
 for(const images of [[],[photo,photo,photo],['https://private/'],['A'.repeat(150)],['a'.repeat(1_250_001)]])assert.equal((await worker.fetch(req({images}),env)).status,400);
 const tooLarge=new Request(`https://ocr.test/v1/rooms/${room}/recognize`,{method:'POST',headers:{Authorization:`Bearer ${token}`},body:'a'.repeat(3_000_001)});assert.equal((await worker.fetch(tooLarge,env)).status,413);assert.equal(calls,0);assert.equal(count(),0);
});
test('malformed and contradictory model output rejected',async()=>{
 for(const change of [r=>r.fields.quantity='-1',r=>r.fields.unit='invented',r=>r.fields.expiry='2026-10-01',r=>r.fields.injected='x']){
  const r=result();change(r);if(r.fields.expiry==='2026-10-01')r.evidence.expiry='Годен до 01.10.2026';assert.throws(()=>validateResult(r));
 }
 modelResult={unexpected:'json'};assert.equal((await worker.fetch(req(),env)).status,502);
});
test('unsubstantiated dates, half quantities and hours are never suggested as valid calendar fields',()=>{
 const r=result();r.evidence.expiry='Годен до: см. упаковку';r.evidence.openingDays='После вскрытия 72 часа';delete r.fields.unit;const parsed=validateResult(r);assert.equal(parsed.fields.expiry,undefined);assert.equal(parsed.fields.openingDays,undefined);assert.equal(parsed.fields.quantity,undefined);assert.ok(parsed.notes.length>=3);
});
test('AI must be configured; plain HTTP and unsupported methods fail',async()=>{
 assert.equal((await worker.fetch(req(),{...env,AI:null})).status,503);assert.equal((await worker.fetch(req(undefined,token,'GET'),env)).status,405);
 const plain=new Request(`http://ocr.test/v1/rooms/${room}/recognize`,{method:'POST'});assert.equal((await worker.fetch(plain,env)).status,426);assert.equal(calls,0);
});
test('previous-day counters do not prevent a new day and no photos are stored in SQL',async()=>{
 db.prepare('INSERT INTO recognition_usage VALUES (?,?,?)').run(room,'2000-01-01',20);
 const response=await worker.fetch(req(),env);assert.equal(response.status,200);const rows=db.prepare('SELECT * FROM recognition_usage').all();assert.equal(rows.length,2);assert.ok(rows.every(r=>!JSON.stringify(r).includes(photo)));assert.equal(rows.find(r=>r.day!=='2000-01-01').count,1);
});

test('public landing page identifies Worker without exposing family configuration',async()=>{
 const response=await worker.fetch(new Request('https://ocr.test/'),env);assert.equal(response.status,200);const page=await response.json();assert.equal(page.service,'fridge-photo');assert.equal(page.version,2);assert.ok(!JSON.stringify(page).includes(room));assert.ok(!JSON.stringify(page).includes(token));assert.equal(calls,0);assert.equal(count(),0);
});
function statusRequest(auth=token){return new Request(`https://ocr.test/v1/rooms/${room}/status`,{headers:{Authorization:`Bearer ${auth}`}});}
test('authenticated connection check verifies D1 without calling AI or spending quota',async()=>{
 let response=await worker.fetch(statusRequest(),env);assert.equal(response.status,200);let status=await response.json();assert.equal(status.ready,true);assert.equal(status.remaining,20);assert.equal(status.dailyLimit,20);assert.equal(calls,0);assert.equal(count(),0);
 await worker.fetch(req(),env);response=await worker.fetch(statusRequest(),env);status=await response.json();assert.equal(status.remaining,19);assert.equal(count(),1);assert.equal(calls,1);
 assert.equal((await worker.fetch(statusRequest(randomBytes(32).toString('base64url')),env)).status,401);
});
test('connection check diagnoses missing SQL table and bindings without exposing SQL errors',async()=>{
 assert.equal((await worker.fetch(statusRequest(),{...env,AI:null})).status,503);db.exec('DROP TABLE recognition_usage');
 for(const request of [statusRequest(),req()]){const response=await worker.fetch(request,env);assert.equal(response.status,503);assert.deepEqual(await response.json(),{error:'database_not_ready'});}assert.equal(calls,0);
});
test('exhausted quota still permits connection check, never negative remaining',async()=>{
 env.DAILY_LIMIT='1';await worker.fetch(req(),env);assert.equal((await worker.fetch(req(),env)).status,429);const status=await (await worker.fetch(statusRequest(),env)).json();assert.equal(status.remaining,0);assert.equal(calls,1);assert.equal(count(),1);
});
