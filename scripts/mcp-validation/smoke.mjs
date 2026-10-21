import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { writeFile, mkdir } from 'node:fs/promises';
const login = await fetch('http://127.0.0.1:18180/api/auth/login', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({username:'viewer',password:'viewer123'}) });
if (!login.ok) throw new Error('Local synthetic login failed');
const token = (await login.json()).data.token;
const transport = new StreamableHTTPClientTransport(new URL('http://127.0.0.1:18181/mcp'), { requestInit:{headers:{Authorization:'Bearer '+token}} });
const client = new Client({name:'official-sdk-portfolio-validation',version:'1.0.0'});
try {
  await client.connect(transport);
  const tools = await client.listTools();
  if (tools.tools.length !== 5 || tools.tools.some(tool => !tool.annotations?.readOnlyHint)) throw new Error('Read capability boundary changed');
  const replenish = await client.callTool({ name:'suggest-replenish',arguments:{skus:['AUD-001'],days:7,targetCoverDays:14} });
  const item = JSON.parse(replenish.content.find(part=>part.type==='text').text).items[0];
  if (item.targetQty !== 90 || item.rawGap !== 72 || item.suggestedQty !== 72) throw new Error('Java rule observations differ');
  const templates = await client.listResourceTemplates();
  const resource = await client.readResource({uri:'supply://stock/AUD-001'});
  if (JSON.parse(resource.contents[0].text).items[0].availableQty !== 18) throw new Error('Resource read differs');
  const report = { officialSdk:'@modelcontextprotocol/sdk@1.30.0',transport:'Streamable HTTP',tools:tools.tools.map(tool=>tool.name),resourceTemplates:templates.resourceTemplates.length,targetQty:item.targetQty,rawGap:item.rawGap,suggestedQty:item.suggestedQty,actualJavaHttp:true,businessWrites:false };
  const out = new URL('../../artifacts/mcp-official-sdk.json', import.meta.url);
  await mkdir(new URL('../../artifacts/',import.meta.url),{recursive:true});
  await writeFile(out,JSON.stringify(report,null,2));
  process.stdout.write(JSON.stringify(report)+'\n');
} finally { await client.close(); }
