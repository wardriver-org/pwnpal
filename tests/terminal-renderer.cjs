const {chromium}=require('playwright');
const fs=require('fs');const assert=require('assert');
(async()=>{
const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
const page=await browser.newPage({viewport:{width:412,height:720}});
async function waitForFunction(fn,arg){for(let i=0;i<100;i++){if(await page.evaluate(fn,arg))return;await new Promise(r=>setTimeout(r,50));}throw Error('Renderer condition timed out');}
const errors=[];page.on('pageerror',e=>errors.push(e.message));
await page.route('**/*',route=>{
 const name=new URL(route.request().url()).pathname.split('/').pop();
 const path=require('path').join(__dirname,'../app/src/main/assets/terminal',name);
 if(!fs.existsSync(path))return route.abort();
 route.fulfill({body:fs.readFileSync(path),contentType:name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':'text/html'});
});
await page.addInitScript(()=>{window.sent=[];window.sizes=[];window.queue=[];window.PwnTerminal={read:()=>queue.shift()||'',send:x=>sent.push(x),resize:(c,r)=>sizes.push([c,r]),ended:()=>false};});
await page.goto('https://pwnpal.invalid/terminal/index.html');
await waitForFunction(()=>sizes.length&&document.querySelector('.xterm-screen'));
await page.evaluate(()=>queue.push(btoa('hello\r\n\x1b[31mred\x1b[0m\r\n')));
await waitForFunction(()=>term.buffer.active.getLine(1)?.translateToString(true)==='red');
await page.evaluate(()=>term.focus());await page.keyboard.type('hello');
assert.equal(await page.evaluate(()=>sent.map(atob).join('')),'hello');
await page.evaluate(()=>sent=[]);await page.locator('#ctrl').click();await page.keyboard.type('c');
assert.equal(await page.evaluate(()=>sent.map(atob).join('')),'\x03');
await page.evaluate(()=>{sent=[];queue.push(btoa('\x1b[?1h'));});
await waitForFunction(()=>term.modes.applicationCursorKeysMode);
await page.locator('[data-key="UP"]').click();assert.equal(await page.evaluate(()=>sent.map(atob).join('')),'\x1bOA');
await page.evaluate(()=>{sent=[];queue.push(btoa('\x1b[?2004h'));});
await waitForFunction(()=>term.modes.bracketedPasteMode);
await page.evaluate(()=>pwnPaste('echo test\n'));
assert.equal(await page.evaluate(()=>sent.map(atob).join('')),'\x1b[200~echo test\r\x1b[201~');
const before=await page.evaluate(()=>sizes.at(-1));await page.setViewportSize({width:820,height:480});
await waitForFunction(([c,r])=>sizes.at(-1)[0]!==c&&sizes.at(-1)[1]!==r,before);
assert.deepEqual(errors,[]);
console.log('PASS: renderer boot, ANSI output, keyboard, Ctrl, application cursor arrows, bracketed paste, resize; no JavaScript errors.');
await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
