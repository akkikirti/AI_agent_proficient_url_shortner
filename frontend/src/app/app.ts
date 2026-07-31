import { DatePipe, NgFor, NgIf } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

type ScenarioType = 'GREENFIELD' | 'BROWNFIELD' | 'AMBIGUOUS';
type WorkflowStatus = 'DRAFT' | 'RUNNING' | 'WAITING_FOR_APPROVAL' | 'COMPLETED' | 'FAILED' | 'SAFE_STOPPED' | 'ROLLED_BACK';

interface ShortUrlResponse {
  code: string;
  shortUrl: string;
  destinationUrl: string;
  title: string | null;
  createdAt: string;
  expiresAt: string | null;
  accessCount: number;
  active: boolean;
}

interface UrlListResponse {
  urls: ShortUrlResponse[];
}

interface AccessEvent {
  accessedAt: string;
  userAgent: string | null;
  referer: string | null;
}

interface UrlAnalyticsResponse {
  code: string;
  destinationUrl: string;
  title: string | null;
  createdAt: string;
  expiresAt: string | null;
  accessCount: number;
  lastAccessedAt: string | null;
  active: boolean;
  expired: boolean;
  recentAccesses: AccessEvent[];
}

interface ApprovalGate {
  id: string;
  nodeId: string;
  title: string;
  approved: boolean;
  rejected: boolean;
  approver: string | null;
  decidedAt: string | null;
  notes: string | null;
}

interface WorkflowNode {
  id: string;
  name: string;
  type: string;
  dependsOn: string[];
  approvalRequired: boolean;
  status: string;
  retryCount: number;
  maxRetries: number;
  assignedAgent: string;
  summary: string;
  artifacts: string[];
  risks: string[];
}

interface DecisionRecord {
  id: string;
  agent: string;
  input: string;
  output: string;
  reasoning: string;
  engineerDecision: string;
  approvalStatus: string;
  repositoryReference: string;
  createdAt: string;
}

interface WorkflowState {
  id: string;
  projectName: string;
  requirement: string;
  scenario: ScenarioType;
  status: WorkflowStatus;
  approvals: ApprovalGate[];
  nodes: WorkflowNode[];
  decisions: DecisionRecord[];
  artifactIndex: string[];
  safeStopReason: string | null;
  metrics: {
    workflowSuccessRate: number;
    agentSuccessRate: number;
    totalRetries: number;
    rollbackCount: number;
    fallbackCount: number;
    mttrMillis: number;
    averageTaskDurationMillis: number;
    workflowDurationMillis: number;
    approvalWaitMillis: number;
    validationLatencyMillis: number;
    endToEndLatencyMillis: number;
  };
}

interface WorkflowSummary {
  id: string;
  projectName: string;
  scenario: ScenarioType;
  status: WorkflowStatus;
  updatedAt: string;
  completedNodes: number;
  totalNodes: number;
}

