'use strict';
const term=new Terminal({cursorBlink:true,fontSize:14,fontFamily:'monospace',scrollback:2000,allowProposedApi:false,theme:{background:'#0b1114',foreground:'#ecf4f0',cursor:'#8be8c0'},linkHandler:{activate:()=>{}}});
const fit=new FitAddon.FitAddon();term.loadAddon(fit);term.open(document.getElementById('terminal'));
// Remote escape sequences cannot read or modify the phone clipboard.
term.parser.registerOscHandler(52,()=>true);
let ctrl=false;
function setCtrl(value){ctrl=value;document.getElementById('ctrl').setAttribute('aria-pressed',String(value));}
function encode(bytes){let s='';for(const b of bytes)s+=String.fromCharCode(b);return btoa(s);}
function send(text){PwnTerminal.send(encode(new TextEncoder().encode(text)));}
term.onData(data=>{if(ctrl&&data.length===1){const c=data.toUpperCase().charCodeAt(0);if(c>=64&&c<=95)data=String.fromCharCode(c&31);setCtrl(false);}send(data);});
term.onBinary(data=>PwnTerminal.send(btoa(data)));
term.onResize(size=>PwnTerminal.resize(size.cols,size.rows));
const keys={ESC:'\x1b',TAB:'\t',UP:'\x1b[A',DOWN:'\x1b[B',LEFT:'\x1b[D',RIGHT:'\x1b[C',CTRL_C:'\x03',CTRL_D:'\x04',HOME:'\x1b[H',END:'\x1b[F',PGUP:'\x1b[5~',PGDN:'\x1b[6~'};
document.getElementById('ctrl').onclick=()=>{setCtrl(!ctrl);term.focus();};
for(const b of document.querySelectorAll('[data-key]'))b.onclick=()=>{let key=keys[b.dataset.key];if(term.modes.applicationCursorKeysMode&&['UP','DOWN','LEFT','RIGHT','HOME','END'].includes(b.dataset.key))key=key.replace('[','O');send(key);term.focus();};
window.pwnPaste=text=>{setCtrl(false);term.paste(text);term.focus();};
window.pwnSelection=()=>{const selected=term.getSelection();if(selected)return selected.slice(0,65536);const lines=[];for(let i=0;i<term.rows;i++)lines.push(term.buffer.active.getLine(term.buffer.active.viewportY+i)?.translateToString(true)||'');return lines.join('\n').trimEnd().slice(0,65536);};
window.pwnFocus=()=>term.focus();
function resize(){fit.fit();PwnTerminal.resize(term.cols,term.rows);}
new ResizeObserver(resize).observe(document.getElementById('terminal'));
resize();
function pump(){const b64=PwnTerminal.read();if(b64){const bytes=Uint8Array.from(atob(b64),c=>c.charCodeAt(0));term.write(bytes,()=>setTimeout(pump,0));}else if(!PwnTerminal.ended())setTimeout(pump,30);}
pump();
