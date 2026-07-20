export const DEFAULT_SCHEDULER_PROVIDER_ID = 'weighted'
export const DEFAULT_SCHEDULER_PROVIDER_NAME = '加权调度'

const schedulerProviderNames: Record<string, string> = {
  [DEFAULT_SCHEDULER_PROVIDER_ID]: DEFAULT_SCHEDULER_PROVIDER_NAME,
}

export function schedulerProviderName(providerId: string): string {
  return schedulerProviderNames[providerId] ?? providerId
}
