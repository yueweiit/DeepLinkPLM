const { chromium } = require('playwright');
const fs=require('fs');const path=require('path');
(async()=>{
 const out=path.resolve('../plm-server/registry-release-r2');
 const session=JSON.parse(fs.readFileSync(path.join(out,'browser-session.private.json'),'utf8'));
 const browser=await chromium.launch({headless:true,executablePath:'D:/work/Yuewei/.codex_tmp/ms-playwright/chromium-1234/chrome-win64/chrome.exe'});
 const page=await browser.newPage({viewport:{width:1788,height:1050}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(token=>localStorage.setItem('plm_token',token),session.token);
 await page.goto('http://127.0.0.1:5174/#/mold-share-mappings');
 try {await page.getByRole('button',{name:'新增映射',exact:true}).waitFor({timeout:30000});} catch(e) {await page.screenshot({path:path.join(out,'registry-debug.png'),fullPage:true});console.log((await page.locator('body').innerText()).slice(-2500));await browser.close();throw e;}
 await page.screenshot({path:path.join(out,'registry-list.png'),fullPage:true});
 await page.getByRole('button',{name:'新增映射',exact:true}).click();
 await page.getByRole('dialog').waitFor();
 await page.screenshot({path:path.join(out,'registry-create.png'),fullPage:true});
 for(const width of [1000,900]) {await page.setViewportSize({width,height:1000});await page.screenshot({path:path.join(out,`registry-create-${width}.png`),fullPage:true});}
 await page.getByRole('button',{name:'取消',exact:true}).click();
 await page.setViewportSize({width:1788,height:1050});
 await page.locator('.filters > .el-select').last().click();
 await page.getByRole('option',{name:'全部',exact:true}).click();
 await page.getByRole('button',{name:'查询',exact:true}).click();
 await page.getByText('QA模拟更新后的依据',{exact:true}).waitFor();
 await page.screenshot({path:path.join(out,'registry-saved-inactive.png'),fullPage:true});
 await page.locator('select').selectOption('es');
 await page.waitForTimeout(700);
 await page.screenshot({path:path.join(out,'registry-spanish.png'),fullPage:true});
 await page.locator('select').selectOption('zh-CN');
 fs.writeFileSync(path.join(out,'browser-verification.json'),JSON.stringify({url:page.url(),pageErrors:errors,viewports:[1788,1000,900],newMappingDialogVisible:true},null,2));
 console.log(JSON.stringify({url:page.url(),pageErrors:errors,newMappingDialogVisible:true}));
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
