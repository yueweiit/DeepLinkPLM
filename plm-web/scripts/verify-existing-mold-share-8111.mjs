import { chromium } from '@playwright/test'

const browser=await chromium.launch({headless:true,executablePath:'D:/work/Yuewei/.codex_tmp/ms-playwright/chromium-1234/chrome-win64/chrome.exe'})
const page=await browser.newPage({viewport:{width:1440,height:1000}})
const errors=[]
page.on('console',message=>{if(message.type()==='error')errors.push(message.text())})
page.on('pageerror',error=>errors.push(error.message))
await page.goto('http://127.0.0.1:8111/',{waitUntil:'networkidle'})
await page.locator('input').nth(0).fill('engineer01')
await page.locator('input').nth(1).fill('plm123456')
await page.getByRole('button',{name:/登录|Sign in/i}).click()
await page.waitForURL(url=>!url.pathname.includes('login'),{timeout:15000}).catch(()=>{})
await page.goto('http://127.0.0.1:8111/#/mold-share-mappings',{waitUntil:'networkidle'})
await page.getByRole('button',{name:'新增共模映射'}).click()
await page.getByText('已开模机型',{exact:true}).waitFor({timeout:15000})
const text=await page.locator('.el-dialog').innerText()
await page.screenshot({path:'D:/work/PLM_new/DeepLinkPLM-main/docs/existing-mold-share-8111.png',fullPage:true})
console.log(JSON.stringify({url:page.url(),hasProduct:text.includes('产品'),hasReference:text.includes('已开模机型'),hasTarget:text.includes('新增适用机型'),hasHole:text.includes('孔位类型'),hasMaterials:text.includes('材质（可多选）'),hasPreview:text.includes('自动匹配结果'),errors},null,2))
await browser.close()