@Component({
  selector: 'app-root',
  imports: [FormsModule, NgIf, NgFor, DatePipe],
  template: `
    <main class="shell">
      <section class="hero">
        <div>
          <p class="eyebrow">Agentic SDLC Prototype</p>
          <h1>URL shortener delivery with governed orchestration.</h1>
          <p class="lede">
            Build URLs, inspect analytics, and drive a stateful SDLC workflow with approvals,
            retries, replanning, rollback, and safe-stop controls.
          </p>
        </div>
        <div class="hero-metrics">
          <article>
            <span>Tracked URLs</span>
            <strong>{{ urls().length }}</strong>
          </article>
          <article>
            <span>Workflow Status</span>
            <strong>{{ selectedWorkflow()?.status ?? 'NONE' }}</strong>
          </article>
          <article>
            <span>Pending Approvals</span>
            <strong>{{ pendingApprovals() }}</strong>
          </article>
        </div>
      </section>

      <section class="grid two-up">
        <article class="panel">
          <header>
            <h2>Create short URL</h2>
            <p>Core product capability with aliasing and analytics capture.</p>
          </header>
          <form class="stack" (ngSubmit)="createUrl()">
            <label>
              Destination URL
              <input [(ngModel)]="urlForm.destinationUrl" name="destinationUrl" placeholder="https://example.com/spec" required />
            </label>
            <label>
              Custom alias
              <input [(ngModel)]="urlForm.alias" name="alias" placeholder="release-plan" />
            </label>
            <label>
              Title
              <input [(ngModel)]="urlForm.title" name="title" placeholder="Release plan" />
            </label>
            <button type="submit">Create short URL</button>
          </form>
          <p class="message" *ngIf="urlMessage()">{{ urlMessage() }}</p>
        </article>

        <article class="panel">
          <header>
            <h2>Create workflow</h2>
            <p>Requirement intake normalized into a dependency-aware execution graph.</p>
          </header>
          <form class="stack" (ngSubmit)="createWorkflow()">
            <label>
              Project name
              <input [(ngModel)]="workflowForm.projectName" name="projectName" required />
            </label>
            <label>
              Scenario
              <select [(ngModel)]="workflowForm.scenario" name="scenario">
                <option value="GREENFIELD">Greenfield</option>
                <option value="BROWNFIELD">Brownfield</option>
                <option value="AMBIGUOUS">Ambiguous</option>
              </select>
            </label>
            <label>
              Requirement
              <textarea [(ngModel)]="workflowForm.requirement" name="requirement" rows="4"></textarea>
            </label>
            <button type="submit">Create workflow</button>
          </form>
          <p class="message" *ngIf="workflowMessage()">{{ workflowMessage() }}</p>
        </article>
      </section>

      <section class="grid two-up">
        <article class="panel">
          <header>
            <h2>Short URLs</h2>
            <p>Recent links with routing state and click volume.</p>
          </header>
          <div class="list" *ngIf="urls().length; else emptyUrls">
            <button class="list-item" type="button" *ngFor="let item of urls()" (click)="loadAnalytics(item.code)">
              <div>
                <strong>{{ item.code }}</strong>
                <span>{{ item.destinationUrl }}</span>
              </div>
              <span class="pill">{{ item.accessCount }} clicks</span>
            </button>
          </div>
          <ng-template #emptyUrls>
            <p class="empty">No short URLs yet.</p>
          </ng-template>
        </article>

        <article class="panel">
          <header>
            <h2>Analytics</h2>
            <p>Per-link visibility for the last recorded accesses.</p>
          </header>
          <ng-container *ngIf="selectedAnalytics() as analytics; else emptyAnalytics">
            <div class="metric-band">
              <div>
                <span>Total clicks</span>
                <strong>{{ analytics.accessCount }}</strong>
              </div>
              <div>
                <span>Last seen</span>
                <strong>{{ analytics.lastAccessedAt ? (analytics.lastAccessedAt | date:'medium') : 'Never' }}</strong>
              </div>
            </div>
            <div class="stack compact">
              <div class="audit-row" *ngFor="let event of analytics.recentAccesses">
                <span>{{ event.accessedAt | date:'medium' }}</span>
                <span>{{ event.userAgent || 'Unknown agent' }}</span>
              </div>
            </div>
          </ng-container>
          <ng-template #emptyAnalytics>
            <p class="empty">Select a short URL to inspect analytics.</p>
          </ng-template>
        </article>
      </section>

      <section class="grid two-up">
        <article class="panel">
          <header>
            <h2>Workflows</h2>
            <p>Persisted workflow states with scenario-aware orchestration.</p>
          </header>
          <div class="list" *ngIf="workflows().length; else emptyWorkflows">
            <button class="list-item" type="button" *ngFor="let workflow of workflows()" (click)="selectWorkflow(workflow.id)">
              <div>
                <strong>{{ workflow.projectName }}</strong>
                <span>{{ workflow.scenario }} · {{ workflow.status }}</span>
              </div>
              <span class="pill">{{ workflow.completedNodes }}/{{ workflow.totalNodes }}</span>
            </button>
          </div>
          <ng-template #emptyWorkflows>
            <p class="empty">No workflows created yet.</p>
          </ng-template>
        </article>

        <article class="panel" *ngIf="selectedWorkflow() as workflow">
          <header>
            <h2>{{ workflow.projectName }}</h2>
            <p>{{ workflow.requirement }}</p>
          </header>
          <div class="actions">
            <button type="button" (click)="executeWorkflow()">Execute</button>
            <button type="button" class="secondary" (click)="approveAll()">Approve pending</button>
            <button type="button" class="secondary" (click)="replanWorkflow()">Replan</button>
            <button type="button" class="danger" (click)="rollbackWorkflow()">Rollback</button>
          </div>
          <div class="metric-band">
            <div>
              <span>Success rate</span>
              <strong>{{ percentage(workflow.metrics.agentSuccessRate) }}</strong>
            </div>
            <div>
              <span>Retries</span>
              <strong>{{ workflow.metrics.totalRetries }}</strong>
            </div>
            <div>
              <span>Fallbacks</span>
              <strong>{{ workflow.metrics.fallbackCount }}</strong>
            </div>
          </div>
          <div class="stack compact">
            <div class="audit-row" *ngFor="let node of workflow.nodes">
              <div>
                <strong>{{ node.name }}</strong>
                <span>{{ node.assignedAgent }}</span>
              </div>
              <span class="pill">{{ node.status }}</span>
            </div>
          </div>
        </article>
      </section>

      <section class="panel" *ngIf="selectedWorkflow() as workflow">
        <header>
          <h2>Decision lineage</h2>
          <p>Traceable decisions, repository references, and approval posture.</p>
        </header>
        <div class="stack compact">
          <div class="audit-row" *ngFor="let decision of workflow.decisions.slice().reverse()">
            <div>
              <strong>{{ decision.agent }}</strong>
              <span>{{ decision.output }}</span>
            </div>
            <span>{{ decision.createdAt | date:'short' }}</span>
          </div>
        </div>
      </section>
    </main>
  `,
  styles: [],
})
export class App {
  private readonly http = inject(HttpClient);
  protected readonly urls = signal<ShortUrlResponse[]>([]);
  protected readonly workflows = signal<WorkflowSummary[]>([]);
  protected readonly selectedAnalytics = signal<UrlAnalyticsResponse | null>(null);
  protected readonly selectedWorkflow = signal<WorkflowState | null>(null);
  protected readonly urlMessage = signal('');
  protected readonly workflowMessage = signal('');
  protected readonly pendingApprovals = computed(
    () => this.selectedWorkflow()?.approvals.filter((approval) => !approval.approved && !approval.rejected).length ?? 0,
  );

