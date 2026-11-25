import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { apiDelete, apiGet, apiPut } from '../../shared/api/client'
import { queryKeys } from '../../shared/api/queryKeys'
import { businessLogoEndpoint, businessLogoQuery, type BusinessLogo } from '../../shared/businessLogo'

export type BusinessProfile = { name: string; address: string; phone: string }
export type CreditLimit = { creditLimit: number | null; enabled: boolean; updatedAt?: string | null; updatedBy?: string | null }
type CreditDraft = { enabled: boolean; amount: string }
type LogoDraft = { content: string; type: string }

const businessProfileEndpoint = '/api/settings/business-profile'
const creditLimitEndpoint = '/api/settings/credit-limit'

export const businessProfileQuery = {
  queryKey: queryKeys.settings.businessProfile,
  queryFn: ({ signal }: { signal: AbortSignal }) => apiGet<BusinessProfile>(businessProfileEndpoint, signal),
}
export const creditLimitQuery = {
  queryKey: queryKeys.settings.creditLimit,
  queryFn: ({ signal }: { signal: AbortSignal }) => apiGet<CreditLimit>(creditLimitEndpoint, signal),
}

function useSettingsEditors() {
  const client = useQueryClient()
  const active = useRef(false)
  const [profileDraft, setProfileDraft] = useState<Partial<BusinessProfile>>({})
  const [creditDraft, setCreditDraft] = useState<CreditDraft>()
  const [logoDraft, setLogoDraft] = useState<LogoDraft | null>()
  const saveProfile = useMutation({
    mutationFn: (profile: BusinessProfile) => apiPut<BusinessProfile>(businessProfileEndpoint, profile),
    onSuccess: async (profile) => {
      if (!active.current) return
      await client.cancelQueries({ queryKey: businessProfileQuery.queryKey })
      if (!active.current) return
      client.setQueryData(businessProfileQuery.queryKey, profile)
      setProfileDraft({})
    },
  })
  const saveCredit = useMutation({
    mutationFn: (creditLimit: number | null) => apiPut<CreditLimit>(creditLimitEndpoint, { creditLimit }),
    onSuccess: async (limit) => {
      if (!active.current) return
      await client.cancelQueries({ queryKey: creditLimitQuery.queryKey })
      if (!active.current) return
      client.setQueryData(creditLimitQuery.queryKey, limit)
      setCreditDraft(undefined)
    },
  })
  const saveLogo = useMutation({
    mutationFn: async (draft: LogoDraft | null): Promise<BusinessLogo> => {
      if (draft) return apiPut<BusinessLogo>(businessLogoEndpoint, { content: draft.content })
      await apiDelete<void>(businessLogoEndpoint)
      return { content: null }
    },
    onSuccess: async (logo) => {
      if (!active.current) return
      await client.cancelQueries({ queryKey: businessLogoQuery.queryKey })
      if (!active.current) return
      client.setQueryData(businessLogoQuery.queryKey, logo)
      setLogoDraft(undefined)
    },
  })

  useEffect(() => {
    active.current = true
    return () => { active.current = false }
  }, [])

  return { profileDraft, setProfileDraft, creditDraft, setCreditDraft, logoDraft, setLogoDraft, saveProfile, saveCredit, saveLogo }
}

const SettingsEditorContext = createContext<ReturnType<typeof useSettingsEditors> | null>(null)

export function SettingsEditorProvider({ children }: { children: ReactNode }) {
  const editors = useSettingsEditors()
  const hasDraft = editors.logoDraft !== undefined || Boolean(editors.creditDraft || Object.keys(editors.profileDraft).length)

  useEffect(() => {
    if (!hasDraft) return
    function protectDraft(event: BeforeUnloadEvent) {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', protectDraft)
    return () => window.removeEventListener('beforeunload', protectDraft)
  }, [hasDraft])

  return <SettingsEditorContext.Provider value={editors}>{children}</SettingsEditorContext.Provider>
}

export function useSettingsEditorState() {
  const editors = useContext(SettingsEditorContext)
  if (!editors) throw new Error('Settings editors require a SettingsEditorProvider')
  return editors
}
