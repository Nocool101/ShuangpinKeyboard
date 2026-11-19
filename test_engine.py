import asyncio, json, os, sys
from pathlib import Path
from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parent
URL = (ROOT / "index.html").as_uri()
SHOTS = ROOT / "shots"
SHOTS.mkdir(exist_ok=True)

# 浏览器可执行文件：默认让 Playwright 自己找；
# 若装在非标准位置，可用环境变量 PLAYWRIGHT_CHROMIUM 指定。
CHROMIUM = os.environ.get("PLAYWRIGHT_CHROMIUM")

TEST = r"""
(() => {
  const out = {pass:[], fail:[]};
  const T = (name, cond, extra) => (cond ? out.pass : out.fail).push(name + (extra!==undefined? ' -> '+extra : ''));

  // --- 键位表编码正确性 ---
  T('bai=b d', encode('bai')==='bd', encode('bai'));
  T('zhong=v s', encode('zhong')==='vs', encode('zhong'));
  T('xue=x t', encode('xue')==='xt', encode('xue'));
  T('wen=w f', encode('wen')==='wf', encode('wen'));
  T('ping=p k', encode('ping')==='pk', encode('ping'));
  T('liang=l l', encode('liang')==='ll', encode('liang'));
  T('nv=n v', encode('nv')==='nv', encode('nv'));
  T('jv=ju', encode('ju')==='jv', encode('ju'));
  T('jv... j u e', encode('jue')==='jt', encode('jue'));
  T('chuang=ch i + uang l', encode('chuang')==='il', encode('chuang'));

  // --- 零声母（小鹤规则） ---
  T('zero a=aa', encode('a')==='aa', encode('a'));
  T('zero ai=ai', encode('ai')==='ai', encode('ai'));
  T('zero an=an', encode('an')==='an', encode('an'));
  T('zero ang=ah', encode('ang')==='ah', encode('ang'));
  T('zero en=en', encode('en')==='en', encode('en'));
  T('zero eng=eg', encode('eng')==='eg', encode('eng'));
  T('zero er=er', encode('er')==='er', encode('er'));
  T('zero ou=ou', encode('ou')==='ou', encode('ou'));

  // --- 第一键：26 个键全部合法 ---
  let allFirst = true, bad=[];
  for (const k of 'abcdefghijklmnopqrstuvwxyz') {
    if (resolveFinals(SCHEMES.xiaohe, k).size === 0) { allFirst=false; bad.push(k); }
  }
  T('26 keys valid as first key', allFirst, bad.join(','));

  // --- b 的第二阶段：15 个可用键，且歧义已消解 ---
  const fb = resolveFinals(SCHEMES.xiaohe, 'b');
  T('b -> 16 finals', fb.size===16, fb.size);
  T('b+d = ai/bai', fb.get('d') && fb.get('d').fin==='ai' && fb.get('d').syl==='bai', JSON.stringify(fb.get('d')));
  T('b+k = ing/bing', fb.get('k') && fb.get('k').fin==='ing', JSON.stringify(fb.get('k')));
  T('b+e not valid', !fb.get('e'), JSON.stringify(fb.get('e')));
  T('b has no ambiguous fin', [...fb.values()].every(v=>!v.fin.includes('/')), JSON.stringify([...fb.values()].filter(v=>v.fin.includes('/'))));

  // --- 9 个歧义键在每个声母下都唯一 ---
  const AMB = {k:['ing','uai'], s:['ong','iong'], x:['ia','ua'], l:['iang','uang'],
               o:['o','uo'], v:['v','ui'], y:['un','vn'], t:['ve','ue'], r:['uan','van']};
  let ambig=[];
  for (const k in AMB) {
    for (const fk of 'abcdefghijklmnopqrstuvwxyz') {
      const r = resolveFinals(SCHEMES.xiaohe, fk).get(k);
      if (r && r.fin.includes('/')) ambig.push(fk+'+'+k+'='+r.fin);
    }
  }
  T('no unresolved ambiguity after initial', ambig.length===0, ambig.join(' | '));

  // --- 每个音节都能解出合法编码 ---
  let noCode=[], badSyl=[];
  for (const line of Object.keys(SYL_BY_IF)) {
    const syl = SYL_BY_IF[line];
    const c = encode(syl);
    if (!c || c.length!==2) noCode.push(syl+'='+c);
  }
  T('all syllables have 2-key code', noCode.length===0, noCode.slice(0,10).join(','));

  // --- 编码可逆：每个编码都能反解回音节 ---
  const dec = {};
  for (const line in SYL_BY_IF) { const s=SYL_BY_IF[line]; const c=encode(s); dec[c]=s; }
  let unrev=[];
  for (const fk in dec) { const s=dec[fk]; const hit=resolveFinals(SCHEMES.xiaohe, fk[0]).get(fk[1]); if(!hit || hit.syl!==s) unrev.push(fk+'='+s+' vs '+(hit?hit.syl:'null')); }
  T('all codes reversible', unrev.length===0, unrev.slice(0,12).join(' | '));

  // --- 校验用例 ---
  let cf=[];
  CHECK_CASES.xiaohe.forEach(([z,py,c])=>{ if(encode(py)!==c) cf.push(z+':'+encode(py)+'≠'+c); });
  T('xiaohe check cases', cf.length===0, cf.join(','));
  let cf2=[];
  const _sc = SCHEME; SCHEME = SCHEMES.ziranma;
  CHECK_CASES.ziranma.forEach(([z,py,c])=>{ if(encode(py)!==c) cf2.push(z+':'+encode(py)+'≠'+c); });
  SCHEME = _sc;
  T('ziranma check cases', cf2.length===0, cf2.join(','));

  // --- 词表覆盖 ---
  const missing=[];
  for (const line in SYL_BY_IF){ const s=SYL_BY_IF[line]; if(!CHARS[s]) missing.push(s); }
  T('chardict covers ' + (Object.keys(SYL_BY_IF).length - missing.length) + '/' + Object.keys(SYL_BY_IF).length + ' syllables', missing.length<=8, missing.join(','));

  // --- 词表中的每个音节都必须在音节表里（防止漏音节） ---
  const orphan = Object.keys(CHARS).filter(s=>!SYL_BY_IF[Object.keys(SYL_BY_IF).find(k=>SYL_BY_IF[k]===s)] );
  const ZERO_ALL = Object.values(ZERO_SYLS);
  const orphan2 = Object.keys(CHARS).filter(sy=>{
    if (ZERO_ALL.includes(sy)) return false;                 // 零声母单独存
    const k=Object.keys(SYL_BY_IF).find(k=>SYL_BY_IF[k]===sy);
    if (k) return false;
    // 同音合并（ye/yue）算通过
    return !Object.keys(SYL_BY_IF).some(k=>{
      const a=SYL_BY_IF[k];
      return (a==='ye'&&sy==='yue')||(a==='yue'&&sy==='ye');
    });
  });
  T('all chardict syllables exist in syll table', orphan2.length===0, orphan2.join(','));

  // --- 每个声母的常用字都有对应音节 ---
  const commonSyl = ['hao','hui','gong','song','zai','zhong','shi','de','wo','ni'];
  const miss3 = commonSyl.filter(sy=>!Object.keys(SYL_BY_IF).some(k=>SYL_BY_IF[k]===sy) && !Object.values(ZERO_SYLS).includes(sy));
  T('common syllables present', miss3.length===0, miss3.join(','));

  return out;
})()
"""