  protected readonly urlForm = {
    destinationUrl: 'https://github.com/akkikirti/AI_agent_proficient_url_shortner',
    alias: '',
    title: 'Assignment repository',
  };

  protected readonly workflowForm = {
    projectName: 'Agentic-url-shortner',
    scenario: 'GREENFIELD' as ScenarioType,
    requirement:
      'Build a production-oriented URL shortener with analytics and a governed orchestration layer that supports approvals, retries, fallback, rollback, safe-stop, and dynamic replanning.',
  };

  constructor() {
    this.refreshUrls();
    this.refreshWorkflows();
  }

  protected createUrl(): void {
    this.http.post<ShortUrlResponse>('http://localhost:8080/api/urls', this.urlForm).subscribe({
      next: (response) => {
        this.urlMessage.set(`Created ${response.shortUrl}`);
        this.urlForm.alias = '';
        this.refreshUrls(response.code);
      },
      error: (error) => this.urlMessage.set(error.error?.message ?? 'Unable to create short URL.'),
    });
  }

  protected createWorkflow(): void {
    const payload = {
      ...this.workflowForm,
      constraints: ['Java 17', 'Spring Boot backend', 'Angular frontend'],
      acceptanceCriteria: ['Runnable prototype', 'Governed orchestration', 'Incremental commits'],
    };

    this.http.post<WorkflowState>('http://localhost:8080/api/orchestrator/workflows', payload).subscribe({
      next: (workflow) => {
        this.workflowMessage.set(`Created workflow ${workflow.id}`);
        this.selectedWorkflow.set(workflow);
        this.refreshWorkflows(workflow.id);
      },
      error: (error) => this.workflowMessage.set(error.error?.message ?? 'Unable to create workflow.'),
    });
  }

