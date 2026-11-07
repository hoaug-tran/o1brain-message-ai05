import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import test from "node:test";
import {webcrypto} from "node:crypto";

function setup() {
  const elements = new Map();
  function element() {
    return {
      disabled:false, hidden:false, children:[], handlers:{}, value:"", dataset:{}, style:{}, attributes:{}, textContent:"", scrollHeight:48,
      addEventListener(name, fn){this.handlers[name]=fn;}, replaceChildren(...nodes){this.children=nodes;},
      append(...nodes){this.children.push(...nodes);}, setAttribute(name,value){this.attributes[name]=value;},
      removeAttribute(name){delete this.attributes[name];}, focus(){}, showModal(){this.open=true;}, close(){this.open=false;},
      setCustomValidity(value){this.validity=value;}, reportValidity(){},
    };
  }
  const html = fs.readFileSync(new URL("../src/ai05/web/index.html",import.meta.url),"utf8");
  for (const [,id] of html.matchAll(/id="([^"]+)"/g)) elements.set(`#${id}`,element());
  elements.set(".bottom-nav",element());
  elements.set(".app-header",element());
  elements.get("#filter").value="inbox";
  elements.get("#theme").value="system";
  const controls=["#sms","#analyze","#sample","#sample-spam","#clear"].map(id=>elements.get(id));
  elements.get("#sms-form").elements=controls;
  let resolveComparison;
  let rejectComparison;
  const requests=[];
  const context={
    document:{querySelector:id=>elements.get(id),querySelectorAll:()=>[],createElement:element,createElementNS:()=>element(),documentElement:{dataset:{}}},
    window:{addEventListener(){},matchMedia:()=>({matches:false,addEventListener(){}})},
    location:{hash:"#inbox",replace(value){this.hash=value;}},history:{replaceState(){}},
    localStorage:{getItem(){return null;},setItem(){}},crypto:webcrypto,AbortController,
    setTimeout:()=>1,clearTimeout(){},console,
    fetch:async(url,options)=>{
      requests.push({url,options});
      if(url.endsWith("/metrics"))return{ok:true,json:async()=>({reports:{english_baseline:{metrics:{spam_f1:.8,spam_precision:.9,spam_recall:.8,ham_fpr:.1},confusion:{tn:9,fp:1,fn:2,tp:8}}}})};
      if(url.endsWith("/active"))throw Error("model info unavailable");
      return new Promise((resolve,reject)=>{resolveComparison=resolve;rejectComparison=reject;});
    }
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(new URL("../src/ai05/web/app.js",import.meta.url),"utf8"),context);
  return {elements,controls,requests,run:code=>vm.runInContext(code,context),resolve:value=>resolveComparison(value),reject:error=>rejectComparison(error)};
}
const event={preventDefault(){}};

test("real comparison locks all controls; error unlocks; editing clears stale result", async()=>{
  const app=setup();
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(app.elements.get("#metrics").children.length,1);
  const cells=app.elements.get("#metrics").children[0].children;
  assert.equal(cells.length,7);
  assert.equal(cells[3].textContent,"0.80000");
  assert.equal(cells[4].textContent,"0.85000");
  app.elements.get("#sms").value="See you tomorrow.";
  const task=app.elements.get("#sms-form").handlers.submit(event);
  assert(app.controls.every(control=>control.disabled));
  assert.equal(app.elements.get("#result").children[0].className,"skeleton");
  assert.equal(JSON.parse(app.requests.at(-1).options.body).text,"See you tomorrow.");
  app.resolve({ok:true,json:async()=>({comparisons:[{model:{id:"english_char_svm",version:"test"},classification:{label:"ham",score:-1.3,score_kind:"decision_margin"},latency_ms:2}]})});
  await task;
  assert(app.controls.every(control=>!control.disabled));
  assert.equal(app.elements.get("#result").children[0].className,"prediction");
  app.elements.get("#sms").handlers.input();
  assert.equal(app.elements.get("#result").children.length,0);
  const failed=app.elements.get("#sms-form").handlers.submit(event);
  app.reject(new Error("Network unavailable"));
  await failed;
  assert.match(app.elements.get("#result").children[0].textContent,/Network unavailable/);
  assert(app.controls.every(control=>!control.disabled));
});

test("spam navigation clears unread badge and returns to spam; actions update filters",()=>{
  const app=setup();
  assert.equal(app.elements.get("#spam-count").textContent,"1");
  app.run('location.hash="#chat/prize/spam"; navigate()');
  assert.equal(app.elements.get("#spam-count").hidden,true);
  app.elements.get("#chat-back").handlers.click();
  assert.equal(app.run("location.hash"),"#spam");
  app.run('location.hash="#inbox"; navigate(); openActions(threads[0])');
  app.elements.get("#thread-actions").children[1].handlers.click();
  app.elements.get("#filter").value="trusted";
  app.elements.get("#filter").handlers.change();
  assert.equal(app.elements.get("#conversation-count").textContent,"0");
  app.run('openActions(threads[0])');
  app.elements.get("#thread-actions").children[0].handlers.click();
  app.elements.get("#filter").value="unread";
  app.elements.get("#filter").handlers.change();
  assert.equal(app.elements.get("#conversation-count").textContent,"1");
});

test("borderline Ham stays Ham with UCI model",async()=>{
  const app=setup();
  app.elements.get("#sms").value="A borderline message";
  const task=app.elements.get("#sms-form").handlers.submit(event);
  app.resolve({ok:true,json:async()=>({comparisons:[{model:{id:"english_char_svm",version:"test"},classification:{label:"ham",score:-.2,score_kind:"decision_margin"},policy:{status:"no_spam_signal"},warning:"UCI",latency_ms:2}]})});
  await task;
  const block=app.elements.get("#result").children[0];
  assert.equal(block.className,"prediction");
  assert.match(block.children[1].textContent,/Character TF-IDF.*Linear SVM/);
  assert.match(block.children[2].textContent,/HAM/);
});

test("mock compose escapes text, preserves draft, rejects blank recipient and never calls API",()=>{
  const app=setup();
  app.run('location.hash="#compose"; navigate()');
  app.elements.get("#message-input").value='<img src=x onerror="bad()">';
  app.elements.get("#message-input").handlers.input();
  app.run('location.hash="#inbox"; navigate(); location.hash="#compose"; navigate()');
  assert.match(app.elements.get("#message-input").value,/<img/);
  app.elements.get("#message-form").handlers.submit(event);
  assert.match(app.elements.get("#recipient").validity,/người nhận/);
  app.elements.get("#recipient").value="Test recipient";
  app.elements.get("#message-form").handlers.submit(event);
  const bubble=app.elements.get("#messages").children[0].children[0];
  assert.equal(bubble.textContent,'<img src=x onerror="bad()">');
  assert.equal(bubble.children.length,0);
  assert.equal(app.requests.filter(request=>request.options?.method==="POST").length,0);
});

test("English sample generators vary, reset clears result, theme follows explicit choice",()=>{
  const app=setup();
  for(const id of ["#sample","#sample-spam"]){
    const values=new Set();
    for(let count=0;count<30;count++){
      app.elements.get(id).handlers.click();
      const value=app.elements.get("#sms").value;
      assert(!/[\u0100-\u1fff]/u.test(value));
      values.add(value);
    }
    assert(values.size>20);
  }
  app.elements.get("#theme").value="dark";
  app.elements.get("#theme").handlers.change();
  assert.equal(app.run("document.documentElement.dataset.theme"),"dark");
  app.elements.get("#sms-form").handlers.reset();
  assert.equal(app.elements.get("#char-count").textContent,"0 / 2.000");
});