async def main():
    async with async_playwright() as p:
        launch_args = ["--no-sandbox", "--disable-dev-shm-usage"]
        b = await p.chromium.launch(executable_path=CHROMIUM, args=launch_args) if CHROMIUM \
            else await p.chromium.launch(args=launch_args)
        pg = await b.new_page(viewport={'width':1180,'height':1100}, device_scale_factor=2)
        errs=[]
        pg.on('console', lambda m: errs.append(m.text) if m.type=='error' else None)
        pg.on('pageerror', lambda e: errs.append(str(e)))
        await pg.goto(URL)
        await pg.wait_for_timeout(400)
        res = await pg.evaluate(TEST)
        print("PASS:", len(res['pass']))
        for x in res['pass']: print("  ok  ", x)
        print("FAIL:", len(res['fail']))
        for x in res['fail']: print("  XX  ", x)
        if errs: print("JS ERRORS:", errs[:5])

        await pg.screenshot(path=str(SHOTS / 'shot-1-initial.png'), full_page=True)

        # 交互：按 b
        await pg.click('.key[data-k="b"]')
        await pg.wait_for_timeout(250)
        await pg.screenshot(path=str(SHOTS / 'shot-2-final.png'), full_page=True)
        info = await pg.evaluate("()=>({stage:state.stage, keys:state.finals.size, lit:document.querySelectorAll('.key.lit').length, dead:document.querySelectorAll('.key.dead').length, chips:document.querySelectorAll('#candbar .chip').length})")
        print("after b:", json.dumps(info, ensure_ascii=False))

        # 按 d -> bai
        await pg.click('.key[data-k="d"]')
        await pg.wait_for_timeout(250)
        info2 = await pg.evaluate("()=>({stage:state.stage, syl:state.syl.syl, fin:state.syl.fin, chips:[...document.querySelectorAll('#candbar .chip')].slice(0,6).map(c=>c.textContent)})")
        print("after d:", json.dumps(info2, ensure_ascii=False))
        await pg.screenshot(path=str(SHOTS / 'shot-3-syllable.png'), full_page=True)

        # 连续输入 nihao
        await pg.evaluate("()=>{state.committed='';state.pending=[];state.stage='initial';state.firstKey=null;state.finals=null;state.syl=null;render();}")
        for k in ['n','i','h','c']:
            await pg.click(f'.key[data-k="{k}"]')
            await pg.wait_for_timeout(90)
        info3 = await pg.evaluate("()=>({pending:state.pending.map(s=>s.syl), phrase:document.querySelector('#candbar .chip.phrase')?document.querySelector('#candbar .chip.phrase').textContent:null, comp:document.querySelector('#editor .comp')?document.querySelector('#editor .comp').textContent:null, chips:[...document.querySelectorAll('#candbar .chip')].map(c=>c.textContent).slice(0,8)})")
        print("nihao:", json.dumps(info3, ensure_ascii=False))
        await pg.screenshot(path=str(SHOTS / 'shot-4-phrase.png'), full_page=True)

        # 零声母：a -> i  (爱)
        await pg.evaluate("()=>{state.committed='';state.pending=[];state.stage='initial';state.firstKey=null;state.finals=null;state.syl=null;render();}")
        await pg.click('.key[data-k="a"]'); await pg.wait_for_timeout(120)
        z1 = await pg.evaluate("()=>({stage:state.stage, keys:state.finals.size, lit:[...document.querySelectorAll('.key.lit .big')].map(e=>e.textContent)})")
        print("zero a:", json.dumps(z1, ensure_ascii=False))
        await pg.click('.key[data-k="i"]'); await pg.wait_for_timeout(120)
        z2 = await pg.evaluate("()=>({syl:state.pending.map(s=>s.syl), run:document.querySelector('#editor .comp').textContent})")
        print("zero a+i:", json.dumps(z2, ensure_ascii=False))
        await pg.screenshot(path=str(SHOTS / 'shot-5-zero.png'), full_page=True)

        # 整体认读：y + t = yue (月)
        await pg.evaluate("()=>{state.committed='';state.pending=[];state.stage='initial';state.firstKey=null;state.finals=null;state.syl=null;render();}")
        await pg.click('.key[data-k="y"]'); await pg.wait_for_timeout(120)
        y1 = await pg.evaluate("()=>({keys:state.finals.size, lit:[...document.querySelectorAll('.key.lit .big')].map(e=>e.textContent)})")
        print("y:", json.dumps(y1, ensure_ascii=False))
        await pg.click('.key[data-k="t"]'); await pg.wait_for_timeout(120)
        y2 = await pg.evaluate("()=>({syl:state.pending.map(s=>s.syl)})")
        print("y+t:", json.dumps(y2, ensure_ascii=False))

        # 自然码切换
        await pg.select_option('#schemeSel','ziranma'); await pg.wait_for_timeout(200)
        zr = await pg.evaluate("()=>({checks:[...document.querySelectorAll('#checkrow .ckc')].map(e=>e.textContent+(e.classList.contains('ok')?'✓':'✗'))})")
        print("ziranma checks:", json.dumps(zr, ensure_ascii=False))
        await pg.screenshot(path=str(SHOTS / 'shot-6-ziranma.png'), full_page=True)

        await b.close()

asyncio.run(main())