  protected loadAnalytics(code: string): void {
    this.http.get<UrlAnalyticsResponse>(`http://localhost:8080/api/urls/${code}/analytics`).subscribe({
      next: (analytics) => this.selectedAnalytics.set(analytics),
    });
  }

  protected selectWorkflow(workflowId: string): void {
    this.http.get<WorkflowState>(`http://localhost:8080/api/orchestrator/workflows/${workflowId}`).subscribe({
      next: (workflow) => this.selectedWorkflow.set(workflow),
    });
  }

  protected executeWorkflow(): void {
    const workflow = this.selectedWorkflow();
    if (!workflow) {
      return;
    }

    this.http
      .post<WorkflowState>(`http://localhost:8080/api/orchestrator/workflows/${workflow.id}/execute`, { failOnceNodeIds: ['testing'] })
      .subscribe({
        next: (updated) => {
          this.selectedWorkflow.set(updated);
          this.refreshWorkflows(updated.id);
        },
      });
  }

  protected approveAll(): void {
    const workflow = this.selectedWorkflow();
    if (!workflow) {
      return;
    }

    const pending = workflow.approvals.filter((approval) => !approval.approved && !approval.rejected);
    if (!pending.length) {
      return;
    }

    let remaining = pending.length;
    for (const approval of pending) {
      this.http
        .post<WorkflowState>(`http://localhost:8080/api/orchestrator/workflows/${workflow.id}/approvals/${approval.id}`, {
          approver: 'human-reviewer',
          approved: true,
          notes: 'Approved from UI dashboard',
        })
        .subscribe({
          next: (updated) => {
            remaining -= 1;
            this.selectedWorkflow.set(updated);
            if (remaining === 0) {
              this.refreshWorkflows(updated.id);
            }
          },
        });
    }
  }

  protected replanWorkflow(): void {
    const workflow = this.selectedWorkflow();
    if (!workflow) {
      return;
    }

    this.http
      .post<WorkflowState>(`http://localhost:8080/api/orchestrator/workflows/${workflow.id}/replan`, {
        changedNodeIds: ['architecture'],
        reason: 'Architecture review introduced an upstream design change.',
        updatedRequirement: workflow.requirement + ' Include refreshed architecture review outputs.',
      })
      .subscribe({
        next: (updated) => {
          this.selectedWorkflow.set(updated);
          this.refreshWorkflows(updated.id);
        },
      });
  }

  protected rollbackWorkflow(): void {
    const workflow = this.selectedWorkflow();
    if (!workflow) {
      return;
    }

    this.http.post<WorkflowState>(`http://localhost:8080/api/orchestrator/workflows/${workflow.id}/rollback`, { reason: 'UI-triggered rollback' }).subscribe({
      next: (updated) => {
        this.selectedWorkflow.set(updated);
        this.refreshWorkflows(updated.id);
      },
    });
  }

  protected percentage(value: number): string {
    return `${Math.round(value * 100)}%`;
  }

  private refreshUrls(selectCode?: string): void {
    this.http.get<UrlListResponse>('http://localhost:8080/api/urls').subscribe({
      next: (response) => {
        this.urls.set(response.urls);
        if (selectCode) {
          this.loadAnalytics(selectCode);
        }
      },
    });
  }

  private refreshWorkflows(selectId?: string): void {
    this.http.get<WorkflowSummary[]>('http://localhost:8080/api/orchestrator/workflows').subscribe({
      next: (response) => {
        this.workflows.set(response);
        if (selectId) {
          const match = response.find((workflow) => workflow.id === selectId);
          if (match) {
            this.selectWorkflow(match.id);
          }
        }
      },
    });
  }
}
