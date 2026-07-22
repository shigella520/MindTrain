import type { KnowledgeDomain } from '../types/api'

export function initialTrainingDomainId(domains: KnowledgeDomain[]): string {
  return domains.length === 1 ? domains[0].id : ''
}
