import { chromium, type Browser, type BrowserContext, type Page } from 'playwright';
import { createHash } from 'node:crypto';
import { agentIds, type AgentId, type Proposal } from './types.js';

export class BrowserWorkers {
  browser!: Browser; context!: BrowserContext; pages = new Map<AgentId, Page>();
  constructor(readonly origin: string) {}
  async start() {
    this.browser = await chromium.launch({ headless: true, ...(process.env.DIYALAMP_CHROME ? { executablePath: process.env.DIYALAMP_CHROME } : {}) });
    this.context = await this.browser.newContext({ viewport: { width: 960, height: 620 }, acceptDownloads: false, serviceWorkers: 'block' });
    await this.context.route('**/*', route => {
      const url = new URL(route.request().url());
      return url.origin === this.origin ? route.continue() : route.abort('blockedbyclient');
    });
    this.context.setDefaultTimeout(8000);
    await Promise.all(agentIds.map(async id => { const page = await this.context.newPage(); this.pages.set(id, page); await page.goto(`${this.origin}/fixture/${id}`); }));
  }
  async observe(id: AgentId) {
    const page = this.pages.get(id)!; await page.reload();
    const text = await page.locator('main').innerText();
    return createHash('sha256').update(page.url() + '\n' + text).digest('hex');
  }
  async execute(proposal: Proposal) {
    const page = this.pages.get(proposal.agent)!;
    await page.locator('#proposal').fill(proposal.id);
    await page.locator('#execute').click();
    await page.waitForFunction(id => document.getElementById('result')?.getAttribute('data-receipt') === id, proposal.id);
    return page.locator('#result').innerText();
  }
  async receiptVisible(id: AgentId, proposalId: string) {
    const page = this.pages.get(id)!; await page.reload();
    return (await page.locator('#result').getAttribute('data-receipt')) === proposalId;
  }
  async screenshot(id: AgentId) { return this.pages.get(id)!.screenshot({ type: 'png', animations: 'disabled' }); }
  async close() { if (this.context) await this.context.close(); if (this.browser) await this.browser.close(); }
}
