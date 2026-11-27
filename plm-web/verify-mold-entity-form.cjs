// Read-only verification against 8111. Credentials come from the local test environment.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const path=require('node:path');
(async()=>{
  const origin=process.env.PLM_QA_ORIGIN||'http://127.0.0.1:8111';
  const login=await fetch(origin+'/api/v1/auth/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({username:process.env.PLM_QA_USERNAME,password:process.env.PLM_QA_PASSWORD})}).then(r=>r.json());
  assert.equal(login.code,0,'Local test login must succeed');
  const browser=await chromium.launch({headless:true,executablePath:'D:/work/Yuewei/.codex_tmp/ms-playwright/chromium-1234/chrome-win64/chrome.exe'});
  try{
    const page=await browser.newPage({viewport:{width:1788,height:1200}}),errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    await page.addInitScript(token=>localStorage.setItem('plm_token',token),login.data.token);
    await page.goto(origin+'/#/mold-share-mappings');
    const button=page.getByRole('button',{name:'新增共模映射',exact:true});await button.waitFor();
    assert.deepEqual(await button.evaluate(el=>({background:getComputedStyle(el).backgroundColor,color:getComputedStyle(el).color})),{background:'rgb(255, 255, 255)',color:'rgb(0, 0, 0)'});
    await button.click();const dialog=page.getByRole('dialog');await dialog.waitFor();
    const text=await dialog.innerText();for(const label of ['孔型','已有共模机型','新增适用机型','新规则编码预览'])assert.ok(text.includes(label),label);
    assert.equal(await dialog.locator('input').first().inputValue(),'');
    assert.ok(await dialog.getByText('请选择产品',{exact:true}).count());
    await dialog.locator('.el-form-item').filter({has:page.locator('.el-form-item__label',{hasText:'孔型'})}).locator('.el-select').click();
    for(const option of ['大孔','精孔','混合'])assert.ok(await page.getByRole('option',{name:option,exact:true}).count(),option);
    await page.keyboard.press('Escape');
    await page.screenshot({path:path.resolve('../plm-server/registry-release-r2/mold-entity-form-8111.png'),fullPage:true});
    assert.deepEqual(errors,[]);console.log(JSON.stringify({formVisible:true,holeOptions:true,productZeroFixed:true,whiteButton:true,pageErrors:errors}));
  }finally{await browser.close()}
})().catch(e=>{console.error(e.message);process.exit(1)});
