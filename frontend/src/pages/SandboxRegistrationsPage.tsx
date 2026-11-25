import React from 'react';
import { PageHeader, PageShell } from '../components/ui';
import { SandboxRegistrationsPanel } from '../components/SandboxRegistrationsPanel';

export const SandboxRegistrationsPage: React.FC = () => (
  <PageShell>
    <PageHeader
      eyebrow="Verification Governance"
      title="Authorized Sandbox Postings"
      subtitle="Register and revoke candidate-owned sandbox postings permitted for end-to-end receipt verification."
    />
    <div className="mt-6">
      <SandboxRegistrationsPanel />
    </div>
  </PageShell>
);
