export interface CrdScenario {
  id: string;
  name: string;
  description: string;
  hooks: CrdHookVariant[];
}

export interface CrdHookVariant {
  id: string;
  hookName: string;
  label: string;
  requestJson: Record<string, unknown>;
}
