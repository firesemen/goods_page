import {test,beforeEach} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {randomUUID,randomBytes} from 'node:crypto';
import worker,{digest} from '../src/worker.js';

let env,room,token,db;
class Statement {
  constructor(sql){this.sql=sql;this.values=[];}
  bind(...values){this.values=values;return this;}
  async first(){return db.prepare(this.sql).get(...this.values)||null;}
  async all(){return {results:db.prepare(this.sql).all(...this.values)};}
  async run(){return db.prepare(this.sql).run(...this.values);}
}
beforeEach(async()=>{
  db?.close();db=new DatabaseSync(':memory:');db.exec(readFileSync(new URL('../schema.sql',import.meta.url),'utf8'));
  room=`${randomUUID()}_${randomUUID()}`;token=randomBytes(32).toString('base64url');
  env={ROOMS:JSON.stringify({[room]:await digest(token)}),DB:{prepare:sql=>new Statement(sql),batch:async statements=>{db.exec('BEGIN');try{const result=[];for(const s of statements)result.push(await s.run());db.exec('COMMIT');return result;}catch(e){db.exec('ROLLBACK');throw e;}}}};
});
function req(method,path='',data=undefined,auth=token){return new Request(`https://relay.test/v1/rooms/${room}${path}`,{method,headers:{Authorization:`Bearer ${auth}`,'Content-Type':'application/json'},body:data===undefined?undefined:JSON.stringify(data)});}
const event=()=>({id:randomUUID(),payload:Buffer.concat([Buffer.from([1]),randomBytes(60)]).toString('base64url')});
test('relay only accepts explicitly provisioned rooms and the correct capability',async()=>{
  assert.equal((await worker.fetch(req('PUT'),env)).status,200);
  assert.equal((await worker.fetch(req('PUT','',undefined,randomBytes(32).toString('base64url')),env)).status,401);
  assert.equal((await worker.fetch(req('PUT'),{...env,ROOMS:'{}'})).status,401);
  assert.equal((await worker.fetch(new Request('https://relay.test/'),env)).status,404);
});
test('ciphertext is immutable, replay idempotent and returned intact',async()=>{
  const e=event();assert.equal((await worker.fetch(req('POST','/events',{events:[e]}),env)).status,200);
  assert.equal((await worker.fetch(req('POST','/events',{events:[e]}),env)).status,200);
  const data=await (await worker.fetch(req('GET','/events?after=0'),env)).json();assert.equal(data.events.length,1);assert.equal(data.events[0].payload,e.payload);
  assert.equal((await worker.fetch(req('POST','/events',{events:[{...e,payload:event().payload}]}),env)).status,409);
  const done=await (await worker.fetch(req('GET',`/events?after=${data.cursor}`),env)).json();assert.equal(done.events.length,0);assert.equal(done.cursor,data.cursor);
});
test('pagination has no gaps across 45 encrypted updates',async()=>{
  const all=Array.from({length:45},event);for(let n=0;n<all.length;n+=20)assert.equal((await worker.fetch(req('POST','/events',{events:all.slice(n,n+20)}),env)).status,200);
  let cursor=0,ids=[],more=true;while(more){const page=await (await worker.fetch(req('GET',`/events?after=${cursor}`),env)).json();ids.push(...page.events.map(e=>e.id));assert.ok(page.cursor>cursor);cursor=page.cursor;more=page.hasMore;}
  assert.deepEqual(ids,all.map(e=>e.id));
});
test('different families cannot read or write each other’s events',async()=>{
  const e=event();await worker.fetch(req('POST','/events',{events:[e]}),env);
  const other=`${randomUUID()}_${randomUUID()}`,otherToken=randomBytes(32).toString('base64url');env.ROOMS=JSON.stringify({[room]:await digest(token),[other]:await digest(otherToken)});
  const ownRoom=room;room=other;assert.equal((await worker.fetch(req('GET','/events'),env)).status,401);
  const response=await worker.fetch(req('GET','/events',undefined,otherToken),env);assert.deepEqual((await response.json()).events,[]);room=ownRoom;
});
test('invalid input, duplicate IDs, large batches and hostile cursors rejected',async()=>{
  for(const events of [[],Array.from({length:21},event),[{id:'x',payload:event().payload}],null])assert.equal((await worker.fetch(req('POST','/events',{events}),env)).status,400);
  const e=event();assert.equal((await worker.fetch(req('POST','/events',{events:[e,e]}),env)).status,400);
  for(const cursor of ['-1','NaN','1.5','9007199254740992'])assert.equal((await worker.fetch(req('GET',`/events?after=${cursor}`),env)).status,400);
  assert.equal((await worker.fetch(req('POST','/events',{events:[{id:randomUUID(),payload:'a'.repeat(1_000_001)}]}),env)).status,413);
});
test('validation rejects the whole batch before storing valid earlier entries',async()=>{
  const valid=event();assert.equal((await worker.fetch(req('POST','/events',{events:[valid,{id:'bad',payload:'?'}]}),env)).status,400);
  const page=await (await worker.fetch(req('GET','/events'),env)).json();assert.equal(page.events.length,0);
});
